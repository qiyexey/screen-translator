package com.hunter.screentranslator.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 框选翻译遮罩（v1.5.0）：
 * 双击悬浮球后添加，覆盖全屏半透明遮罩；手指拖出矩形选区，
 * 松手回调 onRegionSelected(屏幕坐标矩形)，单击（未拖出选区）取消。
 *
 * 坐标系：FLAG_LAYOUT_IN_SCREEN 全屏铺满，视图内 (x, y) 即屏幕绝对坐标，
 * 与无障碍节点 getBoundsInScreen() 一致，可直接传给 translateInRegion()。
 */
class RegionSelectView(
    ctx: Context,
    private val onRegionSelected: (Rect) -> Unit,
    private val onDismiss: () -> Unit
) : View(ctx) {

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
        x = 0
        y = 0
    }

    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var dragging = false

    private val maskPaint = Paint().apply { color = Color.parseColor("#99000000") }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.5f)
    }
    private val cornerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpF(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val tipBgPaint = Paint().apply {
        color = Color.parseColor("#CC1C1C1E")
        style = Paint.Style.FILL
    }
    private val tipTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = dpF(13f)
        isAntiAlias = true
    }
    private val sizeTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = dpF(11f)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    fun attachToWindow(wm: WindowManager) {
        wm.addView(this, wmParams)
    }

    fun detachFromWindow(wm: WindowManager) {
        runCatching { wm.removeView(this) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // ===== 提示条 =====
        val tip = "拖动框选要翻译的区域 · 松手即翻译 · 单击取消"
        val tipW = tipTextPaint.measureText(tip) + dpF(32f)
        val tipH = dpF(36f)
        val tipTop = dpF(48f)
        val tipRect = RectF((w - tipW) / 2, tipTop, (w + tipW) / 2, tipTop + tipH)
        canvas.drawRoundRect(tipRect, tipH / 2, tipH / 2, tipBgPaint)
        val tipY = tipTop + tipH / 2 - (tipTextPaint.descent() + tipTextPaint.ascent()) / 2
        canvas.drawText(tip, w / 2 - tipTextPaint.measureText(tip) / 2, tipY, tipTextPaint)

        if (!dragging) return

        val l = minOf(startX, endX)
        val t = minOf(startY, endY)
        val r = maxOf(startX, endX)
        val b = maxOf(startY, endY)

        // ===== 四块遮罩（选区镂空） =====
        canvas.drawRect(0f, 0f, w, t, maskPaint)                    // 上
        canvas.drawRect(0f, b, w, h, maskPaint)                     // 下
        canvas.drawRect(0f, t, l, b, maskPaint)                     // 左
        canvas.drawRect(r, t, w, b, maskPaint)                      // 右

        // ===== 选区边框 + 四角角标 =====
        canvas.drawRect(l, t, r, b, borderPaint)
        val c = dpF(16f)  // 角标臂长
        // 左上
        canvas.drawLine(l, t, l + c, t, cornerPaint)
        canvas.drawLine(l, t, l, t + c, cornerPaint)
        // 右上
        canvas.drawLine(r - c, t, r, t, cornerPaint)
        canvas.drawLine(r, t, r, t + c, cornerPaint)
        // 左下
        canvas.drawLine(l, b - c, l, b, cornerPaint)
        canvas.drawLine(l, b, l + c, b, cornerPaint)
        // 右下
        canvas.drawLine(r - c, b, r, b, cornerPaint)
        canvas.drawLine(r, b, r, b - c, cornerPaint)

        // ===== 尺寸提示（选区上方，放不下就放选区内顶部） =====
        val sizeText = "${(r - l).toInt()} × ${(b - t).toInt()}"
        var sy = t - dpF(10f)
        if (sy < tipTop + tipH + dpF(8f)) sy = t + dpF(18f)
        canvas.drawText(sizeText, (l + r) / 2, sy, sizeTextPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x
                startY = e.y
                endX = e.x
                endY = e.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                endX = e.x
                endY = e.y
                if (kotlin.math.abs(endX - startX) > dpF(6f) ||
                    kotlin.math.abs(endY - startY) > dpF(6f)
                ) dragging = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    performClick()
                    onDismiss()  // 单击空白：取消框选
                    return true
                }
                val rect = Rect(
                    minOf(startX, endX).toInt(), minOf(startY, endY).toInt(),
                    maxOf(startX, endX).toInt(), maxOf(startY, endY).toInt()
                )
                // 太小的框（<24dp）视为误触，取消
                if (rect.width() < dpF(24f) || rect.height() < dpF(24f)) {
                    onDismiss()
                } else {
                    onRegionSelected(rect)
                }
            }
            MotionEvent.ACTION_CANCEL -> onDismiss()
        }
        return true
    }

    private fun dpF(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    )
}
