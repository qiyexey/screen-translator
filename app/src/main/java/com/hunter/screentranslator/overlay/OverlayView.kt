package com.hunter.screentranslator.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.util.Speaker
import com.hunter.screentranslator.util.TtsContent
import kotlin.math.abs

/**
 * 悬浮窗视图。
 * 功能：
 * - 拖动移动位置
 * - 单击展开/折叠
 * - 双击关闭（隐藏）
 * - 显示原文 + 译文
 */
class OverlayView(private val ctx: Context) : LinearLayout(ctx) {

    private val tvTitle: TextView
    private val tvSource: TextView
    private val tvTranslated: TextView
    /** v1.8.0：朗读按钮 + 原文/译文切换按钮 */
    private val btnSpeak: TextView
    private val btnToggleSource: TextView

    /** v1.9.3：朗读内容切换行（原文/译文/两者），长按朗读按钮时显示 */
    private var contentPickerRow: LinearLayout? = null

    private var expanded = true

    /** 最近一次译文，供朗读与"看原文"使用 */
    private var lastTranslated: String = ""
    private var lastSource: String = ""

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
            x = 40
            y = 200
        }

    init {
        orientation = VERTICAL
        // mutate：背景 drawable 可能与其他实例共享 ConstantState，独立出来才能单独改色
        background = (ContextCompat.getDrawable(ctx, R.drawable.overlay_bg) as GradientDrawable).mutate()
        elevation = 16f
        val padH = dp(14)
        val padV = dp(10)
        setPadding(padH, padV, padH, padV)
        applyPanelStyle()

        tvTitle = TextView(ctx).apply {
            text = "🌐 屏幕翻译"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFFCCCCCC.toInt())
        }
        tvSource = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFB0B0B0.toInt())
            maxLines = 6
        }
        tvTranslated = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 10
        }

        val titleParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        val sourceParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        }
        val transParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        }

        // v1.8.0：标题行放两个小按钮（朗读 / 看原文），横排
        // v1.9.3：朗读按钮长按可切换朗读内容（原文/译文/两者），标签随设置更新
        btnSpeak = makeActionChip("🔊 朗读")
        btnToggleSource = makeActionChip("👁 原文")
        refreshSpeakChipLabel()

        val titleRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tvTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(btnSpeak, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(btnToggleSource, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(6)
            })
        }

        addView(titleRow, titleParams)
        addView(tvSource, sourceParams)
        addView(tvTranslated, transParams)

        btnSpeak.setOnClickListener {
            if (lastTranslated.isBlank() && lastSource.isBlank()) return@setOnClickListener
            Speaker.stop()
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

        btnToggleSource.setOnClickListener {
            tvSource.visibility =
                if (tvSource.visibility == VISIBLE && tvSource.text.isNotBlank()) GONE else VISIBLE
        }

        setOnTouchListener(TouchListener())
    }

    /** 顶部小按钮统一样式 */
    private fun makeActionChip(label: String): TextView = TextView(ctx).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(0xFFE0E0E0.toInt())
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0x33FFFFFF)
        }
    }

    private fun toastShort(msg: String) {
        runCatching {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== v1.9.3：朗读内容切换 ====================

    /** 朗读按钮标签随设置变化，让人一眼看出当前读的是原文还是译文 */
    private fun refreshSpeakChipLabel() {
        val label = when (App.prefs.ttsContent) {
            TtsContent.SOURCE -> "🔊 原文"
            TtsContent.BOTH -> "🔊 原文+译文"
            else -> "🔊 译文"
        }
        btnSpeak.text = label
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
            setPadding(0, dp(6), 0, 0)
        }
        TtsContent.OPTIONS.forEach { (value, label) ->
            val chip = makeActionChip(label).apply {
                setOnClickListener {
                    App.prefs.ttsContent = value
                    refreshSpeakChipLabel()
                    refreshContentPicker()
                    row.visibility = GONE
                    toastShort("朗读内容：$label")
                }
            }
            row.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(6)
            })
        }
        // 插入到标题行之后（index 1），这样出现在原文/译文上方
        addView(row, 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return row
    }

    /** 高亮当前选中的那一项 */
    private fun refreshContentPicker() {
        val row = contentPickerRow ?: return
        val current = App.prefs.ttsContent
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as? TextView ?: continue
            val selected = TtsContent.OPTIONS.getOrNull(i)?.first == current
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (selected) 0x664CAF50 else 0x33FFFFFF)
            }
            chip.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFE0E0E0.toInt())
        }
    }

    fun attachToWindow(wm: WindowManager) {
        // 先测量，再添加
        measure(
            MeasureSpec.makeMeasureSpec(dp(280), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        layoutParams.width = dp(280)
        wm.addView(this, layoutParams)
    }

    /**
     * 应用面板背景透明度（v1.5.1）。
     * 只缩放背景色 alpha，文字颜色不动——面板变通透时译文依然清晰。
     * 基准色 #E0181818（88% 不透明深灰），panelAlpha 0.4~1.0 等比缩放。
     */
    fun applyPanelStyle() {
        val alpha = (PANEL_BASE_ALPHA * App.prefs.panelAlpha).toInt().coerceIn(0x40, 0xE0)
        (background as? GradientDrawable)?.setColor((alpha shl 24) or 0x00181818)
    }

    fun detachFromWindow(wm: WindowManager) {
        runCatching { wm.removeView(this) }
    }

    fun updateContent(source: String, translated: String) {
        lastSource = source
        lastTranslated = translated
        tvSource.text = if (source.isBlank()) "" else "原：$source"
        tvSource.visibility = if (source.isBlank()) GONE else VISIBLE
        tvTranslated.text = translated.ifBlank { "等待内容…" }
        // v1.8.0：译文有效时才启用朗读按钮，避免对着"正在翻译…"朗读
        btnSpeak.alpha = if (translated.isBlank() || translated.startsWith("正在翻译")) 0.4f else 1f
        if (translated.isBlank() && source.isBlank()) {
            visibility = GONE
        } else {
            showWithAutoHide()
            // 自动朗读（默认关）：只朗读真正的译文，跳过占位与报错文案
            // v1.9.3：改为走 speakContent，跟随「朗读内容」设置（原文/译文/两者）
            if (App.prefs.ttsAutoSpeak && isSpeakable(translated)) {
                Speaker.stop()
                Speaker.speakContent(
                    ctx,
                    if (isSpeakable(source)) source else "",
                    translated
                )
            }
        }
        requestLayout()
    }

    /** 哪些内容值得自动朗读（过滤占位符、错误提示、空文本） */
    private fun isSpeakable(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("正在翻译")) return false
        if (t.startsWith("⚠️") || t.startsWith("❌") || t.startsWith("📍")) return false
        return true
    }

    /** 显示面板并启动 25 秒自动隐藏计时（静默设计：平时屏幕上只有悬浮球） */
    fun showWithAutoHide() {
        visibility = VISIBLE
        removeCallbacks(autoHideRunnable)
        postDelayed(autoHideRunnable, AUTO_HIDE_MS)
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
        Log.d("ScreenTranslator", "翻译面板 25 秒无更新，自动隐藏")
    }

    private fun toggle() {
        expanded = !expanded
        tvSource.visibility = if (expanded && tvSource.text.isNotBlank()) VISIBLE else GONE
        tvTranslated.visibility = if (expanded) VISIBLE else GONE
    }

    private inner class TouchListener : OnTouchListener {
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
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
                    if (abs(dx) > 5 || abs(dy) > 5) moved = true
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                        .updateViewLayout(v, layoutParams)
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        performClick()
                        toggle()
                    }
                }
            }
            return true
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        ctx.resources.displayMetrics
    ).toInt()

    companion object {
        /** 面板显示后自动隐藏时长 */
        private const val AUTO_HIDE_MS = 25_000L

        /** 面板基准背景色 alpha（对应 overlay_bg.xml 的 #E0） */
        private const val PANEL_BASE_ALPHA = 0xE0
    }
}
