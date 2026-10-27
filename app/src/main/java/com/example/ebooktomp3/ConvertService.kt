package com.example.ebooktomp3

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
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

class ConvertService : Service() {
    companion object {
        @Volatile var onUpdate: ((String, Int) -> Unit)? = null
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        val uri = Uri.parse(i.getStringExtra("uri") ?: return START_NOT_STICKY)
        val lang = i.getStringExtra("lang") ?: "vi"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("cv", "Chuyển đổi", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 1, notif("Đang chuẩn bị..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Thread {
            try { convert(uri, lang) } catch (e: Exception) { report("Lỗi: ${e.message}", 0) }
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    private fun notif(t: String) = Notification.Builder(this, "cv")
        .setContentTitle(t).setSmallIcon(android.R.drawable.stat_sys_download).build()

    private fun report(msg: String, p: Int) {
        getSystemService(NotificationManager::class.java).notify(1, notif(msg))
        onUpdate?.invoke(msg, p)
    }

    private fun convert(uri: Uri, lang: String) {
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val isZip = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val chapters = if (isZip) epub(bytes) else listOf("1" to String(bytes))
        val chunks = chapters.map { chunk(it.second) }
        val total = chunks.sumOf { it.size }
        if (total == 0) { report("Không đọc được nội dung sách", 0); return }

        val tts = initTts(lang)
        if (tts == null) { report("Không khởi tạo được TTS hoặc máy chưa có giọng '$lang'", 0); return }

        var done = 0
        chapters.indices.forEach { idx ->
            val cs = chunks[idx]
            if (cs.isEmpty()) return@forEach
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "Sach_Chuong_%03d.mp3".format(idx + 1))
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/EbookMp3")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val item = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(item)!!.use { os ->
                var lame: AndroidLame? = null
                for (c in cs) {
                    val wav = File(cacheDir, "t.wav"); wav.delete()
                    if (synth(tts, c, wav)) lame = encode(wav, lame, os)
                    done++
                    report("Chương ${idx + 1}/${chapters.size}", done * 100 / total)
                }
                lame?.let {
                    val b = ByteArray(7200)
                    val n = it.flush(b)
                    if (n > 0) os.write(b, 0, n)
                    it.close()
                }
            }
            values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(item, values, null, null)
        }
        tts.shutdown()
        report("Xong! File nằm trong Music/EbookMp3", 100)
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
