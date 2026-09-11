package com.hunter.screentranslator.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.hunter.screentranslator.App
import kotlin.math.abs

/**
 * 悬浮球视图：可拖动小球。
 * v1.1.1：去掉松手贴边——球停在哪就在哪（定位翻译的核心诉求）。
 * v1.5.0：样式个性化 + 双击进入框选翻译。
 * v1.6.0：长按打开输入翻译界面。
 *
 * 手势：拖动 → 定点翻译；单击 → 切换面板；双击 → 框选翻译；长按 → 输入翻译；
 * 三击 → 图片翻译（v1.8.0）。
 */
class OverlayBallView(
    ctx: Context,
    private val onDrop: (x: Int, y: Int) -> Unit,
    private val onBallClick: () -> Unit,
    private val onBallDoubleClick: () -> Unit,
    private val onBallLongPress: () -> Unit = {},
    /** v1.8.0 三击：打开图片翻译 */
    private val onBallTripleClick: () -> Unit = {}
) : FrameLayout(ctx) {

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var moved = false

    /** 双击检测：上次 tap 时间 + 待执行的单击任务 */
    private var lastTapTime = 0L
    /** v1.8.0：双击窗口内累积的点击次数，用于区分单击/双击/三击 */
    private var tapCount = 0
    private var pendingSingleTap: Runnable? = null

    /** 长按检测（v1.6.0） */
    private var longPressRunnable: Runnable? = null
    private var longPressFired = false

    /** 球心准星点：拖动时显示，标记取词位置 */
    private val crosshair: View

    /** 当前样式透明度（拖动/高亮都以它为基准恢复） */
    private val styleAlpha: Float get() = App.prefs.ballAlpha

    val wmParams: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        format = PixelFormat.RGBA_8888
        // FLAG_NOT_FOCUSABLE：不抢焦点；FLAG_LAYOUT_IN_SCREEN：坐标系覆盖全屏含状态栏，
        // 与无障碍节点 getBoundsInScreen() 的屏幕绝对坐标一致（否则 y 会偏移一个状态栏高度）
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        gravity = Gravity.TOP or Gravity.START
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        x = 0
        y = 400
    }

    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), 0xAAFFFFFF.toInt())
        }
        elevation = 12f

        // 球心准星：8dp 白点
        crosshair = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
            visibility = View.GONE
        }
        val dot = dp(8)
        addView(crosshair, LayoutParams(dot, dot, Gravity.CENTER))

        // 应用初始样式（尺寸/颜色/透明度）——此时未 attach，只改参数与背景
        applyStyle(keepCenter = false)

        setOnTouchListener(BallTouchListener())
    }

    fun attachToWindow(wm: WindowManager) {
        applyStyle(keepCenter = false)
        wm.addView(this, wmParams)
    }

    fun detachFromWindow(wm: WindowManager) {
        cancelGestures()
        runCatching { wm.removeView(this) }
    }

    /**
     * 应用 Prefs 中的悬浮球样式（颜色/大小/透明度）。
     * keepCenter=true 时保持球心位置不变（改尺寸时避免球"跳"一下）。
     */
    fun updateStyle() = applyStyle(keepCenter = true)

    /**
     * 把悬浮球左上角坐标钳制在屏幕内，返回是否发生了钳制。
     *
     * 修复：拖动时直接用 `rawX - width/2`，手指移到屏幕左/上边缘会得到负坐标，
     * 球被推出屏幕外，且下游以球心 (center()) 反查文字时会拿到负坐标。
     * getBoundsInScreen / Rect 在下游对负值并不总是安全。
     */
    private fun clampToScreen() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (sw, sh) = screenSize(wm)
        val w = if (width > 0) width else wmParams.width
        val h = if (height > 0) height else wmParams.height
        wmParams.x = wmParams.x.coerceIn(0, (sw - w).coerceAtLeast(0))
        wmParams.y = wmParams.y.coerceIn(0, (sh - h).coerceAtLeast(0))
    }

    /** 当前屏幕可用尺寸（px）。优先用 API 30+ 的 WindowMetrics。 */
    private fun screenSize(wm: WindowManager): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val d = wm.defaultDisplay
            @Suppress("DEPRECATION")
            val p = android.util.DisplayMetrics().also { d.getRealMetrics(it) }
            p.widthPixels to p.heightPixels
        }

    private fun applyStyle(keepCenter: Boolean) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val oldCx = wmParams.x + width / 2
        val oldCy = wmParams.y + height / 2

        val sizePx = dp(App.prefs.ballSizeDp)
        wmParams.width = sizePx
        wmParams.height = sizePx
        if (keepCenter && isAttachedToWindow) {
            // 尺寸变化时锚点是左上角，补回偏移让球心不动
            wmParams.x = oldCx - sizePx / 2
            wmParams.y = oldCy - sizePx / 2
        }
        clampToScreen()

        (background as? GradientDrawable)?.setColor(App.prefs.ballColor)
        alpha = styleAlpha

        if (isAttachedToWindow) {
            runCatching { wm.updateViewLayout(this, wmParams) }
        }
    }

    /** 球心当前屏幕坐标 */
    fun center(): Pair<Int, Int> = (wmParams.x + width / 2) to (wmParams.y + height / 2)

    @SuppressLint("ClickableViewAccessibility")
    private inner class BallTouchListener : OnTouchListener {
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = e.rawX
                    touchDownY = e.rawY
                    moved = false
                    longPressFired = false
                    // 轻微变实，提示"摸到了"
                    alpha = (styleAlpha + 1f) / 2f
                    crosshair.visibility = View.VISIBLE
                    // 长按检测（500ms 未移动未松手）
                    val lp = Runnable {
                        longPressFired = true
                        crosshair.visibility = View.GONE
                        alpha = styleAlpha
                        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        performLongClick()
                        onBallLongPress()
                    }
                    longPressRunnable = lp
                    postDelayed(lp, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchDownX
                    val dy = e.rawY - touchDownY
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    if (moved) {
                        // 拖动即取消长按与待执行的单击
                        cancelGestures()
                        // 拖动时更透明：让球下的文字透出来，方便瞄准
                        alpha = styleAlpha * 0.35f
                        // 球心跟随手指（clampToScreen 保证不越出屏幕边界）
                        wmParams.x = (e.rawX - width / 2).toInt()
                        wmParams.y = (e.rawY - height / 2).toInt()
                        clampToScreen()
                        runCatching { wm.updateViewLayout(v, wmParams) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { removeCallbacks(it) }
                    longPressRunnable = null
                    crosshair.visibility = View.GONE
                    if (longPressFired) {
                        // 长按已经触发过（打开了输入翻译），本次松手不再当 tap
                        longPressFired = false
                        alpha = styleAlpha
                        return true
                    }
                    if (!moved) {
                        val now = SystemClock.uptimeMillis()
                        // v1.8.0：加入三击。原实现只有单/双击二分，加三击最稳的做法是
                        // 统一记账"窗口内累积了几次 tap"，等窗口结束再判定 —— 这样不会出现
                        // "双击立刻触发、第三击无处安放"的冲突。
                        if (now - lastTapTime < DOUBLE_TAP_MS) {
                            tapCount += 1
                        } else {
                            tapCount = 1
                        }
                        lastTapTime = now

                        // 每次点击都重置待判定任务，窗口结束后按 tapCount 分派
                        pendingSingleTap?.let { removeCallbacks(it) }
                        val r = Runnable {
                            pendingSingleTap = null
                            lastTapTime = 0
                            alpha = styleAlpha
                            performClick()
                            when (tapCount) {
                                1 -> onBallClick()
                                2 -> onBallDoubleClick()
                                else -> onBallTripleClick()
                            }
                            tapCount = 0
                        }
                        pendingSingleTap = r
                        postDelayed(r, DOUBLE_TAP_MS)
                    } else {
                        // 定点翻译：用松手时的球心坐标；球留在原地，不贴边
                        val (cx, cy) = center()
                        onDrop(cx, cy)
                        // 轻微反馈：短暂高亮后恢复
                        alpha = 1f
                        postDelayed({ alpha = styleAlpha }, 400)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    crosshair.visibility = View.GONE
                    alpha = styleAlpha
                    cancelGestures()
                }
            }
            return true
        }
    }

    /** 取消所有挂起的手势任务（长按 / 待触发的单击） */
    private fun cancelGestures() {
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
        pendingSingleTap?.let { removeCallbacks(it) }
        pendingSingleTap = null
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()

    companion object {
        /** 双击判定窗口：两次 tap 间隔小于该值算双击 */
        private const val DOUBLE_TAP_MS = 300L

        /** 长按判定时长 */
        private const val LONG_PRESS_MS = 500L
    }
}
