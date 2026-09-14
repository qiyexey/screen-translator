package com.hunter.screentranslator.overlay

import android.content.Context
import android.view.MotionEvent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import androidx.core.widget.TextViewCompat
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.util.LiveOverlayMode

/**
 * 实时翻译的**原位**译文叠层（v1.15.0）。
 *
 * 窗口精确盖在用户框选的翻译区域上，把译文压在日本原文上面——就是 v1.14.0
 * 拍照翻译"译文盖住原文"那套视觉，只是这里跟随实时取帧持续更新。
 *
 * ## 自捕获回环（这个类存在的主要理由之一）
 *
 * 叠层窗口**也会被 MediaProjection 截进去**。如果放着不管，会变成这样：
 * 截到原文 → 翻译 → 叠层盖上 → 下一帧截到的是**自己的译文** → 判定"画面变了"
 * → 再翻译译文 → 叠层换成另一段中文 → 再触发……无限自我翻译，且疯狂烧额度。
 *
 * 系统没有"本窗口不参与截屏"的开关（FLAG_SECURE 是反过来的：它会把该区域
 * 在截图里涂黑——而那块恰好就是我们要截的选区，等于自废武功）。
 *
 * 所以走 [setCaptureHidden]：取帧前把整个窗口的 alpha 置 0，等合成器翻过
 * 一两帧后再抓，抓完恢复。窗口 alpha 是合成器层面的属性，2~3 帧（约 50ms）
 * 就生效，用户看不到闪烁（低于闪烁融合阈值的"消失一帧"基本无感），
 * 但抓到的帧一定是**干净的游戏画面**。代价只有每次取帧多等 50ms。
 */
class LiveOverlayView(private val ctx: Context) : LinearLayout(ctx) {

    private val tvStatus: TextView
    private val tvText: TextView

    private var hiddenForCapture = false

    /**
     * 叠层当前是否盖着内容。
     * 用 volatile 字段而不是去读 tvText.text —— 取帧在主循环的子线程上跑，
     * 从子线程读 TextView 的文本属于跨线程访问 UI 状态，虽然能跑但不该依赖。
     */
    @Volatile private var contentShown = false

    /** 拖动模式（v1.15.9）：临时允许触摸并跟随手指移动，用于自由摆放译文框 */
    @Volatile private var dragMode = false
    private var dragWm: WindowManager? = null
    private var onDragEnd: ((Int, Int, Int, Int) -> Unit)? = null
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragRawX = 0f
    private var dragRawY = 0f
    private var startW = 0
    private var startH = 0

    /** 0=没在拖 1=移动 2=缩放 */
    private var dragKind = 0
    private var activeCorner = -1

    val wmParams: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        gravity = Gravity.TOP or Gravity.START
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        x = 0
        y = 0
    }

    init {
        orientation = VERTICAL
        // 只处理"拖动模式"下的手势；平时窗口本身是 NOT_TOUCHABLE，收不到事件。
        setOnTouchListener { _, e ->
            if (!dragMode) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = wmParams.x
                    dragStartY = wmParams.y
                    dragRawX = e.rawX
                    dragRawY = e.rawY
                    startW = wmParams.width
                    startH = wmParams.height
                    // 靠近角 → 缩放；否则整块移动（v1.15.28）
                    activeCorner = hitCorner(e.x, e.y)
                    dragKind = if (activeCorner >= 0) 2 else 1
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 用 rawX/rawY 算增量：它们是屏幕绝对坐标，不受视图自身位移影响
                    // （用 e.x/e.y 会随着窗口一起移动，产生"越拖越快"的抖动）
                    val dx = (e.rawX - dragRawX).toInt()
                    val dy = (e.rawY - dragRawY).toInt()
                    if (dragKind == 2) {
                        // 缩放：按住哪个角就动哪个角，**对角固定不动**（拖起来才符合直觉）
                        when (activeCorner) {
                            0 -> { // 左上
                                wmParams.x = dragStartX + dx
                                wmParams.y = dragStartY + dy
                                wmParams.width = (startW - dx).coerceAtLeast(MIN_SIDE)
                                wmParams.height = (startH - dy).coerceAtLeast(MIN_SIDE)
                            }
                            1 -> { // 右上
                                wmParams.y = dragStartY + dy
                                wmParams.width = (startW + dx).coerceAtLeast(MIN_SIDE)
                                wmParams.height = (startH - dy).coerceAtLeast(MIN_SIDE)
                            }
                            2 -> { // 左下
                                wmParams.x = dragStartX + dx
                                wmParams.width = (startW - dx).coerceAtLeast(MIN_SIDE)
                                wmParams.height = (startH + dy).coerceAtLeast(MIN_SIDE)
                            }
                            else -> { // 右下
                                wmParams.width = (startW + dx).coerceAtLeast(MIN_SIDE)
                                wmParams.height = (startH + dy).coerceAtLeast(MIN_SIDE)
                            }
                        }
                    } else {
                        wmParams.x = dragStartX + dx
                        wmParams.y = dragStartY + dy
                    }
                    updateWindow(dragWm ?: context.getSystemService(Context.WINDOW_SERVICE)
                        as WindowManager, "拖动叠层")
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onDragEnd?.invoke(wmParams.x, wmParams.y, wmParams.width, wmParams.height)
                    dragKind = 0
                    activeCorner = -1
                    true
                }
                else -> false
            }
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0xE6101012.toInt())
            // 细描边（半透明绿）：叠层到底盖在哪，一眼可见。
            // 没有它，深色游戏画面上一块深色叠层几乎看不出来，
            // 一旦坐标错位就会长时间被误判成"模型识别不到文字"。
            setStroke(dp(1), 0x667CE38B.toInt())
        }
        elevation = 16f
        setPadding(dp(10), dp(7), dp(10), dp(7))

        tvStatus = TextView(ctx).apply {
            text = "实时翻译 · 待机"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(0xFF9E9E9E.toInt())
            maxLines = 1
        }
        tvText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(2).toFloat(), 1f)
            // 字号自适应：选区大小千差万别（GBA 的文本框可能只有 40dp 高，
            // 也可能占半屏），写死字号不是溢出就是浪费空间。
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 10, 22, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
        addView(tvStatus, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            tvText,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
        )

        applyTouchable()
        applyStyle()
    }

    /** 角把手的判定半径（比把手本身大，手指才好按） */
    private val handleRadius by lazy { dp(30) }

    /**
     * 触摸点是否落在某个角附近。
     * 用**视图内坐标**（e.x/e.y）判定：角就在视图四角，与窗口在屏幕上的位置无关。
     * 返回 0=左上 1=右上 2=左下 3=右下，-1=没命中。
     */
    private fun hitCorner(x: Float, y: Float): Int {
        if (width <= 0 || height <= 0) return -1
        val r = handleRadius.toFloat()
        val corners = arrayOf(0f to 0f, width.toFloat() to 0f, 0f to height.toFloat(),
            width.toFloat() to height.toFloat())
        corners.forEachIndexed { i, (cx, cy) ->
            if (kotlin.math.hypot((x - cx).toDouble(), (y - cy).toDouble()) <= r) return i
        }
        return -1
    }

    fun attachToWindow(wm: WindowManager) {
        dragWm = wm
        wm.addView(this, wmParams)
    }

    /**
     * 进入 / 退出拖动模式（v1.15.9）。
     *
     * 叠层默认是 `NOT_TOUCHABLE`（否则会把游戏那一块操作区变哑）。要让它能拖，
     * 就必须临时变成可触摸 —— 所以做成**临时模式**：拖完（或超时）立刻切回，
     * 不会长期吃掉游戏操作。
     */
    fun setDraggable(enabled: Boolean, wm: WindowManager, onEnd: ((Int, Int, Int, Int) -> Unit)? = null) {
        dragMode = enabled
        dragWm = wm
        onDragEnd = if (enabled) onEnd else null
        applyTouchable()
        updateWindow(wm, if (enabled) "进入拖动模式" else "退出拖动模式")
    }

    fun detachFromWindow(wm: WindowManager) {
        runCatching { wm.removeView(this) }
    }

    /** 把窗口摆到选区上（选区变化时调用） */
    fun applyGeometry(roi: Rect, wm: WindowManager) {
        wmParams.x = roi.left
        wmParams.y = roi.top
        wmParams.width = roi.width()
        wmParams.height = roi.height()
        updateWindow(wm, "设置叠层位置")
    }

    /**
     * 提交窗口参数（独立成方法，便于统一处理线程与错误）。
     *
     * v1.15.4 之前这里直接 `runCatching { wm.updateViewLayout(...) }`，把异常**吞掉了**。
     * 而 updateViewLayout 会触碰视图层级，**必须在 UI 线程调用**；从主循环的
     * 工作线程调用可能抛 CalledFromWrongThreadException —— 一旦如此，"位置没更新"
     * 或"叠层没隐藏"就会**静默失效**，日志里连一行痕迹都没有。现在改成：
     * 统一 post 到 UI 线程，失败写日志。
     */
    private fun updateWindow(wm: WindowManager, what: String) {
        val job = Runnable {
            runCatching { wm.updateViewLayout(this, wmParams) }
                .onFailure { Log.w(TAG, "$what 失败: $it") }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) job.run() else post(job)
    }

    /**
     * 触摸策略刷新。
     * 可触摸的条件：**拖动模式中**（临时）或用户显式打开了"叠层可触摸"（长期）。
     * 默认两者都不成立，所以平时完全不吃触摸。
     */
    fun applyTouchable() {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val touchable = dragMode || App.prefs.liveOverlayTouchable
        wmParams.flags = if (touchable) base else base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    /**
     * 处理中：**刻意什么都不做**（v1.15.21）。
     *
     * 原来这里会把状态行写成"识别中…"，于是主循环每轮写的诊断行
     * （Δ / 阈值 / 译次 / 新几行共几行）**只存在一瞬间就被冲掉**，
     * 用户在界面上根本看不到 —— 而这行恰恰是排查"增量有没有生效"的唯一依据。
     * 现在状态行**由主循环独占**，这里只用于正文，不再抢状态行。
     * 正文也不清空：增量更新时保留上一段译文，避免整框闪一下。
     */
    fun showTranslating() {
        contentShown = true
    }

    /**
     * 出译文：写正文，同时把状态行恢复成**常规文案**。
     *
     * v1.16.1：这里必须回收状态行。上一版把主循环写状态行的那句（调试读数）删掉后，
     * 状态行就**没人负责复位**了 —— 拖动时的"已保存"这类一次性提示会一直挂在框上
     * （用户反馈"一直有…啥的"）。现在每出一段译文就复位成当前链路，提示自然被顶掉。
     */
    fun showResult(translated: String, fromCache: Boolean = false) {
        contentShown = true
        post {
            tvText.text = translated
            tvStatus.text = if (fromCache) "缓存命中 · 未发请求" else statusLabel()
        }
    }

    /** 当前链路的常规状态文案（读图模式 / 免费本机识别模式） */
    private fun statusLabel(): String =
        if (TranslationEngine.fromKey(App.prefs.engine).visionCapable) "实时翻译 · 读图模式"
        else "实时翻译 · 免费模式 · 本机识别"

    fun showMessage(status: String, text: String = "") {
        contentShown = true
        post {
            tvStatus.text = status
            tvText.text = text
        }
    }

    fun setStatus(status: String) {
        post { tvStatus.text = status }
    }

    /**
     * 取帧前把窗口藏起来（见类注释的自捕获回环）。
     * 通过**窗口** alpha 而不是 View alpha：前者由合成器直接处理，不受绘制时机影响。
     */
    fun setCaptureHidden(hidden: Boolean, wm: WindowManager) {
        if (hiddenForCapture == hidden) return
        hiddenForCapture = hidden
        wmParams.alpha = if (hidden) 0f else 1f
        updateWindow(wm, if (hidden) "隐藏叠层" else "恢复叠层")
    }

    /**
     * 当前叠层是否"盖着东西"。
     * 没盖东西时取帧前不必隐藏——省掉那 50ms，也让启动瞬间更快出第一屏译文。
     */
    fun hasContent(): Boolean = contentShown

    /**
     * 底色透明度。
     *
     * 实时翻译的叠层**必须足够不透明**，有两个硬理由：
     *  1. 它是"原位覆盖"——半透明就会同时看到原文和译文，两边都读不清；
     *  2. 取帧时要靠"藏起来 vs 露出来"两帧的差异来判断隐藏是否生效；
     *     半透明会让这个差异小到无法判断（v1.15.4 就是因为这个才误报）。
     * 所以这里不复用面板透明度偏好，直接压到接近不透明。
     */
    /**
     * 面板样式：背景不透明度 + 字号缩放（v1.15.19 改为用户可调）。
     *
     * 覆盖模式下强制拉回接近不透明，理由见 [App] 里 liveOverlayAlpha 的注释 ——
     * 半透明会让自捕获校验失去可判别性。
     */
    fun applyStyle() {
        val cover = App.prefs.liveOverlayMode == LiveOverlayMode.COVER
        val want = if (cover) App.prefs.liveOverlayAlpha.coerceAtLeast(0.92f)
        else App.prefs.liveOverlayAlpha
        (background as? GradientDrawable)?.let { g ->
            val base = 0xE6101012.toInt()
            val a = ((base ushr 24) * want).toInt().coerceIn(0x28, 0xFF)
            g.setColor((a shl 24) or (base and 0x00FFFFFF))
        }
        val s = App.prefs.liveTextScale
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            tvText,
            (10 * s).toInt().coerceAtLeast(8),
            (22 * s).toInt().coerceAtMost(40),
            1,
            TypedValue.COMPLEX_UNIT_SP
        )
    }

    companion object {
        private const val TAG = "ScreenTranslator"

        /** 缩放时的最小边长，避免被拖成一条缝后再也抓不住 */
        private const val MIN_SIDE = 120
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()
}
