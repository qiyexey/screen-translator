package com.hunter.screentranslator.ui

import android.os.Bundle
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.databinding.ActivityAsrSettingsBinding
import com.hunter.screentranslator.util.EdgeToEdge

/**
 * Whisper 语音识别配置页，供语音翻译使用。
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
        EdgeToEdge.install(this)
        b.topAppBar.setNavigationOnClickListener { finish() }

        b.etAsrKey.setText(App.prefs.asrApiKey)
        b.etAsrBaseUrl.setText(App.prefs.asrBaseUrl)
        b.etAsrModel.setText(App.prefs.asrModel)

        b.btnSave.setOnClickListener {
            App.prefs.asrApiKey = b.etAsrKey.text.toString().trim()
            App.prefs.asrBaseUrl = b.etAsrBaseUrl.text.toString().trim()
                .ifBlank { "https://api.openai.com/v1" }
            App.prefs.asrModel = b.etAsrModel.text.toString().trim()
                .ifBlank { "whisper-1" }
            toast(getString(R.string.common_t10))
        }
    }
}
