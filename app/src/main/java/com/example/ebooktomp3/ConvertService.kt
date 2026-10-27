package com.example.ebooktomp3

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import java.util.zip.ZipInputStream

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
    val tree: String?
) {
    @Volatile var status = JobState.WAITING
    @Volatile var percent = 0
    @Volatile var note = ""
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

class OutFile(val os: OutputStream, private val onClose: () -> Unit) {
    fun close() {
        try { os.close() } catch (_: Exception) {}
        onClose()
    }
}

class ConvertService : Service() {
    companion object {
        @Volatile var listener: ((Progress) -> Unit)? = null
        @Volatile var last: Progress? = null
        @Volatile var cancel = false      // bỏ qua sách đang đọc
        @Volatile var stopAll = false     // dừng cả hàng đợi
        @Volatile var running = false
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

        fun add(list: List<BookJob>) {
            synchronized(lock) {
                if (!running) {
                    jobs.removeAll { it.status != JobState.WAITING && it.status != JobState.RUNNING }
                }
                jobs.addAll(list)
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

    override fun onBind(i: Intent?): IBinder? = null

    // Giữ CPU thức khi tắt màn hình; luôn gọi trong synchronized(lock)
    private fun releaseWl() {
        try {
            wl?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wl = null
    }

    override fun onDestroy() {
        synchronized(lock) { releaseWl() }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                releaseWl()
                wl = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ebooktomp3:convert")
                    .apply {
                        setReferenceCounted(false)
                        acquire(12 * 60 * 60 * 1000L)
                    }
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
        if (ongoing) b.setProgress(100, pct, false).setOngoing(true) else b.setAutoCancel(true)
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
            return OutFile(contentResolver.openOutputStream(item)!!) {
                val v = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                contentResolver.update(item, v, null, null)
            }
        }
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(contentResolver, dir, "audio/mpeg", name)!!
        return OutFile(contentResolver.openOutputStream(doc)!!) {}
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
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val isZip = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val chapters = if (isZip) epub(bytes) else listOf("1" to String(bytes))
        val chunks = chapters.map { chunk(it.second) }
        val allChars = chunks.sumOf { c -> c.sumOf { it.length } }
        if (allChars == 0) throw IllegalStateException("Không đọc được nội dung sách")
        val nCh = chapters.size
        val startCh = maxOf(1, job.startChapter)
        val valid = chapters.indices.filter { chunks[it].isNotEmpty() && it + 1 >= startCh }
        if (valid.isEmpty())
            throw IllegalStateException("Sách chỉ có $nCh chương, không có chương nào từ chương $startCh trở đi")
        val totalChars = valid.sumOf { i -> chunks[i].sumOf { it.length } }
        log("${job.name}: $nCh chương, đọc từ chương $startCh ($totalChars ký tự)")

        emit("Khởi tạo giọng đọc...", "Ngôn ngữ: ${job.lang}", 0)
        val tts = initTts(job.lang)
            ?: throw IllegalStateException("Máy chưa có giọng '${job.lang}'. Hãy cài Google Text-to-speech và tải giọng này.")
        tts.setSpeechRate(job.rate)

        val loc = if (tree == null) "Music/EbookMp3" else (tree.lastPathSegment ?: "thư mục đã chọn")
        lastLoc = loc
        var doneChars = 0
        var files = 0
        var out: OutFile? = null
        var lame: AndroidLame? = null
        var openName = ""
        try {
            val groups = valid.chunked(maxOf(1, if (job.group <= 0) valid.size else job.group))
            loop@ for ((gi, g) in groups.withIndex()) {
                val a = g.first() + 1
                val z = g.last() + 1
                val name = if (a == z) "%s_Chuong_%03d.mp3".format(book, a)
                else "%s_Chuong_%03d-%03d.mp3".format(book, a, z)
                out = openOut(name, tree)
                openName = name
                lame = null
                for (idx in g) {
                    val cs = chunks[idx]
                    for ((k, c) in cs.withIndex()) {
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
                log("Xong $name")
            }
        } finally {
            val o = out
            if (o != null) {
                finishFile(lame, o)
                files++
                addDone(applicationContext, DoneFile(job.name, openName, loc, true))
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

    // Thứ tự đọc thật của sách theo mục "spine" trong file .opf
    private fun spineOrder(files: Map<String, ByteArray>): List<String>? {
        try {
            val cont = files["META-INF/container.xml"]?.let { String(it) } ?: return null
            val opfPath = Regex("full-path=\"([^\"]+)\"").find(cont)?.groupValues?.get(1) ?: return null
            val opf = files[opfPath]?.let { String(it) } ?: return null
            val dir = opfPath.substringBeforeLast('/', "")
            val items = HashMap<String, String>()
            val skip = HashSet<String>()
            for (m in Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)) {
                val tag = m.value
                val id = Regex("\\bid=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: continue
                val href = Regex("\\bhref=\"([^\"]*)\"").find(tag)?.groupValues?.get(1) ?: continue
                val clean = Uri.decode(href.substringBefore('#'))
                val full = File(if (dir.isEmpty()) clean else "$dir/$clean").normalize().path
                items[id] = full
                if (Regex("properties=\"[^\"]*\\bnav\\b").containsMatchIn(tag)) skip.add(id)
            }
            val order = mutableListOf<String>()
            for (m in Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)) {
                val id = Regex("idref=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1) ?: continue
                if (id in skip) continue
                val path = items[id] ?: continue
                if (files.containsKey(path) && !order.contains(path)) order.add(path)
            }
            return if (order.isEmpty()) null else order
        } catch (e: Exception) {
            return null
        }
    }

    private fun epub(b: ByteArray): List<Pair<String, String>> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name
                if (!e.isDirectory && (isHtml(n) || n.lowercase().endsWith(".opf") || n.endsWith("container.xml")))
                    files[n] = z.readBytes()
                e = z.nextEntry
            }
        }
        val ordered = spineOrder(files)
            ?: files.keys.filter { isHtml(it) }.sortedWith { x, y -> naturalCompare(x, y) }
        val result = mutableListOf<Pair<String, String>>()
        for (name in ordered) {
            val data = files[name] ?: continue
            val t = Html.fromHtml(String(data), Html.FROM_HTML_MODE_LEGACY)
                .toString().replace("\uFFFC", "")
            if (t.trim().length > 200) result.add(name to t)
        }
        return result
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
