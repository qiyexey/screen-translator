package com.hunter.screentranslator.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hunter.screentranslator.R
import com.hunter.screentranslator.util.Roi
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 翻译区域框选器（v1.15.1）。
 *
 * 替代 v1.15.0 用 [RegionSelectView] 的做法。它有三个当时没想到、实测才暴露的问题：
 *
 * 1. **在活的游戏画面上拖框** —— 画面一直在动，手一抖就对不上要框的位置。
 *    这里先把当前帧**冻住**当背景，在静止图上画，瞄准难度直接降一个数量级。
 * 2. **画完不能改** —— 只能重来。这里四角有把手，可以拖着微调。
 * 3. **画完不知道会截到哪一块** —— 这是最要命的：选区坐标一旦算错（旋转、
 *    notch、窗口比屏幕高……），用户和排查者都看不出来，只会得到"识别不到文字"。
 *    所以底部放一个**真实裁剪预览**：它调用的就是服务取帧时用的同一个
 *    [Roi.toFrameCoords]，**预览里是什么，送去翻译的就是什么**。
 *    预览与框不符 = 坐标换算错了；预览对但模型说没字 = 模型读不了这种字。
 *    一屏之内把两种截然不同的故障分开。
 *
 * 另外这里会把"视图尺寸 vs 真实屏幕尺寸"记进日志：两者不等就说明全屏悬浮窗
 * 被系统撑大了，那正是坐标错位的根源。
 */
class RoiPickerView(
    ctx: Context,
    private val frame: Bitmap?,
    private val onConfirm: (Rect) -> Unit,
    private val onCancel: () -> Unit
) : FrameLayout(ctx) {

    private val maskView: MaskView
    private val preview: ImageView
    private val previewBox: LinearLayout
    private val tvSize: TextView

    val wmParams: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        format = PixelFormat.TRANSLUCENT
        // 刻意不加 FLAG_LAYOUT_NO_LIMITS：它允许窗口超出屏幕，原点会跑到屏幕外，
        // 于是"视图内坐标 == 屏幕坐标"这个前提不成立 —— 正是叠层位置偏移的经典成因。
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        gravity = Gravity.TOP or Gravity.START
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        x = 0
        y = 0
    }

    init {
        // 冻结帧铺满全屏：它本身就是整屏的缩略，所以"图上的位置"就是"屏幕上的位置"
        if (frame != null) {
            addView(
                ImageView(ctx).apply {
                    setImageBitmap(frame)
                    scaleType = ImageView.ScaleType.FIT_XY
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        } else {
            // 没抓到帧也不能不给用，退化成半透明遮罩，至少能框
            setBackgroundColor(Color.parseColor("#CC000000"))
        }

        maskView = MaskView(ctx, frame)
        addView(maskView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---- 底部操作条 ----
        val pad = dp(14)
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E6101012"))
            setPadding(pad, dp(10), pad, dp(10))
        }

        preview = ImageView(ctx).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        previewBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                preview,
                LinearLayout.LayoutParams(dp(132), dp(74))
            )
            addView(
                TextView(ctx).apply {
                    text = "将送出这一块"
                    setTextColor(0xFF9E9E9E.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        bar.addView(
            previewBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        tvSize = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = "在画面上拖出要翻译的文字区域"
        }
        // 注意：权重必须用 LinearLayout.LayoutParams。写成外层 FrameLayout 的
        // LayoutParams(0, WRAP_CONTENT, 1f) 会被解析成 (width, height, **int gravity**)，
        // 第三个参数类型不符，编译期直接报错。
        bar.addView(
            tvSize,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(12) }
        )

        bar.addView(button("取消") { onCancel() })
        bar.addView(
            button("确定") { maskView.current()?.let { onConfirm(it) } ?: onCancel() }
                .apply { setTextColor(0xFF7CE38B.toInt()) }
        )

        addView(
            bar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
        )
    }

    private fun button(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun attachToWindow(wm: WindowManager) {
        wm.addView(this, wmParams)
    }

    fun detachFromWindow(wm: WindowManager) {
        runCatching { wm.removeView(this) }
    }

    /** 打印一次坐标基准，用于区分"视图被系统撑大"与"换算写错" */
    fun logGeometry() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(dm)
        Log.i(TAG, "框选遮罩: 视图=${width}x$height 真实屏幕=${dm.widthPixels}x${dm.heightPixels} " +
            "帧=${frame?.width}x${frame?.height}")
    }

    // ==================================================================

    /**
     * 负责画遮罩/边框/把手/放大镜，并处理拖动。
     * 坐标即本视图坐标；本视图铺满全屏，所以也就是屏幕坐标。
     */
    private inner class MaskView(
        ctx: Context,
        private val frame: Bitmap?
    ) : View(ctx) {

        private var box: RectF? = null
        private var dragCorner = -1          // -1 没在拖角，0~3 = 左上/右上/左下/右下
        private var startX = 0f
        private var startY = 0f
        private var touchX = 0f
        private var touchY = 0f
        private var drawing = false

        private val dim = Paint().apply { color = Color.parseColor("#99000000") }
        private val border = Paint().apply {
            color = Color.parseColor("#7CE38B")
            style = Paint.Style.STROKE
            strokeWidth = dpF(2f)
        }
        private val handlePaint = Paint().apply {
            color = Color.parseColor("#7CE38B")
            style = Paint.Style.FILL
        }
        private val hintPaint = Paint().apply {
            color = Color.WHITE
            textSize = dpF(12f)
            isAntiAlias = true
        }
        private val magnifierBorder = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dpF(2f)
        }
        private val crosshair = Paint().apply {
            color = Color.parseColor("#FF5252")
            style = Paint.Style.STROKE
            strokeWidth = dpF(1f)
        }

        fun current(): Rect? {
            val b = box ?: return null
            if (b.width() < dpF(24f) || b.height() < dpF(24f)) return null
            return Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            touchX = e.x; touchY = e.y
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = hitCorner(e.x, e.y)
                    if (hit >= 0) {
                        dragCorner = hit
                    } else {
                        // 空白处按下 = 重新画一个框
                        startX = e.x; startY = e.y
                        box = RectF(e.x, e.y, e.x, e.y)
                        drawing = true
                        dragCorner = -1
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val b = box
                    if (drawing && b != null) {
                        b.set(minOf(startX, e.x), minOf(startY, e.y), maxOf(startX, e.x), maxOf(startY, e.y))
                    } else if (dragCorner >= 0 && b != null) {
                        // 拖角只动那两个边，另外两边保持不动
                        when (dragCorner) {
                            0 -> { b.left = e.x; b.top = e.y }
                            1 -> { b.right = e.x; b.top = e.y }
                            2 -> { b.left = e.x; b.bottom = e.y }
                            3 -> { b.right = e.x; b.bottom = e.y }
                        }
                        normalize(b)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    drawing = false
                    dragCorner = -1
                    refreshPreview()
                }
            }
            invalidate()
            return true
        }

        /** 拖角可能把矩形拖成负宽高，规范一下（否则后续取宽高全是负数） */
        private fun normalize(b: RectF) {
            if (b.left > b.right) { val t = b.left; b.left = b.right; b.right = t }
            if (b.top > b.bottom) { val t = b.top; b.top = b.bottom; b.bottom = t }
        }

        /** 触摸点是否落在某个角把手上（判定半径比把手本身大，手指才好按） */
        private fun hitCorner(x: Float, y: Float): Int {
            val b = box ?: return -1
            val r = dpF(30f)
            val corners = arrayOf(b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom)
            corners.forEachIndexed { i, (cx, cy) ->
                if (hypot((x - cx).toDouble(), (y - cy).toDouble()) <= r) return i
            }
            return -1
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val b = box

            if (b == null) {
                canvas.drawText("拖出一个框，框住要翻译的文字", dpF(20f), dpF(60f), hintPaint)
                return
            }

            // 四块遮罩（选区镂空）—— 让用户一眼看出框住的是哪块
            canvas.drawRect(0f, 0f, w, b.top, dim)
            canvas.drawRect(0f, b.bottom, w, h, dim)
            canvas.drawRect(0f, b.top, b.left, b.bottom, dim)
            canvas.drawRect(b.right, b.top, w, b.bottom, dim)

            canvas.drawRect(b, border)
            val hs = dpF(9f)
            for ((cx, cy) in arrayOf(b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom)) {
                canvas.drawCircle(cx, cy, hs, handlePaint)
            }

            drawMagnifier(canvas, w, h, b)
        }

        /**
         * 放大镜：手指挡住的地方看不见，取帧又要求精确到字，所以给个 3 倍镜。
         * 位置固定在选区上方/下方里空间更大的那一侧，避免跟着手指抖。
         */
        private fun drawMagnifier(canvas: Canvas, w: Float, h: Float, b: RectF) {
            val bmp = frame ?: return
            val r = dpF(56f)
            val cx = w / 2f
            val cy = if (b.top > r * 2 + dpF(20f)) b.top - r - dpF(12f) else b.bottom + r + dpF(12f)
            if (cy - r < 0 || cy + r > h) return

            // 视图坐标 → 帧坐标（这里与服务取帧时用的是同一套比例关系）
            val sx = bmp.width.toFloat() / w
            val sy = bmp.height.toFloat() / h
            val srcHalf = (r / MAGNIFY) * 1f
            val srcW = (srcHalf * 2 * sx).toInt().coerceAtLeast(2)
            val srcH = (srcHalf * 2 * sy).toInt().coerceAtLeast(2)
            val srcL = (touchX * sx - srcW / 2f).toInt()
            val srcT = (touchY * sy - srcH / 2f).toInt()
            val src = Rect(
                srcL.coerceIn(0, (bmp.width - srcW).coerceAtLeast(0)),
                srcT.coerceIn(0, (bmp.height - srcH).coerceAtLeast(0)),
                (srcL + srcW).coerceIn(srcW, bmp.width),
                (srcT + srcH).coerceIn(srcH, bmp.height)
            )
            val dst = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawBitmap(bmp, src, dst, null)
            canvas.drawCircle(cx, cy, r, magnifierBorder)
            canvas.drawLine(cx - dpF(10f), cy, cx + dpF(10f), cy, crosshair)
            canvas.drawLine(cx, cy - dpF(10f), cx, cy + dpF(10f), crosshair)
        }
    }

    /** 用服务取帧时的**同一个**换算函数生成预览，所以预览=实际送出的内容 */
    private fun refreshPreview() {
        val rect = maskView.current() ?: run {
            preview.setImageDrawable(null)
            tvSize.text = context.getString(R.string.roi_picker_t01)
            return
        }
        val bmp = frame
        if (bmp == null) {
            tvSize.text = "${rect.width()} × ${rect.height()}"
            return
        }
        val fr = Roi.toFrameCoords(rect, bmp.width, bmp.height, width, height)
        if (fr == null) {
            preview.setImageDrawable(null)
            tvSize.text = context.getString(R.string.roi_picker_t02)
            return
        }
        runCatching {
            preview.setImageBitmap(Bitmap.createBitmap(bmp, fr.left, fr.top, fr.width(), fr.height()))
        }
        tvSize.text = "${rect.width()} × ${rect.height()}"
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()

    private fun dpF(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics
    )

    companion object {
        private const val TAG = "ScreenTranslator"
        private const val MAGNIFY = 3f
    }
}
