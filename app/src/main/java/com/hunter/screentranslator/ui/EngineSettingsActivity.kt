package com.hunter.screentranslator.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.LANG_DISPLAY
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.databinding.ActivityEngineSettingsBinding
import com.hunter.screentranslator.service.OverlayService
import kotlinx.coroutines.launch

/**
 * v1.12.0 三级页：翻译引擎 · 目标语言 · 密钥。
 *
 * 这一页承担了主页原来最肿的那张卡（890 行）。**11 家引擎的配置区仍全部保留在
 * 布局里**，但沿用原有的显隐机制一次只显示一组 —— 这样切引擎时输入框不会丢内容，
 * 也不必动态建 View。
 */
class EngineSettingsActivity : BaseActivity() {

    private lateinit var b: ActivityEngineSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEngineSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }

        // ---- 回显所有引擎的已存配置 ----
        b.etApiKey.setText(App.prefs.apiKey)
        b.etBaseUrl.setText(App.prefs.baseUrl)
        b.etModel.setText(App.prefs.model)
        b.etGoogleKey.setText(App.prefs.googleApiKey)
        b.etMsKey.setText(App.prefs.msApiKey)
        b.etMsRegion.setText(App.prefs.msRegion)
        b.etDeeplKey.setText(App.prefs.deeplApiKey)
        b.etOpenAIKey.setText(App.prefs.openaiApiKey)
        b.etOpenAIBaseUrl.setText(App.prefs.openaiBaseUrl)
        b.etOpenAIModel.setText(App.prefs.openaiModel)
        b.etClaudeKey.setText(App.prefs.claudeApiKey)
        b.etClaudeModel.setText(App.prefs.claudeModel)
        b.etQwenKey.setText(App.prefs.qwenApiKey)
        b.etQwenModel.setText(App.prefs.qwenModel)
        b.etGLMKey.setText(App.prefs.glmApiKey)
        b.etGLMModel.setText(App.prefs.glmModel)
        b.etDoubaoKey.setText(App.prefs.doubaoApiKey)
        b.etDoubaoModel.setText(App.prefs.doubaoModel)
        b.etBaiduAppId.setText(App.prefs.baiduAppId)
        b.etBaiduKey.setText(App.prefs.baiduKey)
        b.etCaiyunToken.setText(App.prefs.caiyunToken)

        // ---- 引擎下拉 ----
        val engines = TranslationEngine.entries.toList()
        b.spinnerEngine.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            engines.map { it.displayName }
        )
        val currentEngine = TranslationEngine.fromKey(App.prefs.engine)
        b.spinnerEngine.setSelection(engines.indexOf(currentEngine))
        b.spinnerEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applyEngineVisibility(engines[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        applyEngineVisibility(currentEngine)

        // ---- 目标语言 ----
        val langCodes = LANG_DISPLAY.keys.toList()
        b.spinnerTarget.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            langCodes.map { "${LANG_DISPLAY[it]} ($it)" }
        )
        b.spinnerTarget.setSelection(langCodes.indexOf(App.prefs.targetLang).coerceAtLeast(0))

        b.tvApiKeyHelp.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com")))
        }

        // ---- 只保存本页负责的字段（各子页各自保存）----
        b.btnSave.setOnClickListener {
            saveEngineConfigs()
            App.prefs.engine = engines[b.spinnerEngine.selectedItemPosition].key
            App.prefs.targetLang = langCodes[b.spinnerTarget.selectedItemPosition]
            toast("已保存")
        }

        b.btnTestTranslate.setOnClickListener { testTranslate() }
    }

    /** 根据所选引擎显示/隐藏对应配置区 */
    private fun applyEngineVisibility(engine: TranslationEngine) {
        val v = View.VISIBLE
        val g = View.GONE
        b.layoutDeepSeek.visibility = if (engine == TranslationEngine.DEEPSEEK) v else g
        b.layoutOpenAI.visibility = if (engine == TranslationEngine.OPENAI) v else g
        b.layoutClaude.visibility = if (engine == TranslationEngine.CLAUDE) v else g
        b.layoutQwen.visibility = if (engine == TranslationEngine.QWEN) v else g
        b.layoutGLM.visibility = if (engine == TranslationEngine.GLM) v else g
        b.layoutDoubao.visibility = if (engine == TranslationEngine.DOUBAO) v else g
        b.layoutGoogle.visibility = if (engine == TranslationEngine.GOOGLE) v else g
        b.layoutMicrosoft.visibility = if (engine == TranslationEngine.MICROSOFT) v else g
        b.layoutDeepl.visibility = if (engine == TranslationEngine.DEEPL) v else g
        b.layoutBaidu.visibility = if (engine == TranslationEngine.BAIDU) v else g
        b.layoutCaiyun.visibility = if (engine == TranslationEngine.CAIYUN) v else g
        b.layoutBingWeb.visibility = if (engine == TranslationEngine.BING_WEB) v else g
    }

    /**
     * 把所有引擎的输入框内容写入偏好。
     *
     * 注意：ASR（语音识别）的三个字段**不在这里** —— 它们属于
     * [AsrSettingsActivity]。「各子页只写自己那一摊」是这次拆分的约定，
     * 避免某一页的保存把别的页正在编辑的内容一起覆盖掉。
     */
    private fun saveEngineConfigs() {
        App.prefs.apiKey = b.etApiKey.text.toString().trim()
        App.prefs.baseUrl = b.etBaseUrl.text.toString().trim()
            .ifBlank { "https://api.deepseek.com" }
        App.prefs.model = b.etModel.text.toString().trim()
            .ifBlank { "deepseek-chat" }
        App.prefs.googleApiKey = b.etGoogleKey.text.toString().trim()
        App.prefs.msApiKey = b.etMsKey.text.toString().trim()
        App.prefs.msRegion = b.etMsRegion.text.toString().trim()
        App.prefs.deeplApiKey = b.etDeeplKey.text.toString().trim()
        App.prefs.openaiApiKey = b.etOpenAIKey.text.toString().trim()
        App.prefs.openaiBaseUrl = b.etOpenAIBaseUrl.text.toString().trim()
            .ifBlank { "https://api.openai.com/v1" }
        App.prefs.openaiModel = b.etOpenAIModel.text.toString().trim()
            .ifBlank { "gpt-4o-mini" }
        App.prefs.claudeApiKey = b.etClaudeKey.text.toString().trim()
        App.prefs.claudeModel = b.etClaudeModel.text.toString().trim()
            .ifBlank { "claude-3-5-haiku-20241022" }
        App.prefs.qwenApiKey = b.etQwenKey.text.toString().trim()
        App.prefs.qwenModel = b.etQwenModel.text.toString().trim()
            .ifBlank { "qwen-plus" }
        App.prefs.glmApiKey = b.etGLMKey.text.toString().trim()
        App.prefs.glmModel = b.etGLMModel.text.toString().trim()
            .ifBlank { "glm-4-flash" }
        App.prefs.doubaoApiKey = b.etDoubaoKey.text.toString().trim()
        App.prefs.doubaoModel = b.etDoubaoModel.text.toString().trim()
        App.prefs.baiduAppId = b.etBaiduAppId.text.toString().trim()
        App.prefs.baiduKey = b.etBaiduKey.text.toString().trim()
        App.prefs.caiyunToken = b.etCaiyunToken.text.toString().trim()
    }

    /** 真实调用一次当前引擎的 API 验证配置 */
    private fun testTranslate() {
        // 先落盘当前输入框内容（含所有引擎），否则测的是旧 Key
        saveEngineConfigs()

        val engine = TranslationEngine.fromKey(App.prefs.engine)
        val engineName = engine.displayName
        val keyReady = when (engine) {
            TranslationEngine.DEEPSEEK -> App.prefs.apiKey.isNotBlank()
            TranslationEngine.OPENAI -> App.prefs.openaiApiKey.isNotBlank()
            TranslationEngine.CLAUDE -> App.prefs.claudeApiKey.isNotBlank()
            TranslationEngine.QWEN -> App.prefs.qwenApiKey.isNotBlank()
            TranslationEngine.GLM -> App.prefs.glmApiKey.isNotBlank()
            TranslationEngine.DOUBAO ->
                App.prefs.doubaoApiKey.isNotBlank() && App.prefs.doubaoModel.isNotBlank()
            TranslationEngine.GOOGLE -> App.prefs.googleApiKey.isNotBlank()
            TranslationEngine.MICROSOFT -> App.prefs.msApiKey.isNotBlank()
            TranslationEngine.DEEPL -> App.prefs.deeplApiKey.isNotBlank()
            TranslationEngine.BAIDU ->
                App.prefs.baiduAppId.isNotBlank() && App.prefs.baiduKey.isNotBlank()
            TranslationEngine.CAIYUN -> App.prefs.caiyunToken.isNotBlank()
            // 免密钥引擎：永远算"已配置"
            TranslationEngine.BING_WEB -> true
        }
        if (!keyReady) {
            toast("请先填写 $engineName 的密钥")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限（测试结果会显示在悬浮窗）")
        }

        b.btnTestTranslate.isEnabled = false
        b.btnTestTranslate.text = "测试中…"

        lifecycleScope.launch {
            val result = TranslatorFactory.current().translate(
                "Hello! This is a translation test.",
                App.prefs.targetLang
            )
            b.btnTestTranslate.isEnabled = true
            b.btnTestTranslate.text = "测试翻译（真调 API 验证 Key）"

            result.fold(
                onSuccess = { translated ->
                    OverlayService.update("Hello! This is a translation test.", translated)
                    AlertDialog.Builder(this@EngineSettingsActivity)
                        .setTitle("✅ 测试成功（$engineName）")
                        .setMessage("翻译结果：$translated\n\n密钥和网络均正常。")
                        .setPositiveButton("好的", null)
                        .show()
                },
                onFailure = { e ->
                    AlertDialog.Builder(this@EngineSettingsActivity)
                        .setTitle("❌ 测试失败（$engineName）")
                        .setMessage(
                            "${e.message}\n\n请检查：\n1. 密钥是否正确\n2. 账户额度/余额\n" +
                                    "3. 手机能否访问该服务"
                        )
                        .setPositiveButton("好的", null)
                        .show()
                }
            )
        }
    }
}
