package com.example.ebooktomp3

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private val BG = Color.parseColor("#F4F6FB")
    private val ACC = Color.parseColor("#5B5BD6")
    private val TXT = Color.parseColor("#1F2937")
    private val MUT = Color.parseColor("#6B7280")
    private val OK = Color.parseColor("#16A34A")
    private val BAD = Color.parseColor("#DC2626")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private var uri: Uri? = null
    private var tree: Uri? = null
    private var book = "Sach"
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tvFile: TextView
    private lateinit var tvOut: TextView
    private lateinit var etLang: EditText
    private lateinit var etGroup: EditText
    private lateinit var seek: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var tvPercent: TextView
    private lateinit var bar: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvLog: TextView
    private lateinit var startBtn: Button
    private lateinit var cancelBtn: Button

    private val tick = object : Runnable {
        override fun run() {
            ConvertService.last?.let { if (it.running) render(it) }
            ui.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun label(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = rounded(Color.WHITE, 18)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        c.addView(label(title, 16f, TXT, true))
        return c
    }

    private fun btn(t: String, filled: Boolean, color: Int): Button = Button(this).apply {
        text = t
        setAllCaps(false)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (filled) Color.WHITE else color)
        stateListAnimator = null
        val bg = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (filled) setColor(color) else { setColor(Color.WHITE); setStroke(dp(1), color) }
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22000000")), bg, null)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(50)).apply { topMargin = dp(10) }
    }

    private fun fmt(ms: Long): String {
        val s = maxOf(0L, ms / 1000)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(28))
            val g = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#4338CA"), Color.parseColor("#7C3AED"))
            )
            g.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat())
            background = g
        }
        header.addView(label("Ebook → MP3", 28f, Color.WHITE, true))
        header.addView(label("Biến sách thành audiobook ngay trên điện thoại", 14f, Color.parseColor("#E0E7FF")))
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        root.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))

        // 1. Chọn sách
        val c1 = card("1. Chọn sách")
        val pick = btn("Chọn file EPUB / TXT", true, ACC)
        tvFile = label("Chưa chọn file", 14f, MUT).apply { setPadding(0, dp(10), 0, 0) }
        c1.addView(pick); c1.addView(tvFile)
        body.addView(c1)

        // 2. Tuỳ chọn đầu ra
        val c2 = card("2. Tuỳ chọn đầu ra")
        c2.addView(label("Ngôn ngữ đọc (vi, en, fr...)", 13f, MUT).apply { setPadding(0, dp(10), 0, dp(4)) })
        etLang = EditText(this).apply {
            setText("vi"); textSize = 15f; setTextColor(TXT)
            setSingleLine(true)
            background = rounded(Color.parseColor("#F3F4F6"), 10)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        c2.addView(etLang, LinearLayout.LayoutParams(MATCH, WRAP))
        c2.addView(label("Gộp mỗi bao nhiêu chương thành 1 file mp3", 13f, MUT).apply { setPadding(0, dp(14), 0, dp(4)) })
        etGroup = EditText(this).apply {
            setText("1"); textSize = 15f; setTextColor(TXT)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            background = rounded(Color.parseColor("#F3F4F6"), 10)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        c2.addView(etGroup, LinearLayout.LayoutParams(MATCH, WRAP))
        c2.addView(label("1 = mỗi chương một file • 0 = gộp cả sách. Tên file ghi rõ khoảng chương, ví dụ Sach_Chuong_001-005.mp3", 12f, MUT).apply { setPadding(0, dp(4), 0, 0) })
        c2.addView(label("Tốc độ đọc", 13f, MUT).apply { setPadding(0, dp(14), 0, dp(2)) })
        tvSpeed = label("1.0x (bình thường)", 14f, TXT)
        seek = SeekBar(this).apply {
            max = 15
            progress = 5
            progressTintList = ColorStateList.valueOf(ACC)
            thumbTintList = ColorStateList.valueOf(ACC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val r = 0.5f + p * 0.1f
                    tvSpeed.text = "%.1fx".format(r) + if (p == 5) " (bình thường)" else ""
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        c2.addView(tvSpeed)
        c2.addView(seek, LinearLayout.LayoutParams(MATCH, WRAP))
        c2.addView(label("Nơi lưu file", 13f, MUT).apply { setPadding(0, dp(14), 0, dp(2)) })
        tvOut = label("Music/EbookMp3 (mặc định)", 14f, TXT)
        c2.addView(tvOut)
        val pickTree = btn("Chọn thư mục khác", false, ACC)
        val resetTree = btn("Dùng thư mục mặc định", false, MUT)
        c2.addView(pickTree); c2.addView(resetTree)
        body.addView(c2)

        // 3. Tiến trình
        val c3 = card("3. Tiến trình")
        tvPercent = label("0%", 40f, ACC, true).apply { setPadding(0, dp(6), 0, 0) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ACC)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#E5E7EB"))
        }
        tvTitle = label("Sẵn sàng", 16f, TXT, true).apply { setPadding(0, dp(10), 0, 0) }
        tvDetail = label("", 14f, MUT)
        tvTime = label("", 13f, MUT).apply { setPadding(0, dp(4), 0, 0) }
        tvLog = label("", 12f, MUT).apply {
            typeface = Typeface.MONOSPACE
            background = rounded(Color.parseColor("#F9FAFB"), 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        c3.addView(tvPercent)
        c3.addView(bar, LinearLayout.LayoutParams(MATCH, dp(10)))
        c3.addView(tvTitle); c3.addView(tvDetail); c3.addView(tvTime)
        c3.addView(tvLog, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        body.addView(c3)

        startBtn = btn("Bắt đầu chuyển thành MP3", true, ACC)
        cancelBtn = btn("Huỷ", false, BAD).apply { visibility = View.GONE }
        body.addView(startBtn); body.addView(cancelBtn)

        pick.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, 10)
        }
        pickTree.setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 11) }
        resetTree.setOnClickListener { tree = null; tvOut.text = "Music/EbookMp3 (mặc định)" }
        startBtn.setOnClickListener {
            val u = uri
            if (u == null) {
                Toast.makeText(this, "Hãy chọn file sách trước", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val i = Intent(this, ConvertService::class.java)
                .putExtra("uri", u.toString())
                .putExtra("lang", etLang.text.toString().trim().ifEmpty { "vi" })
                .putExtra("group", etGroup.text.toString().trim().toIntOrNull() ?: 1)
                .putExtra("book", book)
                .putExtra("rate", 0.5f + seek.progress * 0.1f)
            tree?.let { i.putExtra("tree", it.toString()) }
            render(Progress(running = true, title = "Đang khởi động...", detail = "", startAt = SystemClock.elapsedRealtime()))
            startForegroundService(i)
        }
        cancelBtn.setOnClickListener {
            ConvertService.cancel = true
            tvTitle.text = "Đang huỷ..."
        }
        return scroll
    }

    private fun render(p: Progress) {
        tvPercent.text = "${p.percent}%"
        bar.progress = p.percent
        tvTitle.text = p.title
        tvTitle.setTextColor(if (p.error) BAD else if (p.done) OK else TXT)
        tvDetail.text = p.detail
        tvLog.text = p.log
        tvLog.visibility = if (p.log.isEmpty()) View.GONE else View.VISIBLE
        val now = SystemClock.elapsedRealtime()
        if (p.startAt > 0) {
            val el = (if (p.running) now else p.endAt) - p.startAt
            var t = "Đã chạy ${fmt(el)}"
            if (p.running && p.etaAt > 0) t += " • Còn khoảng ${fmt(p.etaAt - now)}"
            tvTime.text = t
        } else tvTime.text = ""
        startBtn.visibility = if (p.running) View.GONE else View.VISIBLE
        cancelBtn.visibility = if (p.running) View.VISIBLE else View.GONE
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        actionBar?.hide()
        window.statusBarColor = Color.parseColor("#4338CA")
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        ConvertService.listener = { p -> runOnUiThread { render(p) } }
        render(ConvertService.last ?: Progress())
        ui.postDelayed(tick, 1000)
    }

    override fun onPause() {
        super.onPause()
        ConvertService.listener = null
        ui.removeCallbacks(tick)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        val u = data?.data ?: return
        if (req == 10) {
            uri = u
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val name = contentResolver.query(u, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            } ?: "sach"
            book = name.substringBeforeLast('.')
            tvFile.text = name
        } else if (req == 11) {
            try {
                contentResolver.takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            tree = u
            tvOut.text = (u.lastPathSegment ?: u.toString()).replace("primary:", "Bộ nhớ trong/")
        }
    }
}
