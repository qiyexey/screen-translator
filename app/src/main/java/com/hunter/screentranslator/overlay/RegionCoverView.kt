package com.hunter.screentranslator.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 框选翻译的「覆盖原文」呈现（v1.29.2）：全屏透明窗口，把每行译文盖在原文所在位置。
 *
 * 视觉与图片翻译的原位覆盖一致（不透明贴片、宽高不小于原文框、明暗随原文底色），
 * 但这里是在**别的 App 上面**盖，所以多两件事：
 *  - 坐标直接用屏幕绝对坐标（与 [RegionSelectView] 同一套窗口参数，FLAG_LAYOUT_IN_SCREEN 铺满）；
 *  - 窗口会拦截触摸：**点一下关闭**、**按住看原文**（松手恢复），不会误点到下面的 App。
 */
class RegionCoverView(
    ctx: Context,
    private val onDismiss: () -> Unit
) : FrameLayout(ctx) {

    /** 一行：屏幕坐标 + 原文底色是否偏浅（决定贴片用浅底还是深底） */
    data class Line(val box: Rect, val lightBackground: Boolean)

    val wmParams: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        gravity = Gravity.TOP or Gravity.START
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        // 关掉窗口动画：出现/消失都要干脆，否则关闭后立刻再框选会截到半透明的旧贴片
        windowAnimations = 0
    }

    private var lines: List<Line> = emptyList()
    private val chips = HashMap<Int, TextView>()
    private var peeking = false

    private val pendingStroke = Paint().apply {
        color = Color.parseColor("#CC1E88E5")
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.5f)
        isAntiAlias = true
    }
    private val drawRect = RectF()
    private val pendingFill = Paint().apply {
        color = Color.parseColor("#331E88E5")
        style = Paint.Style.FILL
    }

    private val tvStatus = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#CC1C1C1E"))
        }
    }

    private val hideStatus = Runnable { tvStatus.visibility = GONE }
    private val startPeek = Runnable { setPeek(true) }

    init {
        setWillNotDraw(false)
        addView(tvStatus, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(48)
        })
    }

    fun attachToWindow(wm: WindowManager) {
        wm.addView(this, wmParams)
    }

    fun detachFromWindow(wm: WindowManager) {
        removeCallbacks(hideStatus)
        removeCallbacks(startPeek)
        runCatching { wm.removeView(this) }
    }

    /** 设定要盖的行（先画出待翻译的框），旧贴片全部清掉 */
    fun setLines(newLines: List<Line>) {
        chips.values.forEach { removeView(it) }
        chips.clear()
        lines = newLines
        invalidate()
    }

    /** 把第 [index] 行的译文盖上去 */
    fun showChip(index: Int, translated: String) {
        val line = lines.getOrNull(index) ?: return
        val box = line.box
        chips.remove(index)?.let { removeView(it) }

        val density = resources.displayMetrics.density
        // 一行原文框高 ≈ 字号 × 1.4，反推字号让译文尽量占满原文框（与图片翻译同一套取值）
        val sizeSp = (box.height() / density * 0.72f).coerceIn(9f, 18f)
        val light = line.lightBackground
        val bg = if (light) Color.rgb(250, 250, 250) else Color.rgb(16, 16, 16)
        val fg = if (light) Color.rgb(16, 16, 16) else Color.rgb(248, 248, 248)
        val screenW = resources.displayMetrics.widthPixels

        val chip = TextView(context).apply {
            text = translated
            textSize = sizeSp
            setTextColor(fg)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(1), dp(4), dp(1))
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(bg)
                setStroke(dp(1), if (light) Color.argb(45, 0, 0, 0) else Color.argb(60, 255, 255, 255))
            }
            // 宽高都不小于原文框 —— 原文被真正盖住，而不是"贴在旁边"
            minWidth = box.width().coerceAtLeast(dp(20))
            minHeight = box.height().coerceAtLeast(dp(14))
            maxWidth = (screenW - box.left - dp(8)).coerceAtLeast(dp(72))
            visibility = if (peeking) INVISIBLE else VISIBLE
        }
        addView(chip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = box.left.coerceIn(0, maxOf(0, screenW - dp(72)))
            topMargin = box.top.coerceAtLeast(0)
        })
        chips[index] = chip
        // 状态条始终在最上层，不被贴片挡住
        tvStatus.bringToFront()
        invalidate()
    }

    /** 顶部状态条；[autoHideMs] > 0 时到点自动收起 */
    fun setStatus(text: String, autoHideMs: Long = 0) {
        removeCallbacks(hideStatus)
        tvStatus.text = text
        tvStatus.visibility = VISIBLE
        tvStatus.bringToFront()
        if (autoHideMs > 0) postDelayed(hideStatus, autoHideMs)
    }

    private fun setPeek(active: Boolean) {
        if (peeking == active) return
        peeking = active
        chips.values.forEach { it.visibility = if (active) INVISIBLE else VISIBLE }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (peeking) return
        // 还没盖上译文的行画一个细框，让人知道"这几行正在翻"
        val r = dpF(3f)
        lines.forEachIndexed { i, line ->
            if (chips.containsKey(i)) return@forEachIndexed
            drawRect.set(line.box)
            canvas.drawRoundRect(drawRect, r, r, pendingFill)
            canvas.drawRoundRect(drawRect, r, r, pendingStroke)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                postDelayed(startPeek, ViewConfiguration.getLongPressTimeout().toLong())
            MotionEvent.ACTION_UP -> {
                removeCallbacks(startPeek)
                if (peeking) {
                    setPeek(false)  // 按住看完原文，松手恢复译文
                } else {
                    performClick()
                    onDismiss()     // 轻点：关闭覆盖层
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(startPeek)
                setPeek(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun dpF(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )
}
