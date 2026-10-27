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
import android.view.Gravity
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

    private val pending = mutableListOf<Pair<Uri, String>>()
    private var tree: Uri? = null
    private var queueSig = ""
    private var listSig = ""
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tvFile: TextView
    private lateinit var tvOut: TextView
    private lateinit var etLang: EditText
    private lateinit var etGroup: EditText
    private lateinit var etStart: EditText
    private lateinit var pageConvert: LinearLayout
    private lateinit var pageList: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var tabConvert: TextView
    private lateinit var tabList: TextView
    private lateinit var seek: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var queueCard: LinearLayout
    private lateinit var queueBox: LinearLayout
    private lateinit var tvPercent: TextView
    private lateinit var bar: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvLog: TextView
    private lateinit var startBtn: Button
    private lateinit var skipBtn: Button
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

    private fun input(initial: String, number: Boolean) = EditText(this).apply {
        setText(initial); textSize = 15f; setTextColor(TXT)
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        background = rounded(Color.parseColor("#F3F4F6"), 10)
        setPadding(dp(14), dp(10), dp(14), dp(10))
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

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.parseColor("#E5E7EB"), 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(16), dp(16), dp(16), 0) }
        }
        fun tab(t: String) = TextView(this).apply {
            text = t
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        tabConvert = tab("Chuyển đổi")
        tabList = tab("Danh sách")
        tabBar.addView(tabConvert)
        tabBar.addView(tabList)
        root.addView(tabBar)
        tabConvert.setOnClickListener { showTab(0) }
        tabList.setOnClickListener { showTab(1) }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        root.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        pageConvert = body

        pageList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            visibility = View.GONE
        }
        val listCard = card("Danh sách chuyển đổi")
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(listBox)
        pageList.addView(listCard)
        val clearHist = btn("Xoá lịch sử đã xong", false, MUT)
        clearHist.setOnClickListener {
            ConvertService.clearHistory(this)
            listSig = ""
            renderList()
        }
        pageList.addView(clearHist)
        root.addView(pageList, LinearLayout.LayoutParams(MATCH, WRAP))

        // 1. Chọn sách (nhiều file)
        val c1 = card("1. Chọn sách")
        val pick = btn("Chọn file EPUB / TXT (chọn được nhiều file)", true, ACC)
        tvFile = label("Chưa chọn file", 14f, MUT).apply { setPadding(0, dp(10), 0, 0) }
        val clearPick = btn("Xoá danh sách đã chọn", false, MUT)
        c1.addView(pick); c1.addView(tvFile); c1.addView(clearPick)
        body.addView(c1)

        // 2. Tuỳ chọn
        val c2 = card("2. Tuỳ chọn đầu ra")
        c2.addView(label("Ngôn ngữ đọc (vi, en, fr...)", 13f, MUT).apply { setPadding(0, dp(10), 0, dp(4)) })
        etLang = input("vi", false)
        c2.addView(etLang, LinearLayout.LayoutParams(MATCH, WRAP))
        c2.addView(label("Gộp mỗi bao nhiêu chương thành 1 file mp3", 13f, MUT).apply { setPadding(0, dp(14), 0, dp(4)) })
        etGroup = input("1", true)
        c2.addView(etGroup, LinearLayout.LayoutParams(MATCH, WRAP))
        c2.addView(label("1 = mỗi chương một file • 0 = gộp cả sách. Tên file ghi rõ khoảng chương, ví dụ Sach_Chuong_001-005.mp3", 12f, MUT).apply { setPadding(0, dp(4), 0, 0) })
        c2.addView(label("Bắt đầu đọc từ chương số", 13f, MUT).apply { setPadding(0, dp(14), 0, dp(4)) })
        etStart = input("1", true)
        c2.addView(etStart, LinearLayout.LayoutParams(MATCH, WRAP))
        c2.addView(label("Số chương theo thứ tự trong sách, giống số trong tên file (Chuong_010...). 1 = đọc từ đầu. File TXT chỉ có chương 1.", 12f, MUT).apply { setPadding(0, dp(4), 0, 0) })
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
        c2.addView(label("Tuỳ chọn được áp dụng cho các sách bạn thêm vào hàng đợi ở lần bấm này.", 12f, MUT).apply { setPadding(0, dp(8), 0, 0) })
        body.addView(c2)

        // 3. Hàng đợi
        queueCard = card("3. Hàng đợi")
        queueBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        queueCard.addView(queueBox)
        queueCard.addView(label("Chạm vào sách đang chờ để bỏ khỏi hàng đợi.", 12f, MUT).apply { setPadding(0, dp(8), 0, 0) })
        queueCard.visibility = View.GONE
        body.addView(queueCard)

        // 4. Tiến trình
        val c3 = card("4. Tiến trình")
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
        skipBtn = btn("Bỏ qua sách đang đọc", false, ACC).apply { visibility = View.GONE }
        cancelBtn = btn("Dừng tất cả", false, BAD).apply { visibility = View.GONE }
        body.addView(startBtn); body.addView(skipBtn); body.addView(cancelBtn)

        pick.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, 10)
        }
        clearPick.setOnClickListener { pending.clear(); updatePending() }
        pickTree.setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 11) }
        resetTree.setOnClickListener { tree = null; tvOut.text = "Music/EbookMp3 (mặc định)" }

        startBtn.setOnClickListener {
            if (pending.isEmpty()) {
                Toast.makeText(this, "Hãy chọn ít nhất một file sách", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val lang = etLang.text.toString().trim().ifEmpty { "vi" }
            val group = etGroup.text.toString().trim().toIntOrNull() ?: 1
            val startCh = (etStart.text.toString().trim().toIntOrNull() ?: 1).coerceAtLeast(1)
            val rate = 0.5f + seek.progress * 0.1f
            val t = tree?.toString()
            val list = pending.map { (u, n) ->
                BookJob(u.toString(), n, n.substringBeforeLast('.'), lang, group, startCh, rate, t)
            }
            pending.clear()
            updatePending()
            ConvertService.add(list)
            if (!ConvertService.running)
                render(Progress(running = true, title = "Đang khởi động...", detail = "", startAt = SystemClock.elapsedRealtime()))
            else renderQueue()
            startForegroundService(Intent(this, ConvertService::class.java))
        }
        skipBtn.setOnClickListener {
            ConvertService.cancel = true
            tvTitle.text = "Đang bỏ qua sách này..."
        }
        cancelBtn.setOnClickListener {
            ConvertService.stopAll = true
            tvTitle.text = "Đang dừng..."
        }
        return scroll
    }

    private fun updatePending() {
        tvFile.text = if (pending.isEmpty()) "Chưa chọn file"
        else "${pending.size} file đã chọn:\n" + pending.joinToString("\n") { "• ${it.second}" }
    }

    private fun renderQueue() {
        val list = ConvertService.jobs.toList()
        val sig = list.joinToString("|") { "${it.name}:${it.status}:${it.percent}:${it.note}" }
        if (sig == queueSig) return
        queueSig = sig
        queueCard.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        queueBox.removeAllViews()
        for (j in list) {
            val (icon, extra) = when (j.status) {
                JobState.RUNNING -> "▶" to " • ${j.percent}%"
                JobState.DONE -> "✅" to ""
                JobState.ERROR -> "⚠️" to " • lỗi: ${j.note}"
                JobState.CANCELED -> "⛔" to " • đã huỷ"
                else -> "⏳" to " • đang chờ"
            }
            val color = when (j.status) {
                JobState.RUNNING -> ACC
                JobState.DONE -> OK
                JobState.ERROR -> BAD
                else -> MUT
            }
            val row = label("$icon ${j.name}$extra", 14f, color, j.status == JobState.RUNNING).apply {
                setPadding(0, dp(6), 0, dp(6))
                if (j.status == JobState.WAITING) setOnClickListener { removeWaiting(j) }
            }
            queueBox.addView(row)
        }
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
            if (p.running && p.etaAt > 0)
                t += (if (ConvertService.jobs.size > 1) " • Sách này còn khoảng " else " • Còn khoảng ") + fmt(p.etaAt - now)
            tvTime.text = t
        } else tvTime.text = ""
        startBtn.text = if (p.running) "Thêm vào hàng đợi" else "Bắt đầu chuyển thành MP3"
        skipBtn.visibility = if (p.running) View.VISIBLE else View.GONE
        cancelBtn.visibility = if (p.running) View.VISIBLE else View.GONE
        renderQueue()
        renderList()
    }

    private fun removeWaiting(j: BookJob) {
        synchronized(ConvertService.lock) {
            if (j.status == JobState.WAITING) ConvertService.jobs.remove(j)
        }
        queueSig = ""
        listSig = ""
        renderQueue()
        renderList()
    }

    private fun styleTab(t: TextView, active: Boolean) {
        t.setTextColor(if (active) Color.WHITE else MUT)
        t.background = if (active) rounded(ACC, 11) else null
    }

    private fun showTab(i: Int) {
        pageConvert.visibility = if (i == 0) View.VISIBLE else View.GONE
        pageList.visibility = if (i == 1) View.VISIBLE else View.GONE
        styleTab(tabConvert, i == 0)
        styleTab(tabList, i == 1)
        if (i == 1) { listSig = ""; renderList() }
    }

    private fun renderList() {
        val jobs = ConvertService.jobs.toList()
        val hist = ConvertService.history.toList()
        val sig = jobs.joinToString("|") { "${it.name}:${it.status}:${it.percent}" } +
            "#" + hist.size + (hist.lastOrNull()?.file ?: "")
        if (sig == listSig) return
        listSig = sig
        tabList.text = "Danh sách (${hist.size})"
        listBox.removeAllViews()

        fun title(t: String) = label(t, 15f, TXT, true).apply { setPadding(0, dp(12), 0, dp(4)) }
        fun line(t: String, color: Int, bold: Boolean = false, indent: Int = 0) =
            label(t, 14f, color, bold).apply { setPadding(dp(indent), dp(4), 0, dp(4)) }

        val running = jobs.filter { it.status == JobState.RUNNING }
        val waiting = jobs.filter { it.status == JobState.WAITING }

        listBox.addView(title("Đang đọc"))
        if (running.isEmpty()) listBox.addView(line("Không có", MUT))
        else for (j in running) listBox.addView(line("▶ ${j.name} • ${j.percent}%", ACC, true))

        listBox.addView(title("Đang chờ (${waiting.size})"))
        if (waiting.isEmpty()) listBox.addView(line("Không có sách nào đang chờ", MUT))
        else {
            for (j in waiting) listBox.addView(line("⏳ ${j.name}", TXT).apply { setOnClickListener { removeWaiting(j) } })
            listBox.addView(label("Chạm vào sách đang chờ để bỏ khỏi hàng đợi.", 12f, MUT))
        }

        listBox.addView(title("Đã xong (${hist.size} file mp3)"))
        if (hist.isEmpty()) listBox.addView(line("Chưa có file mp3 nào hoàn thành", MUT))
        else {
            var lastBook: String? = null
            for (d in hist.asReversed()) {
                if (d.book != lastBook) {
                    listBox.addView(line("📖 ${d.book}", TXT, true).apply { setPadding(0, dp(10), 0, 0) })
                    listBox.addView(label("Lưu tại: ${d.loc}", 12f, MUT))
                    lastBook = d.book
                }
                val txt = (if (d.partial) "⚠️ " else "✅ ") + d.file + (if (d.partial) " (một phần)" else "")
                listBox.addView(line(txt, if (d.partial) BAD else OK, false, 12))
            }
        }
    }

    private fun nameOf(u: Uri): String =
        contentResolver.query(u, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: "sach"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        actionBar?.hide()
        window.statusBarColor = Color.parseColor("#4338CA")
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContentView(buildUi())
        ConvertService.loadHistory(this)
        showTab(0)
    }

    override fun onResume() {
        super.onResume()
        ConvertService.listener = { p -> runOnUiThread { render(p) } }
        queueSig = ""
        listSig = ""
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
        if (res != RESULT_OK || data == null) return
        if (req == 10) {
            val list = mutableListOf<Uri>()
            data.clipData?.let { cd ->
                for (k in 0 until cd.itemCount) list.add(cd.getItemAt(k).uri)
            }
            if (list.isEmpty()) data.data?.let { list.add(it) }
            for (u in list) {
                try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                if (pending.none { it.first == u }) pending.add(u to nameOf(u))
            }
            updatePending()
        } else if (req == 11) {
            val u = data.data ?: return
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
