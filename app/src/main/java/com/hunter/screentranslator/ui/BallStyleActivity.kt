package com.hunter.screentranslator.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import com.hunter.screentranslator.App
import com.hunter.screentranslator.databinding.ActivityBallStyleBinding
import com.hunter.screentranslator.service.OverlayService

/**
 * v1.12.0 三级页：悬浮球与结果面板外观。
 *
 * 纯偏好项，不影响"能不能用"，所以从主页面搬走。保存后即时刷新外观。
 */
class BallStyleActivity : BaseActivity() {

    private lateinit var b: ActivityBallStyleBinding

    /** 预设颜色（与默认悬浮球绿同款透明度档位） */
    private val ballColorPresets = intArrayOf(
        0xCC2E7D32.toInt(),  // 绿（默认）
        0xCC1565C0.toInt(),  // 蓝
        0xCCEF6C00.toInt(),  // 橙
        0xCCE53935.toInt(),  // 红
        0xCC6A1B9A.toInt(),  // 紫
        0xCC00838F.toInt(),  // 青
        0xCCAD1457.toInt(),  // 玫红
        0xCC424242.toInt()   // 墨灰
    )

    private var selectedBallColor: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBallStyleBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }

        setupBallStyleControls()

        b.btnSave.setOnClickListener {
            App.prefs.ballAlpha = (b.seekBallAlpha.progress + 20) / 100f
            App.prefs.ballSizeDp = b.seekBallSize.progress + 36
            App.prefs.ballColor = selectedBallColor
            App.prefs.panelAlpha = (b.seekPanelAlpha.progress + 40) / 100f
            toast("已保存")
            // 样式变化 → 即时刷新悬浮球外观与面板透明度（球不存在时只刷面板）
            if (App.prefs.overlayEnabled) {
                runCatching { OverlayService.refreshBallStyle(this) }
            }
        }
    }

    private fun setupBallStyleControls() {
        // 透明度：SeekBar 0..80 映射 20%..100%
        b.seekBallAlpha.progress = (App.prefs.ballAlpha * 100).toInt() - 20
        b.tvBallAlphaValue.text = "${(App.prefs.ballAlpha * 100).toInt()}%"
        b.seekBallAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                b.tvBallAlphaValue.text = "${p + 20}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 大小：SeekBar 0..28 映射 36..64 dp
        b.seekBallSize.progress = App.prefs.ballSizeDp - 36
        b.tvBallSizeValue.text = "${App.prefs.ballSizeDp} dp"
        b.seekBallSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                b.tvBallSizeValue.text = "${p + 36} dp"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 结果面板透明度：SeekBar 0..60 映射 40%..100%
        b.seekPanelAlpha.progress = (App.prefs.panelAlpha * 100).toInt() - 40
        b.tvPanelAlphaValue.text = "${(App.prefs.panelAlpha * 100).toInt()}%"
        b.seekPanelAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                b.tvPanelAlphaValue.text = "${p + 40}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 颜色选择器：动态生成圆形色块
        selectedBallColor = App.prefs.ballColor
        ballColorPresets.forEach { c ->
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(dp(34), dp(34))
            lp.marginEnd = dp(12)
            dot.layoutParams = lp
            renderBallColorDot(dot, c, c == selectedBallColor)
            dot.setOnClickListener {
                selectedBallColor = c
                refreshColorDots()
            }
            b.layoutBallColors.addView(dot)
        }
    }

    private fun renderBallColorDot(v: View, color: Int, selected: Boolean) {
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                if (selected) dp(3) else dp(1),
                if (selected) 0xFFFFFFFF.toInt() else 0x55FFFFFF.toInt()
            )
        }
    }

    private fun refreshColorDots() {
        ballColorPresets.forEachIndexed { i, c ->
            b.layoutBallColors.getChildAt(i)?.let {
                renderBallColorDot(it, c, c == selectedBallColor)
            }
        }
    }
}
