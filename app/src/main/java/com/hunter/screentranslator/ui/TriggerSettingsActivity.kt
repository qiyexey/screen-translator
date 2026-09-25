package com.hunter.screentranslator.ui

import android.os.Bundle
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.databinding.ActivityTriggerSettingsBinding
import com.hunter.screentranslator.service.OverlayService
import com.hunter.screentranslator.util.EdgeToEdge

/**
 * v1.12.0 三级页：翻译触发方式。
 *
 * 这五个开关决定"什么时候翻译"，互相之间有干扰（全屏自动翻译最费额度），
 * 所以集中在一页、带各自的说明，而不是散在主页面里。
 */
class TriggerSettingsActivity : BaseActivity() {

    private lateinit var b: ActivityTriggerSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTriggerSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.install(this)
        b.topAppBar.setNavigationOnClickListener { finish() }

        b.swAutoTranslate.isChecked = App.prefs.autoTranslate
        b.swOverlay.isChecked = App.prefs.overlayEnabled
        b.swSelectionTranslate.isChecked = App.prefs.selectionTranslate
        b.swFloatingBall.isChecked = App.prefs.floatingBall
        b.swClipboardTranslate.isChecked = App.prefs.clipboardTranslate

        b.btnSave.setOnClickListener {
            val ballBefore = App.prefs.floatingBall

            App.prefs.autoTranslate = b.swAutoTranslate.isChecked
            App.prefs.overlayEnabled = b.swOverlay.isChecked
            App.prefs.selectionTranslate = b.swSelectionTranslate.isChecked
            App.prefs.floatingBall = b.swFloatingBall.isChecked
            App.prefs.clipboardTranslate = b.swClipboardTranslate.isChecked

            toast(getString(R.string.common_t10))
            // 保存后如果权限齐全，直接把悬浮窗拉起来
            maybeStartOverlay()
            // 悬浮球开关变化 → 即时增删悬浮球
            if (ballBefore != App.prefs.floatingBall) {
                runCatching { OverlayService.setBallEnabled(this, App.prefs.floatingBall) }
            }
        }
    }
}
