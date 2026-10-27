package com.example.ebooktomp3

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {
    private var uri: Uri? = null
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val pick = Button(this).apply { text = "Chọn eBook (EPUB / TXT)" }
        val lang = EditText(this).apply { setText("vi"); hint = "Mã ngôn ngữ (vi, en, ...)" }
        status = TextView(this).apply { text = "Chưa chọn file" }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val start = Button(this).apply { text = "Bắt đầu chuyển thành MP3" }
        listOf(pick, lang, start, bar, status).forEach { root.addView(it) }
        setContentView(root)

        pick.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, 10)
        }
        start.setOnClickListener {
            val u = uri ?: return@setOnClickListener
            startForegroundService(Intent(this, ConvertService::class.java)
                .putExtra("uri", u.toString())
                .putExtra("lang", lang.text.toString().trim().ifEmpty { "vi" }))
        }
        ConvertService.onUpdate = { msg, p -> runOnUiThread { status.text = msg; bar.progress = p } }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 10 && res == RESULT_OK) {
            uri = data?.data
            try {
                contentResolver.takePersistableUriPermission(uri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            status.text = "Đã chọn: $uri"
        }
    }
}
