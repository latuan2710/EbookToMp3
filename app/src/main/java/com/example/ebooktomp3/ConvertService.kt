package com.example.ebooktomp3

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import androidx.core.app.ServiceCompat
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

object JobState {
    const val WAITING = 0
    const val RUNNING = 1
    const val DONE = 2
    const val ERROR = 3
    const val CANCELED = 4
}

class BookJob(
    val uri: String,
    val name: String,
    val book: String,
    val lang: String,
    val group: Int,
    val startChapter: Int,
    val rate: Float,
    val tree: String?,
    val voice: String? = null
) {
    @Volatile var status = JobState.WAITING
    @Volatile var percent = 0
    @Volatile var note = ""
    @Volatile var doneGroups = 0
    @Volatile var partialUri: String? = null
}

data class Progress(
    val running: Boolean = false,
    val percent: Int = 0,
    val title: String = "Sẵn sàng",
    val detail: String = "Chọn sách rồi bấm bắt đầu",
    val startAt: Long = 0,
    val etaAt: Long = -1,
    val endAt: Long = 0,
    val log: String = "",
    val done: Boolean = false,
    val error: Boolean = false
)

data class DoneFile(
    val book: String,
    val file: String,
    val loc: String,
    val partial: Boolean
)

class OutFile(val os: OutputStream, val uri: Uri, private val onClose: () -> Unit) {
    fun close() {
        try { os.close() } catch (_: Exception) {}
        onClose()
    }
}

class BookSource(val zip: ZipFile?, val names: List<String>, private val txt: String?) {
    fun text(i: Int): String {
        val z = zip ?: return txt ?: ""
        val e = z.getEntry(names[i]) ?: return ""
        val raw = z.getInputStream(e).use { it.readBytes() }
        return Html.fromHtml(String(raw), Html.FROM_HTML_MODE_LEGACY).toString().replace("\uFFFC", "")
    }

    fun close() {
        try { zip?.close() } catch (_: Exception) {}
    }
}

class ConvertService : Service() {
    companion object {
        @Volatile var listener: ((Progress) -> Unit)? = null
        @Volatile var last: Progress? = null
        @Volatile var cancel = false      // bỏ qua sách đang đọc
        @Volatile var stopAll = false     // dừng cả hàng đợi
        @Volatile var running = false
        @Volatile var paused = false
        val jobs = CopyOnWriteArrayList<BookJob>()
        val lock = Any()
        val history = CopyOnWriteArrayList<DoneFile>()

        private fun clean(x: String) = x.replace('\t', ' ').replace('\n', ' ')

        @Synchronized
        fun loadHistory(ctx: Context) {
            if (history.isNotEmpty()) return
            val txt = ctx.getSharedPreferences("history", Context.MODE_PRIVATE).getString("lines", "") ?: ""
            for (line in txt.split('\n')) {
                val p = line.split('\t')
                if (p.size >= 4) history.add(DoneFile(p[0], p[1], p[2], p[3] == "1"))
            }
        }

        @Synchronized
        private fun saveHistory(ctx: Context) {
            val txt = history.takeLast(500).joinToString("\n") {
                "${clean(it.book)}\t${clean(it.file)}\t${clean(it.loc)}\t${if (it.partial) 1 else 0}"
            }
            ctx.getSharedPreferences("history", Context.MODE_PRIVATE).edit().putString("lines", txt).apply()
        }

        @Synchronized
        fun addDone(ctx: Context, d: DoneFile) {
            loadHistory(ctx)
            history.add(d)
            while (history.size > 500) history.removeAt(0)
            saveHistory(ctx)
        }

        @Synchronized
        fun clearHistory(ctx: Context) {
            history.clear()
            saveHistory(ctx)
        }

        fun add(ctx: Context, list: List<BookJob>) {
            synchronized(lock) {
                if (!running) {
                    jobs.removeAll { it.status != JobState.WAITING && it.status != JobState.RUNNING }
                }
                jobs.addAll(list)
            }
            saveQueue(ctx)
        }

        // Lưu hàng đợi để làm tiếp nếu app bị tắt giữa chừng
        @Synchronized
        fun saveQueue(ctx: Context) {
            val txt = jobs.filter { it.status == JobState.WAITING || it.status == JobState.RUNNING }
                .joinToString("\n") { j ->
                    listOf(
                        j.uri, j.name, j.book, j.lang, j.group.toString(), j.startChapter.toString(),
                        j.rate.toString(), j.tree ?: "", j.voice ?: "", j.doneGroups.toString(), j.partialUri ?: ""
                    ).joinToString("\t") { clean(it) }
                }
            ctx.getSharedPreferences("queue", Context.MODE_PRIVATE).edit().putString("lines", txt).apply()
        }

        @Synchronized
        fun loadQueue(ctx: Context) {
            if (running || jobs.isNotEmpty()) return
            val txt = ctx.getSharedPreferences("queue", Context.MODE_PRIVATE).getString("lines", "") ?: ""
            for (line in txt.split('\n')) {
                if (line.isBlank()) continue
                val p = line.split('\t')
                if (p.size < 11) continue
                val j = BookJob(
                    p[0], p[1], p[2], p[3], p[4].toIntOrNull() ?: 1, p[5].toIntOrNull() ?: 1,
                    p[6].toFloatOrNull() ?: 1f, p[7].ifEmpty { null }, p[8].ifEmpty { null }
                )
                j.doneGroups = p[9].toIntOrNull() ?: 0
                j.partialUri = p[10].ifEmpty { null }
                jobs.add(j)
            }
        }
    }

    private val logLines = mutableListOf<String>()
    private var startAt = 0L
    private var jobStart = 0L
    private var latestStartId = 0
    private var lastLoc = ""
    @Volatile private var cur: BookJob? = null
    private var wl: PowerManager.WakeLock? = null
    private var nTitle = ""
    private var nPct = 0
    private var nText = ""

    private fun svcPi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, ConvertService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    override fun onBind(i: Intent?): IBinder? = null

    // Giữ CPU thức khi tắt màn hình; luôn gọi trong synchronized(lock)
    private fun releaseWl() {
        try {
            wl?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wl = null
    }

    private fun acquireWl() {
        releaseWl()
        wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ebooktomp3:convert")
            .apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
    }

    override fun onDestroy() {
        synchronized(lock) { releaseWl() }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val act = intent?.action
        if (act == "toggle_pause" || act == "stop_all") {
            synchronized(lock) {
                latestStartId = startId
                if (running) {
                    if (act == "toggle_pause") {
                        paused = !paused
                        getSystemService(NotificationManager::class.java)
                            .notify(1, notif(nTitle, nPct, true, nText))
                    } else stopAll = true
                } else stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("cv", "Chuyển đổi", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notif("Đang chuẩn bị...", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        synchronized(lock) {
            latestStartId = startId
            if (!running && jobs.any { it.status == JobState.WAITING }) {
                running = true
                stopAll = false
                cancel = false
                startAt = SystemClock.elapsedRealtime()
                logLines.clear()
                lastLoc = ""
                paused = false
                acquireWl()
                Thread { worker() }.start()
            } else if (!running) {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun worker() {
        var sid = 0
        var done = 0
        var err = 0
        var canc = 0
        try {
            while (true) {
                val job = synchronized(lock) {
                    val j = jobs.firstOrNull { it.status == JobState.WAITING }
                    if (j == null) {
                        emitFinal(done, err, canc)
                        running = false
                        releaseWl()
                        sid = latestStartId
                    } else {
                        j.status = JobState.RUNNING
                    }
                    j
                } ?: break

                cur = job
                jobStart = SystemClock.elapsedRealtime()
                cancel = false
                log("Bắt đầu: ${job.name}")
                saveQueue(applicationContext)
                try {
                    convertJob(job)
                } catch (t: Throwable) {
                    job.status = JobState.ERROR
                    job.note = t.message ?: "Lỗi không rõ"
                    log("Lỗi ${job.name}: ${job.note}")
                }
                if (job.status == JobState.RUNNING) job.status = JobState.DONE
                when (job.status) {
                    JobState.DONE -> done++
                    JobState.ERROR -> err++
                    JobState.CANCELED -> canc++
                }
                cur = null
                if (stopAll) {
                    synchronized(lock) {
                        jobs.filter { it.status == JobState.WAITING }.forEach {
                            it.status = JobState.CANCELED
                            canc++
                        }
                    }
                }
                saveQueue(applicationContext)
            }
        } catch (t: Throwable) {
            synchronized(lock) {
                running = false
                releaseWl()
                sid = latestStartId
            }
            emit("Có lỗi", t.message ?: "", last?.percent ?: 0, error = true)
        }
        stopSelf(sid)
    }

    private fun emitFinal(done: Int, err: Int, canc: Int) {
        val stopped = stopAll
        stopAll = false
        paused = false
        cur = null
        val d = buildString {
            append("Xong $done sách")
            if (err > 0) append(", lỗi $err")
            if (canc > 0) append(", huỷ $canc")
            if (lastLoc.isNotEmpty()) append("\nLưu tại: $lastLoc")
        }
        log(if (stopped) "Đã dừng hàng đợi" else "Hoàn tất hàng đợi")
        emit(
            if (stopped) "Đã dừng" else "Hoàn tất", d,
            if (err == 0 && !stopped) 100 else (last?.percent ?: 0), done = true
        )
    }

    private fun notif(title: String, pct: Int, ongoing: Boolean, text: String = ""): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, "cv")
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi).setOnlyAlertOnce(true)
        if (ongoing) {
            b.setProgress(100, pct, false).setOngoing(true)
            nTitle = title
            nPct = pct
            nText = text
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
                    ),
                    if (paused) "Tiếp tục" else "Tạm dừng", svcPi("toggle_pause", 1)
                ).build()
            )
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Dừng", svcPi("stop_all", 2)
                ).build()
            )
        } else b.setAutoCancel(true)
        return b.build()
    }

    private fun log(s: String) {
        logLines.add(s)
        while (logLines.size > 6) logLines.removeAt(0)
    }

    private fun emit(title: String, detail: String, pct: Int, etaAt: Long = -1, done: Boolean = false, error: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val j = cur
        if (j != null && !done && !error) j.percent = pct
        val total = jobs.size
        val prefix = if (j != null && total > 1) "Sách ${jobs.indexOf(j) + 1}/$total • " else ""
        val t = if (done || error) title else prefix + title
        val d = if (j != null && !done && !error) j.name + (if (detail.isEmpty()) "" else "\n$detail") else detail
        val p = Progress(
            running = !done && !error, percent = pct, title = t, detail = d,
            startAt = startAt, etaAt = etaAt, endAt = if (done || error) now else 0,
            log = logLines.joinToString("\n"), done = done, error = error
        )
        last = p
        listener?.invoke(p)
        val nm = getSystemService(NotificationManager::class.java)
        if (done || error) nm.notify(2, notif(t, pct, false, d))
        else nm.notify(1, notif(t, pct, true, d))
    }

    private fun openOut(name: String, tree: Uri?): OutFile {
        if (tree == null) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/EbookMp3")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val item = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
            return OutFile(contentResolver.openOutputStream(item)!!, item) {
                val v = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                contentResolver.update(item, v, null, null)
            }
        }
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(contentResolver, dir, "audio/mpeg", name)!!
        return OutFile(contentResolver.openOutputStream(doc)!!, doc) {}
    }

    private fun finishFile(lame: AndroidLame?, out: OutFile) {
        try {
            lame?.let {
                val b = ByteArray(7200)
                val n = it.flush(b)
                if (n > 0) out.os.write(b, 0, n)
                it.close()
            }
        } finally {
            out.close()
        }
    }

    private fun convertJob(job: BookJob) {
        val uri = Uri.parse(job.uri)
        val tree = job.tree?.let { Uri.parse(it) }
        val book = job.book.replace(Regex("[^\\p{L}\\p{N}_ -]"), "").trim().ifEmpty { "Sach" }.replace(' ', '_')

        emit("Đang đọc sách...", "", 0)
        // Chép sách vào cache theo luồng, không nạp cả file vào RAM
        val tmp = File(cacheDir, "book.tmp")
        tmp.delete()
        contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        val src = openSource(tmp)
        try {
            runJob(job, src, tree, book)
        } finally {
            src.close()
            tmp.delete()
        }
    }

    private fun runJob(job: BookJob, src: BookSource, tree: Uri?, book: String) {
        // Phân tích: chỉ đếm ký tự từng chương, không giữ văn bản trong RAM
        val lens = IntArray(src.names.size)
        for (i in src.names.indices) {
            if (cancel || stopAll) {
                job.status = JobState.CANCELED
                log("Đã huỷ: ${job.name}")
                return
            }
            if (i % 10 == 0) emit("Đang phân tích sách...", "Phần ${i + 1}/${src.names.size}", 0)
            val t = src.text(i)
            lens[i] = if (src.zip != null && t.trim().length <= 200) 0 else chunk(t).sumOf { it.length }
        }
        val kept = src.names.indices.filter { lens[it] > 0 }
        if (kept.isEmpty()) throw IllegalStateException("Không đọc được nội dung sách")
        val nCh = kept.size
        val startCh = maxOf(1, job.startChapter)
        val valid = kept.indices.filter { it + 1 >= startCh }
        if (valid.isEmpty())
            throw IllegalStateException("Sách chỉ có $nCh chương, không có chương nào từ chương $startCh trở đi")
        val totalChars = valid.sumOf { lens[kept[it]] }
        log("${job.name}: $nCh chương, đọc từ chương $startCh ($totalChars ký tự)")
        val cover = src.zip?.let { scaleCover(coverOf(it)) }
        if (cover != null) log("Đã lấy ảnh bìa")

        emit("Khởi tạo giọng đọc...", "Ngôn ngữ: ${job.lang}", 0)
        val tts = initTts(job.lang)
            ?: throw IllegalStateException("Máy chưa có giọng '${job.lang}'. Hãy cài Google Text-to-speech và tải giọng này.")
        job.voice?.let { vn ->
            try {
                tts.voices?.firstOrNull { it.name == vn }?.let { tts.setVoice(it) }
            } catch (_: Exception) {
            }
        }
        tts.setSpeechRate(job.rate)

        val loc = if (tree == null) "Music/EbookMp3" else (tree.lastPathSegment ?: "thư mục đã chọn")
        lastLoc = loc
        var doneChars = 0
        var files = 0
        var out: OutFile? = null
        var lame: AndroidLame? = null
        var openName = ""
        cleanupPartial(job)
        try {
            val groups = valid.chunked(maxOf(1, if (job.group <= 0) valid.size else job.group))
            loop@ for ((gi, g) in groups.withIndex()) {
                val a = g.first() + 1
                val z = g.last() + 1
                val name = if (a == z) "%s_Chuong_%03d.mp3".format(book, a)
                else "%s_Chuong_%03d-%03d.mp3".format(book, a, z)
                if (gi < job.doneGroups) {
                    for (idx in g) doneChars += lens[kept[idx]]
                    continue
                }
                val opened = openOut(name, tree)
                out = opened
                openName = name
                job.partialUri = opened.uri.toString()
                saveQueue(applicationContext)
                opened.os.write(
                    id3Tag(if (a == z) "Chương $a" else "Chương $a-$z", job.book, "${gi + 1}/${groups.size}", cover)
                )
                lame = null
                for (idx in g) {
                    val cs = chunk(src.text(kept[idx]))
                    for ((k, c) in cs.withIndex()) {
                        if (cancel || stopAll) break@loop
                        if (paused) waitWhilePaused((doneChars * 100L / totalChars).toInt())
                        if (cancel || stopAll) break@loop
                        val now = SystemClock.elapsedRealtime()
                        val eta = if (doneChars > 0) now + (now - jobStart) * (totalChars - doneChars) / doneChars else -1L
                        emit(
                            "Chương ${idx + 1}/$nCh",
                            "File ${gi + 1}/${groups.size} • Đoạn ${k + 1}/${cs.size} • $doneChars/$totalChars ký tự",
                            (doneChars * 100L / totalChars).toInt(), eta
                        )
                        val wav = File(cacheDir, "t.wav")
                        wav.delete()
                        if (synth(tts, c, wav)) lame = encode(wav, lame, out!!.os)
                        else log("Bỏ qua một đoạn bị lỗi")
                        doneChars += c.length
                    }
                }
                finishFile(lame, out!!)
                out = null
                lame = null
                files++
                addDone(applicationContext, DoneFile(job.name, name, loc, false))
                job.doneGroups = gi + 1
                job.partialUri = null
                saveQueue(applicationContext)
                log("Xong $name")
            }
        } finally {
            val o = out
            if (o != null) {
                finishFile(lame, o)
                files++
                addDone(applicationContext, DoneFile(job.name, openName, loc, true))
                job.partialUri = null
            }
            tts.shutdown()
        }

        if (cancel || stopAll) {
            job.status = JobState.CANCELED
            log("Đã huỷ: ${job.name}")
        } else {
            job.percent = 100
            log("Xong: ${job.name} ($files file)")
        }
    }

    private fun waitWhilePaused(pct: Int) {
        val t0 = SystemClock.elapsedRealtime()
        emit("Đang tạm dừng", "Bấm Tiếp tục để đọc tiếp", pct)
        synchronized(lock) { releaseWl() }
        while (paused && !cancel && !stopAll) Thread.sleep(300)
        synchronized(lock) { if (running) acquireWl() }
        jobStart += SystemClock.elapsedRealtime() - t0
    }

    // Xoá file mp3 dở dang do app bị tắt giữa chừng lần trước
    private fun cleanupPartial(job: BookJob) {
        val u = job.partialUri ?: return
        try {
            val uri = Uri.parse(u)
            if (uri.authority == "media") contentResolver.delete(uri, null, null)
            else DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (_: Exception) {
        }
        job.partialUri = null
    }

    private fun id3Frame(id: String, text: String): ByteArray {
        val txt = text.toByteArray(Charsets.UTF_16LE)
        val size = 1 + 2 + txt.size
        val bo = ByteArrayOutputStream()
        bo.write(id.toByteArray(Charsets.US_ASCII))
        bo.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        bo.write(byteArrayOf(0, 0))
        bo.write(1)
        bo.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        bo.write(txt)
        return bo.toByteArray()
    }

    // Thẻ ID3v2.3: tên chương, tên sách, số thứ tự, thể loại
    private fun id3Apic(img: ByteArray): ByteArray {
        val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
        val size = 1 + mime.size + 1 + 1 + 1 + img.size
        val bo = ByteArrayOutputStream()
        bo.write("APIC".toByteArray(Charsets.US_ASCII))
        bo.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        bo.write(byteArrayOf(0, 0))
        bo.write(0)
        bo.write(mime)
        bo.write(0)
        bo.write(3)
        bo.write(0)
        bo.write(img)
        return bo.toByteArray()
    }

    private fun id3Tag(title: String, album: String, track: String, cover: ByteArray?): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.write(id3Frame("TIT2", title))
        frames.write(id3Frame("TALB", album))
        frames.write(id3Frame("TPE1", "Ebook to MP3"))
        frames.write(id3Frame("TRCK", track))
        frames.write(id3Frame("TCON", "Audiobook"))
        if (cover != null) frames.write(id3Apic(cover))
        val f = frames.toByteArray()
        val n = f.size
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 0, 0))
        out.write(byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte()))
        out.write(f)
        return out.toByteArray()
    }

    private fun initTts(lang: String): TextToSpeech? {
        val latch = CountDownLatch(1)
        var ok = false
        val t = TextToSpeech(this) { ok = it == TextToSpeech.SUCCESS; latch.countDown() }
        latch.await(15, TimeUnit.SECONDS)
        if (!ok) return null
        val r = t.setLanguage(Locale.forLanguageTag(lang))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.shutdown(); return null
        }
        return t
    }

    private fun synth(tts: TextToSpeech, text: String, out: File): Boolean {
        val latch = CountDownLatch(1)
        var ok = true
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { latch.countDown() }
            override fun onError(id: String?) { ok = false; latch.countDown() }
        })
        if (tts.synthesizeToFile(text, Bundle(), out, "u") != TextToSpeech.SUCCESS) return false
        latch.await(180, TimeUnit.SECONDS)
        return ok && out.length() > 44
    }

    private fun encode(wav: File, lameIn: AndroidLame?, os: OutputStream): AndroidLame {
        val d = wav.readBytes()
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val ch = bb.getShort(22).toInt().coerceIn(1, 2)
        val sr = bb.getInt(24)
        var p = 12
        var start = 44
        while (p + 8 <= d.size) {
            val id = String(d, p, 4)
            val sz = bb.getInt(p + 4)
            if (id == "data") { start = p + 8; break }
            p += 8 + sz + (sz and 1)
        }
        val lame = lameIn ?: LameBuilder()
            .setInSampleRate(sr).setOutSampleRate(sr)
            .setOutChannels(ch).setOutBitrate(64).build()
        val shorts = ShortArray((d.size - start) / 2)
        ByteBuffer.wrap(d, start, d.size - start).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().get(shorts)
        val frames = shorts.size / ch
        var off = 0
        while (off < frames) {
            val m = minOf(8192, frames - off)
            val l = ShortArray(m)
            val r = ShortArray(m)
            for (k in 0 until m) {
                l[k] = shorts[(off + k) * ch]
                r[k] = if (ch > 1) shorts[(off + k) * ch + 1] else l[k]
            }
            val out = ByteArray((7200 + m * 1.25).toInt())
            val w = lame.encode(l, r, m, out)
            if (w > 0) os.write(out, 0, w)
            off += m
        }
        return lame
    }

    private fun isHtml(n: String): Boolean {
        val l = n.lowercase()
        return l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm")
    }

    private fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        var i = 0
        while (i < ra.size && i < rb.size) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit())
                (x.toLongOrNull() ?: 0L).compareTo(y.toLongOrNull() ?: 0L)
            else x.compareTo(y)
            if (c != 0) return c
            i++
        }
        return ra.size.compareTo(rb.size)
    }

    private fun openSource(f: File): BookSource {
        val isZip = f.inputStream().use { s ->
            val h = ByteArray(2)
            s.read(h) == 2 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte()
        }
        if (!isZip) return BookSource(null, listOf("txt"), f.readText())
        val z = ZipFile(f)
        val order = spineOrder(z)
            ?: z.entries().asSequence().map { it.name }.filter { isHtml(it) }.toList()
                .sortedWith { x, y -> naturalCompare(x, y) }
        return BookSource(z, order, null)
    }

    private fun readOpf(z: ZipFile): Pair<String, String>? {
        val ce = z.getEntry("META-INF/container.xml") ?: return null
        val cont = String(z.getInputStream(ce).use { it.readBytes() })
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(cont)?.groupValues?.get(1) ?: return null
        val oe = z.getEntry(opfPath) ?: return null
        return opfPath to String(z.getInputStream(oe).use { it.readBytes() })
    }

    private fun resolvePath(opfPath: String, href: String): String {
        val dir = opfPath.substringBeforeLast('/', "")
        val clean = Uri.decode(href.substringBefore('#'))
        return File(if (dir.isEmpty()) clean else "$dir/$clean").normalize().path
    }

    // Thứ tự đọc thật của sách theo mục "spine" trong file .opf
    private fun spineOrder(z: ZipFile): List<String>? {
        try {
            val (opfPath, opf) = readOpf(z) ?: return null
            val items = HashMap<String, String>()
            val skip = HashSet<String>()
            for (m in Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)) {
                val tag = m.value
                val id = Regex("\\bid=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: continue
                val href = Regex("\\bhref=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: continue
                items[id] = resolvePath(opfPath, href)
                if (Regex("properties=\"[^\"]*\\bnav\\b").containsMatchIn(tag)) skip.add(id)
            }
            val order = mutableListOf<String>()
            for (m in Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)) {
                val id = Regex("idref=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1) ?: continue
                if (id in skip) continue
                val path = items[id] ?: continue
                if (isHtml(path) && z.getEntry(path) != null && !order.contains(path)) order.add(path)
            }
            return if (order.isEmpty()) null else order
        } catch (e: Exception) {
            return null
        }
    }

    // Tìm ảnh bìa trong EPUB: cover-image, meta cover, hoặc file có tên chứa "cover"
    private fun coverOf(z: ZipFile): ByteArray? {
        try {
            val (opfPath, opf) = readOpf(z) ?: return null
            val tags = Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).map { it.value }.toList()
            fun attr(tag: String, a: String) = Regex("\\b$a=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
            var href: String? = tags.firstOrNull { Regex("properties=\"[^\"]*cover-image").containsMatchIn(it) }
                ?.let { attr(it, "href") }
            if (href == null) {
                val meta = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).map { it.value }
                    .firstOrNull { Regex("name=\"cover\"").containsMatchIn(it) }
                val cid = meta?.let { attr(it, "content") }
                if (cid != null) href = tags.firstOrNull { attr(it, "id") == cid }?.let { attr(it, "href") }
            }
            var path = href?.let { resolvePath(opfPath, it) }
            if (path == null || z.getEntry(path) == null) {
                path = z.entries().asSequence().map { it.name }.firstOrNull {
                    val l = it.lowercase()
                    l.contains("cover") && (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png"))
                }
            }
            val e = path?.let { z.getEntry(it) } ?: return null
            return z.getInputStream(e).use { it.readBytes() }
        } catch (e: Exception) {
            return null
        }
    }

    // Thu nhỏ ảnh bìa (tối đa ~800px) để mỗi file mp3 không phình to
    private fun scaleCover(raw: ByteArray?): ByteArray? {
        if (raw == null) return null
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > 800) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(
                raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val bo = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bo)
            bmp.recycle()
            return bo.toByteArray()
        } catch (e: Throwable) {
            return null
        }
    }

    private fun chunk(t: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        val parts = t.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        for (p0 in parts) for (p in p0.chunked(3000)) {
            if (sb.length + p.length + 1 > 3000) { out.add(sb.toString()); sb.clear() }
            sb.append(p).append(' ')
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out
    }
}
