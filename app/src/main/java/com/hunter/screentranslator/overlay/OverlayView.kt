package com.hunter.screentranslator.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.api.DictEntry
import com.hunter.screentranslator.api.FallbackTranslator
import com.hunter.screentranslator.api.LANG_DISPLAY
import com.hunter.screentranslator.api.SOURCE_AUTO
import com.hunter.screentranslator.ui.EngineSettingsActivity
import com.hunter.screentranslator.util.Speaker
import com.hunter.screentranslator.util.TtsContent
import kotlin.math.abs

/**
 * 悬浮翻译面板。
 *
 * v1.29.0 重做为「精简卡片」：
 * - 去掉常驻的「🌐 屏幕翻译」标题行，**译文放在最上面**、字号最大，是视觉主体
 * - 原文默认收起，细分隔线下展开；点卡片或右下角箭头切换
 * - 底部一行：语言方向 + 复制 / 朗读 / 展开（线性图标，替换掉各 ROM 长得不一样的 emoji）
 * - 「正在翻译」用顶部细进度条表达，报错用红字 +「去设置」，提示类（⚠️ / 📍）用标题样式
 *
 * 「译文显示不完」的修复：原来译文 maxLines = 10、原文 maxLines = 6，超出部分被直接截掉，
 * 而且没有省略号，看起来就是"翻译少了一截"。现在不限行数，内容区最高占屏幕 45%，
 * 再长就在面板内部滚动；面板停留时长也按译文长度放宽，长译文不会读到一半就消失。
 */
class OverlayView(private val ctx: Context) : LinearLayout(ctx) {

    private val progress: ProgressBar
    private val scroll: CappedScrollView
    private val tvTranslated: TextView
    private val divider: View
    private val tvSource: TextView
    private val footer: LinearLayout
    private val tvLang: TextView
    private val btnFix: TextView
    private val btnCopy: ImageView
    private val btnSpeak: ImageView
    private val btnExpand: ImageView

    /** v1.9.3：朗读内容切换行（原文/译文/两者），长按朗读按钮时显示 */
    private var contentPickerRow: LinearLayout? = null

    /** 原文是否展开（默认收起：译文才是主角） */
    private var sourceExpanded = false
    private var state = State.RESULT

    /** v1.29.0：当前显示的是词典词条（不为 null 时） */
    private var dictEntry: DictEntry? = null

    /** 最近一次译文，供朗读与复制使用 */
    private var lastTranslated: String = ""
    private var lastSource: String = ""

    private var wm: WindowManager? = null

    // 拖动相关
    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var moved = false

    private val layoutParams: WindowManager.LayoutParams =
        WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            format = PixelFormat.RGBA_8888
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            // v1.29.0：记住上次拖到的位置；没记录过就用老默认值
            x = App.prefs.panelX.takeIf { it >= 0 } ?: 40
            y = App.prefs.panelY.takeIf { it >= 0 } ?: 200
        }

    init {
        orientation = VERTICAL
        // mutate：背景 drawable 可能与其他实例共享 ConstantState，独立出来才能单独改色
        background = (ContextCompat.getDrawable(ctx, R.drawable.overlay_bg) as GradientDrawable).mutate()
        elevation = 16f
        clipToOutline = true

        // 顶部细进度条：只在「正在翻译」时出现，取代原来占位的文字
        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = GONE
        }
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(3)))

        tvTranslated = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(COLOR_TEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        divider = View(ctx).apply {
            setBackgroundColor(COLOR_DIVIDER)
            visibility = GONE
        }
        tvSource = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(COLOR_TEXT_DIM)
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = GONE
        }
        val body = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(4))
            addView(tvTranslated, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(10); bottomMargin = dp(8)
            })
            addView(tvSource, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        // 内容区限高 + 内部滚动：长译文不再被截断
        scroll = CappedScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = OVER_SCROLL_NEVER
            addView(body)
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        tvLang = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(COLOR_TEXT_HINT)
            isSingleLine = true
        }
        btnFix = TextView(ctx).apply {
            text = "去设置"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(COLOR_TEXT)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = chipBackground(0x33FFFFFF)
            visibility = GONE
            setOnClickListener { openEngineSettings() }
        }
        btnCopy = makeIconButton(R.drawable.ic_copy, "复制译文")
        btnSpeak = makeIconButton(R.drawable.ic_volume, "朗读")
        btnExpand = makeIconButton(R.drawable.ic_expand, "展开原文")

        footer = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(2), dp(8), dp(6))
            addView(tvLang, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(btnFix, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(4)
            })
            addView(btnCopy)
            addView(btnSpeak)
            addView(btnExpand)
        }
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        applyPanelStyle()
        refreshSpeakLabel()

        btnCopy.setOnClickListener { copyTranslated() }
        btnSpeak.setOnClickListener {
            if (lastTranslated.isBlank() && lastSource.isBlank()) return@setOnClickListener
            Speaker.stop()
            // 词条模式下朗读按钮读的是单词本身（听发音），不是那一串释义
            if (state == State.DICT) {
                val lang = Speaker.resolveSourceLang(App.prefs.ttsSourceLang, lastSource)
                Speaker.speak(ctx, lastSource, lang) { ok, err -> if (!ok && err != null) toastShort(err) }
                return@setOnClickListener
            }
            // v1.9.3：按「朗读内容」设置决定读原文还是译文（见 Speaker.speakContent）
            Speaker.speakContent(ctx, lastSource, lastTranslated) { ok, err ->
                if (!ok && err != null) toastShort(err)
            }
        }
        // 长按朗读按钮 → 就地切换朗读内容（原文 / 译文 / 两者），无需进设置页
        btnSpeak.setOnLongClickListener {
            showContentPicker()
            true
        }
        btnExpand.setOnClickListener { toggleSource() }

        // 卡片本体和底栏都能拖；内容区可滚动时由 ScrollView 自己处理，滚到头才交还拖动
        val drag = TouchListener()
        setOnTouchListener(drag)
        footer.setOnTouchListener(drag)
        body.setOnTouchListener(drag)
    }

    /** 底栏图标按钮：32dp 可点区域，统一浅灰着色 */
    private fun makeIconButton(iconRes: Int, desc: String): ImageView = ImageView(ctx).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(COLOR_ICON)
        contentDescription = desc
        val pad = dp(7)
        setPadding(pad, pad, pad, pad)
        layoutParams = LayoutParams(dp(34), dp(34))
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) })
        isClickable = true
    }

    private fun chipBackground(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(color)
    }

    private fun toastShort(msg: String) {
        runCatching {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyTranslated() {
        if (lastTranslated.isBlank()) return
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // 带专用 label：「复制即翻译」据此跳过，不把自己复制的译文再翻一遍
            cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, lastTranslated))
            toastShort("已复制译文")
        }
    }

    private fun openEngineSettings() {
        runCatching {
            ctx.startActivity(
                Intent(ctx, EngineSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            visibility = GONE
        }
    }

    // ==================== v1.9.3：朗读内容切换 ====================

    /** 朗读按钮的说明随设置变化（无障碍读屏能听到当前读的是原文还是译文） */
    private fun refreshSpeakLabel() {
        btnSpeak.contentDescription = when (App.prefs.ttsContent) {
            TtsContent.SOURCE -> "朗读原文（长按切换）"
            TtsContent.BOTH -> "朗读原文和译文（长按切换）"
            else -> "朗读译文（长按切换）"
        }
    }

    /**
     * 就地切换朗读内容。
     *
     * 悬浮窗里不能用 AlertDialog（需要 Activity 主题，且会抢焦点导致面板被收起），
     * 因此在面板内部插入一个横向选项行，选完自动隐藏。
     */
    private fun showContentPicker() {
        val row = contentPickerRow ?: buildContentPickerRow().also {
            contentPickerRow = it
            // 首次构建时高亮当前项（必须在 contentPickerRow 赋值之后调，
            // 否则 refreshContentPicker 里拿不到 row）
            refreshContentPicker()
        }
        row.visibility = if (row.visibility == VISIBLE) GONE else VISIBLE
    }

    private fun buildContentPickerRow(): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            visibility = GONE
            setPadding(dp(16), dp(2), dp(16), dp(8))
        }
        TtsContent.OPTIONS.forEach { (value, label) ->
            val chip = TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener {
                    App.prefs.ttsContent = value
                    refreshSpeakLabel()
                    refreshContentPicker()
                    row.visibility = GONE
                    toastShort("朗读内容：$label")
                }
            }
            row.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(6)
            })
        }
        // 插在底栏上方，紧挨着朗读按钮
        addView(row, indexOfChild(footer), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return row
    }

    /** 高亮当前选中的那一项 */
    private fun refreshContentPicker() {
        val row = contentPickerRow ?: return
        val current = App.prefs.ttsContent
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as? TextView ?: continue
            val selected = TtsContent.OPTIONS.getOrNull(i)?.first == current
            chip.background = chipBackground(if (selected) (accent() and 0x00FFFFFF) or 0x66000000 else 0x33FFFFFF)
            chip.setTextColor(if (selected) COLOR_TEXT else COLOR_ICON)
        }
    }

    /**
     * 强调色跟随悬浮球颜色（v1.29.0），去掉透明度后与白色 35% 混合提亮，
     * 保证深色面板上的进度条与选中态看得清。
     */
    private fun accent(): Int {
        val c = App.prefs.ballColor
        fun mix(ch: Int) = (ch + (0xFF - ch) * 35 / 100).coerceIn(0, 0xFF)
        val r = mix((c shr 16) and 0xFF)
        val g = mix((c shr 8) and 0xFF)
        val b = mix(c and 0xFF)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun attachToWindow(wm: WindowManager) {
        this.wm = wm
        layoutParams.width = panelWidth()
        clampToScreen()
        // 先测量，再添加
        measure(
            MeasureSpec.makeMeasureSpec(layoutParams.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        wm.addView(this, layoutParams)
    }

    /**
     * 应用面板样式（v1.5.1 背景透明度；v1.29.0 起强调色也在这里刷新）。
     * 只缩放背景色 alpha，文字颜色不动——面板变通透时译文依然清晰。
     * 基准色 #E0181818（88% 不透明深灰），panelAlpha 0.4~1.0 等比缩放。
     */
    fun applyPanelStyle() {
        val alpha = (PANEL_BASE_ALPHA * App.prefs.panelAlpha).toInt().coerceIn(0x40, 0xE0)
        (background as? GradientDrawable)?.setColor((alpha shl 24) or 0x00181818)
        progress.indeterminateTintList = ColorStateList.valueOf(accent())
        refreshContentPicker()
    }

    fun detachFromWindow(wm: WindowManager) {
        removeCallbacks(autoHideRunnable)
        runCatching { wm.removeView(this) }
        this.wm = null
    }

    /**
     * 显示词典词条（v1.29.0 单词查词模式）。
     * [lastTranslated] 存纯文本形式，复制、历史都用它；朗读改成读单词本身。
     */
    fun showDictEntry(entry: DictEntry) {
        dictEntry = entry
        lastSource = entry.word
        lastTranslated = entry.toPlainText()
        state = State.DICT
        render()
        scroll.scrollTo(0, 0)
        showWithAutoHide()
    }

    fun updateContent(source: String, translated: String) {
        dictEntry = null
        lastSource = source
        lastTranslated = translated
        if (translated.isBlank() && source.isBlank()) {
            visibility = GONE
            return
        }
        state = when {
            translated.startsWith("正在翻译") -> State.LOADING
            translated.startsWith("翻译失败") -> State.ERROR
            source.startsWith("⚠️") || source.startsWith("📍") || source.startsWith("❌") -> State.NOTICE
            else -> State.RESULT
        }
        render()
        scroll.scrollTo(0, 0)
        showWithAutoHide()
        // 自动朗读（默认关）：只朗读真正的译文，跳过占位与报错文案
        // v1.9.3：改为走 speakContent，跟随「朗读内容」设置（原文/译文/两者）
        if (state == State.RESULT && App.prefs.ttsAutoSpeak && isSpeakable(translated)) {
            Speaker.stop()
            Speaker.speakContent(ctx, if (isSpeakable(source)) source else "", translated)
        }
    }

    /** 按当前状态摆放各元素 */
    private fun render() {
        val hasSource = lastSource.isNotBlank()
        progress.visibility = if (state == State.LOADING) VISIBLE else GONE
        btnFix.visibility = if (state == State.ERROR) VISIBLE else GONE
        val isResult = state == State.RESULT || state == State.DICT
        btnCopy.visibility = if (isResult) VISIBLE else GONE
        btnSpeak.visibility = if (isResult) VISIBLE else GONE
        btnExpand.visibility = if (state == State.RESULT && hasSource) VISIBLE else GONE
        tvLang.text = if (state == State.NOTICE) "" else langLabel()

        when (state) {
            State.LOADING -> {
                tvTranslated.text = "正在翻译…"
                tvTranslated.setTextColor(COLOR_TEXT_HINT)
                // 等待时露出原文，让人知道在翻哪一段
                showSource(hasSource)
            }
            State.ERROR -> {
                tvTranslated.text = lastTranslated
                tvTranslated.setTextColor(COLOR_ERROR)
                showSource(false)
            }
            State.NOTICE -> {
                // 提示类：source 是标题（去掉前缀 emoji），translated 是说明
                val title = lastSource.removePrefix("⚠️").removePrefix("📍").removePrefix("❌").trim()
                tvTranslated.text = SpannableStringBuilder().apply {
                    append(title)
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(COLOR_WARN), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (lastTranslated.isNotBlank()) {
                        val start = length
                        append("\n").append(lastTranslated)
                        setSpan(ForegroundColorSpan(COLOR_TEXT_DIM), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                tvTranslated.setTextColor(COLOR_TEXT)
                showSource(false)
            }
            State.DICT -> {
                tvTranslated.text = dictEntry?.let { formatDict(it) } ?: lastTranslated
                tvTranslated.setTextColor(COLOR_TEXT)
                showSource(false)
            }
            State.RESULT -> {
                tvTranslated.text = lastTranslated.ifBlank { "等待内容…" }
                tvTranslated.setTextColor(COLOR_TEXT)
                showSource(hasSource && sourceExpanded)
            }
        }
        btnExpand.rotation = if (sourceExpanded) 180f else 0f
        btnExpand.contentDescription = if (sourceExpanded) "收起原文" else "展开原文"
    }

    private fun showSource(show: Boolean) {
        tvSource.text = lastSource
        tvSource.visibility = if (show) VISIBLE else GONE
        divider.visibility = if (show) VISIBLE else GONE
    }

    private fun toggleSource() {
        if (state != State.RESULT || lastSource.isBlank()) return
        sourceExpanded = !sourceExpanded
        render()
    }

    /** 词条排版：单词加粗放大，音标灰色，词性用强调色，释义白字 */
    private fun formatDict(e: DictEntry): CharSequence = SpannableStringBuilder().apply {
        append(e.word)
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(android.text.style.RelativeSizeSpan(1.2f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (!e.phonetic.isNullOrBlank()) {
            val start = length
            append("  ").append(e.phonetic)
            setSpan(ForegroundColorSpan(COLOR_TEXT_DIM), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (sense in e.senses) {
            append('\n')
            if (sense.pos.isNotBlank()) {
                val start = length
                append(sense.pos).append(' ')
                setSpan(ForegroundColorSpan(accent()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.ITALIC), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            append(sense.meanings.joinToString("；"))
        }
    }

    /** 「English → 中文」；源语言自动识别时显示「自动 → 中文」 */
    private fun langLabel(): String {
        fun name(code: String) = LANG_DISPLAY[code] ?: code.uppercase()
        val src = App.prefs.sourceLang
        val from = if (src == SOURCE_AUTO || src.isBlank()) "自动" else name(src)
        val label = "$from → ${name(App.prefs.targetLang)}"
        // v1.29.0：这条译文是备用引擎给的，标出来，免得用户以为主引擎还好着
        val fb = if (state == State.RESULT) FallbackTranslator.recentFallbackName() else null
        if (state == State.DICT) return "$label · 词典"
        return if (fb != null) "$label · 备用：$fb" else label
    }

    /** 哪些内容值得自动朗读（过滤占位符、错误提示、空文本） */
    private fun isSpeakable(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("正在翻译")) return false
        if (t.startsWith("⚠️") || t.startsWith("❌") || t.startsWith("📍")) return false
        return true
    }

    /**
     * 显示面板并启动自动隐藏计时（静默设计：平时屏幕上只有悬浮球）。
     * v1.29.0：停留时长按译文长度放宽（25 秒起，最长 90 秒），长译文不会读到一半就消失；
     * 用户摸一下面板（滚动、点按）也会重新计时，见 [dispatchTouchEvent]。
     */
    fun showWithAutoHide() {
        visibility = VISIBLE
        restartAutoHide()
        // 宽高可能刚变（转屏 / 内容变长），确认面板仍在屏幕内
        post { refreshWindowBounds() }
    }

    private fun restartAutoHide() {
        removeCallbacks(autoHideRunnable)
        val extra = ((lastTranslated.length + if (sourceExpanded) lastSource.length else 0) - 100)
            .coerceAtLeast(0) * 100L
        postDelayed(autoHideRunnable, (AUTO_HIDE_MS + extra).coerceAtMost(AUTO_HIDE_MAX_MS))
    }

    /** 外部切换显隐（点悬浮球）：显示时同样带自动隐藏 */
    fun toggleVisible() {
        if (visibility == VISIBLE) {
            visibility = GONE
            removeCallbacks(autoHideRunnable)
        } else {
            showWithAutoHide()
        }
    }

    private val autoHideRunnable = Runnable {
        visibility = GONE
        Log.d("ScreenTranslator", "翻译面板无操作，自动隐藏")
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && visibility == VISIBLE) restartAutoHide()
        return super.dispatchTouchEvent(ev)
    }

    private fun screenW() = ctx.resources.displayMetrics.widthPixels
    private fun screenH() = ctx.resources.displayMetrics.heightPixels

    /** 宽 320dp，小屏 / 分屏时收窄到屏宽减边距 */
    private fun panelWidth() = minOf(dp(320), screenW() - dp(16))

    private fun clampToScreen() {
        val w = layoutParams.width.takeIf { it > 0 } ?: panelWidth()
        val h = height.takeIf { it > 0 } ?: dp(80)
        layoutParams.x = layoutParams.x.coerceIn(0, (screenW() - w).coerceAtLeast(0))
        layoutParams.y = layoutParams.y.coerceIn(0, (screenH() - h).coerceAtLeast(0))
    }

    private fun refreshWindowBounds() {
        val manager = wm ?: return
        if (!isAttachedToWindow) return
        val oldX = layoutParams.x
        val oldY = layoutParams.y
        val oldW = layoutParams.width
        layoutParams.width = panelWidth()
        clampToScreen()
        if (oldX != layoutParams.x || oldY != layoutParams.y || oldW != layoutParams.width) {
            runCatching { manager.updateViewLayout(this, layoutParams) }
        }
    }

    /**
     * 拖动 + 单击。挂在卡片本体、内容区和底栏上（按钮自己消费点击，不会走到这里）。
     * 内容区能滚动时 ScrollView 会截走竖向滑动，这里收到 CANCEL 不做任何事。
     */
    private inner class TouchListener : OnTouchListener {
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    if (moved) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        clampToScreen()
                        wm?.let { runCatching { it.updateViewLayout(this@OverlayView, layoutParams) } }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        App.prefs.panelX = layoutParams.x
                        App.prefs.panelY = layoutParams.y
                    } else {
                        v.performClick()
                        toggleSource()
                    }
                }
            }
            return true
        }
    }

    /** 最高占屏幕 45% 的 ScrollView：内容少时贴合内容，多了在内部滚动 */
    private inner class CappedScrollView(c: Context) : ScrollView(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = (screenH() * MAX_HEIGHT_RATIO).toInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        ctx.resources.displayMetrics
    ).toInt()

    private enum class State { LOADING, RESULT, ERROR, NOTICE, DICT }

    companion object {
        /** 面板显示后自动隐藏时长（基础值；长译文按字数加时，见 [restartAutoHide]） */
        private const val AUTO_HIDE_MS = 25_000L
        private const val AUTO_HIDE_MAX_MS = 90_000L

        /** 内容区最大高度占屏幕高度的比例 */
        private const val MAX_HEIGHT_RATIO = 0.45f

        /** 面板基准背景色 alpha（对应 overlay_bg.xml 的 #E0） */
        private const val PANEL_BASE_ALPHA = 0xE0

        /** 面板「复制」写入剪贴板时用的 label，「复制即翻译」见到它就跳过 */
        const val CLIP_LABEL = "ScreenTranslator.translated"

        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_DIM = 0xFFB4B4B4.toInt()
        private const val COLOR_TEXT_HINT = 0xFF8C8C8C.toInt()
        private const val COLOR_ICON = 0xFFD0D0D0.toInt()
        private const val COLOR_DIVIDER = 0x26FFFFFF
        private const val COLOR_ERROR = 0xFFFF8A80.toInt()
        private const val COLOR_WARN = 0xFFFFD180.toInt()
    }
}
