package com.example.ebooktomp3

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

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
        @Volatile var cancel = false
        @Volatile var running = false
    }

    private val logLines = mutableListOf<String>()
    private var startAt = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("cv", "Chuyển đổi", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notif("Đang chuẩn bị...", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val i = intent
        val uriStr = i?.getStringExtra("uri")
        if (i == null || uriStr == null || running) { stopSelf(); return START_NOT_STICKY }
        val uri = Uri.parse(uriStr)
        val lang = i.getStringExtra("lang") ?: "vi"
        val tree = i.getStringExtra("tree")?.let { Uri.parse(it) }
        val group = i.getIntExtra("group", 1)
        val rate = i.getFloatExtra("rate", 1.0f)
        val book = (i.getStringExtra("book") ?: "Sach")
            .replace(Regex("[^\\p{L}\\p{N}_ -]"), "").trim().ifEmpty { "Sach" }.replace(' ', '_')

        running = true
        cancel = false
        startAt = SystemClock.elapsedRealtime()
        logLines.clear()
        Thread {
            try {
                convert(uri, lang, tree, group, book, rate)
            } catch (e: Exception) {
                fail("Lỗi: ${e.message}")
            }
            running = false
            stopSelf()
        }.start()
        return START_NOT_STICKY
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
        val p = Progress(
            running = !done && !error, percent = pct, title = title, detail = detail,
            startAt = startAt, etaAt = etaAt, endAt = if (done || error) now else 0,
            log = logLines.joinToString("\n"), done = done, error = error
        )
        last = p
        listener?.invoke(p)
        val nm = getSystemService(NotificationManager::class.java)
        if (done || error) nm.notify(2, notif(title, pct, false, detail))
        else nm.notify(1, notif(title, pct, true, detail))
    }

    private fun fail(msg: String) {
        log(msg)
        emit("Có lỗi", msg, last?.percent ?: 0, error = true)
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

    private fun convert(uri: Uri, lang: String, tree: Uri?, group: Int, book: String, rate: Float) {
        emit("Đang đọc sách...", "", 0)
        log("Đọc file sách...")
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val isZip = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val chapters = if (isZip) epub(bytes) else listOf("1" to String(bytes))
        val chunks = chapters.map { chunk(it.second) }
        val totalChars = chunks.sumOf { c -> c.sumOf { it.length } }
        if (totalChars == 0) { fail("Không đọc được nội dung sách"); return }
        val nCh = chapters.size
        log("Tìm thấy $nCh chương, $totalChars ký tự")

        emit("Khởi tạo giọng đọc...", "Ngôn ngữ: $lang", 0)
        val tts = initTts(lang)
        if (tts == null) {
            fail("Máy chưa có giọng '$lang'. Hãy cài Google Text-to-speech và tải giọng này.")
            return
        }

        tts.setSpeechRate(rate)
        log("Tốc độ đọc: %.1fx".format(rate))
        val loc = if (tree == null) "Music/EbookMp3" else (tree.lastPathSegment ?: "thư mục đã chọn")
        var doneChars = 0
        var files = 0
        var out: OutFile? = null
        var lame: AndroidLame? = null
        try {
            val valid = chapters.indices.filter { chunks[it].isNotEmpty() }
            val groups = valid.chunked(maxOf(1, if (group <= 0) valid.size else group))
            loop@ for ((gi, g) in groups.withIndex()) {
                val a = g.first() + 1
                val z = g.last() + 1
                val name = if (a == z) "%s_Chuong_%03d.mp3".format(book, a)
                else "%s_Chuong_%03d-%03d.mp3".format(book, a, z)
                out = openOut(name, tree)
                lame = null
                for (idx in g) {
                    val cs = chunks[idx]
                    for ((k, c) in cs.withIndex()) {
                        if (cancel) break@loop
                        val now = SystemClock.elapsedRealtime()
                        val eta = if (doneChars > 0) now + (now - startAt) * (totalChars - doneChars) / doneChars else -1L
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
                log("Xong $name")
            }
        } finally {
            val o = out
            if (o != null) { finishFile(lame, o); files++ }
            tts.shutdown()
        }

        val pct = (doneChars * 100L / totalChars).toInt()
        if (cancel) {
            log("Đã huỷ theo yêu cầu")
            emit("Đã huỷ", "Đã lưu $files file mp3 (một phần) tại: $loc", pct, done = true)
        } else {
            log("Hoàn tất")
            emit("Hoàn tất", "Đã lưu $files file mp3 tại: $loc", 100, done = true)
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

    private fun epub(b: ByteArray): List<Pair<String, String>> {
        val m = sortedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name.lowercase()
                if (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) {
                    val t = Html.fromHtml(String(z.readBytes()), Html.FROM_HTML_MODE_LEGACY)
                        .toString().replace("\uFFFC", "")
                    if (t.trim().length > 200) m[e.name] = t
                }
                e = z.nextEntry
            }
        }
        return m.toList()
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
