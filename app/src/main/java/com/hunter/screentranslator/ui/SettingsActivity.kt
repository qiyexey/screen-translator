package com.hunter.screentranslator.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.databinding.ActivitySettingsBinding
import com.hunter.screentranslator.util.EdgeToEdge

/**
 * v1.12.0 二级菜单：设置列表。
 *
 * 主页只留功能入口，配置按"使用频率 + 主题"分成七组，各自一个三级页面。
 * 分组依据：改引擎密钥是低频高价值、调触发方式是低频但影响大、调外观是纯偏好、
 * 修权限是出问题时才来的 —— 混在一页里就是改造前那种 1423 行的长滚动。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.install(this)

        b.topAppBar.setNavigationOnClickListener { finish() }

        b.rowEngine.setOnClickListener {
            startActivity(Intent(this, EngineSettingsActivity::class.java))
        }
        b.rowTrigger.setOnClickListener {
            startActivity(Intent(this, TriggerSettingsActivity::class.java))
        }
        b.rowTts.setOnClickListener {
            startActivity(Intent(this, TtsSettingsActivity::class.java))
        }
        b.rowAsr.setOnClickListener {
            startActivity(Intent(this, AsrSettingsActivity::class.java))
        }
        b.rowBall.setOnClickListener {
            startActivity(Intent(this, BallStyleActivity::class.java))
        }
        b.rowPermission.setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }
        b.rowAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    /**
     * v1.21.0：设置索引不再写一堆静态“这里可以设置什么”，而是显示当前状态。
     * 用户无需逐项点进去确认：引擎、触发项数量、语音接口、权限是否就绪一眼可见。
     */
    private fun refreshSummaries() {
        val engine = TranslationEngine.fromKey(App.prefs.engine)
        b.tvEngineSummary.text = getString(R.string.settings_engine_summary, engine.displayName)

        val enabledTriggers = listOf(
            App.prefs.floatingBall,
            App.prefs.selectionTranslate,
            App.prefs.autoTranslate,
            App.prefs.clipboardTranslate
        ).count { it }
        b.tvTriggerSummary.text = getString(R.string.settings_trigger_summary, enabledTriggers)

        b.tvTtsSummary.text = getString(
            if (App.prefs.ttsAutoSpeak) R.string.settings_tts_summary_on
            else R.string.settings_tts_summary_off
        )

        val defaultAsrUrl = "https://api.openai.com/v1"
        val asrReady = App.prefs.asrApiKey.isNotBlank() ||
                App.prefs.asrBaseUrl.trim().trimEnd('/') != defaultAsrUrl
        b.tvAsrSummary.text = getString(
            if (asrReady) R.string.settings_asr_summary_ready
            else R.string.settings_asr_summary_missing
        )

        b.tvBallSummary.text = getString(
            R.string.settings_ball_summary,
            App.prefs.ballSizeDp,
            (App.prefs.ballAlpha * 100).toInt()
        )

        val overlayReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)
        val permissionsReady = isAccessibilityEnabled() && overlayReady
        b.tvPermissionSummary.text = getString(
            if (permissionsReady) R.string.settings_permission_summary_ready
            else R.string.settings_permission_summary_missing
        )
        b.tvPermissionSummary.setTextColor(
            if (permissionsReady) resColor(R.color.md_success)
            else themeColor(com.google.android.material.R.attr.colorError)
        )
    }
}
