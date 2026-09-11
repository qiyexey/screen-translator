package com.hunter.screentranslator.ui

import android.os.Bundle
import com.hunter.screentranslator.App
import com.hunter.screentranslator.databinding.ActivityAsrSettingsBinding

/**
 * v1.12.0 三级页：语音识别（听视频用）。
 *
 * 走 OpenAI 兼容的 /v1/audio/transcriptions，与"翻译引擎"是两套独立配置 ——
 * 拆页后终于不用在一堆翻译 Key 里找它。
 */
class AsrSettingsActivity : BaseActivity() {

    private lateinit var b: ActivityAsrSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAsrSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }

        b.etAsrKey.setText(App.prefs.asrApiKey)
        b.etAsrBaseUrl.setText(App.prefs.asrBaseUrl)
        b.etAsrModel.setText(App.prefs.asrModel)

        b.btnSave.setOnClickListener {
            App.prefs.asrApiKey = b.etAsrKey.text.toString().trim()
            App.prefs.asrBaseUrl = b.etAsrBaseUrl.text.toString().trim()
                .ifBlank { "https://api.openai.com/v1" }
            App.prefs.asrModel = b.etAsrModel.text.toString().trim()
                .ifBlank { "whisper-1" }
            toast("已保存")
        }
    }
}
