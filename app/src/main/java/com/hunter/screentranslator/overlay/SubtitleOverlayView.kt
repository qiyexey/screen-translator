package com.hunter.screentranslator.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.hunter.screentranslator.App
import kotlin.math.abs

/**
 * 听视频翻译的悬浮字幕条（v1.6.0）。
 * 顶部悬浮，可拖动，原文一行小字 + 译文多行大字，右上角 ✕ 关闭（停止采集）。
 * 不自动隐藏——听视频期间常驻，由用户关闭或服务停止时移除。
 */
class SubtitleOverlayView(
    ctx: Context,
    private val onClose: () -> Unit
) : LinearLayout(ctx) {

    private val tvStatus: TextView
    private val tvSource: TextView
    private val tvTranslated: TextView

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
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        x = 0
        y = dp(56)
    }

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(applyPanelAlpha(0xDD101012.toInt()))
        }
        elevation = 20f
        val padH = dp(14)
        val padV = dp(10)
        setPadding(padH, padV, padH, padV)

        tvStatus = TextView(ctx).apply {
            text = "🎧 听视频翻译 · 启动中…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF9E9E9E.toInt())
        }
        tvSource = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFB0B0B0.toInt())
            maxLines = 2
        }
        tvTranslated = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 4
            setLineSpacing(dp(3).toFloat(), 1f)
        }

        // 状态行：标题 + 关闭按钮
        val statusRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        statusRow.addView(
            tvStatus,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        val tvClose = TextView(ctx).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFF888888.toInt())
            setPadding(dp(8), 0, dp(4), dp(4))
            setOnClickListener { onClose() }
        }
        statusRow.addView(tvClose, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        addView(statusRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            tvSource,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        )
        addView(
            tvTranslated,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        )

        setOnTouchListener(DragListener())
    }

    fun attachToWindow(wm: WindowManager) {
        wm.addView(this, wmParams)
    }

    fun detachFromWindow(wm: WindowManager) {
        runCatching { wm.removeView(this) }
    }

    /** 更新字幕：转写原文 + 译文（译文传 null 表示还在处理中） */
    fun update(source: String, translated: String?) {
        post {
            tvSource.text = if (source.isBlank()) "" else source
            tvTranslated.text = translated ?: "…"
        }
    }

    /** 更新状态行文字（如「正在转写」「耳机模式」） */
    fun setStatus(status: String) {
        post { tvStatus.text = "🎧 $status" }
    }

    /** 背景透明度跟随面板透明度设置（复用同一偏好，取值较默认更实一些） */
    private fun applyPanelAlpha(argb: Int): Int {
        val alpha = ((argb ushr 24) * App.prefs.panelAlpha).toInt().coerceAtLeast(0x60)
        return (alpha shl 24) or (argb and 0x00FFFFFF)
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class DragListener : OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = wmParams.x
                    initialY = wmParams.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > 5 || abs(dy) > 5) moved = true
                    wmParams.x = initialX + dx.toInt()
                    wmParams.y = initialY + dy.toInt()
                    runCatching {
                        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                            .updateViewLayout(v, wmParams)
                    }
                }
            }
            return false  // 让 ✕ 按钮 onClick 正常触发
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()
}
