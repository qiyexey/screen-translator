package com.hunter.screentranslator.api

import com.hunter.screentranslator.App
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 翻译器工厂：根据用户选择的引擎创建对应的 Translator 实例。
 * 每次调用都读最新偏好，这样切换引擎立即生效。
 *
 * v1.10.0：所有引擎统一在最外层包一层 [CachingTranslator]。
 * 第 7 个调用点（含图片翻译与"测试翻译"按钮）因此一次性获得缓存能力，调用点零改动。
 */
object TranslatorFactory {

    fun current(): Translator {
        val engine = TranslationEngine.fromKey(App.prefs.engine)
        // 第二项是「缓存作用域」的细粒度部分（端点 + 模型）。换中转、换模型、换区域后
        // 译文可能不同，不能复用老译文；同名模型挂在不同中转后端上甚至可能是两套服务。
        val built: Pair<Translator, String> = when (engine) {
            TranslationEngine.DEEPSEEK -> {
                val base = App.prefs.baseUrl.ifBlank { "https://api.deepseek.com" }
                val m = App.prefs.model.ifBlank { "deepseek-chat" }
                OpenAICompatibleTranslator(
                    endpoint = chatEndpoint(base),
                    apiKey = App.prefs.apiKey,
                    model = m,
                    engineName = "DeepSeek"
                ) to (hostOf(base) + "|" + m)
            }
            TranslationEngine.OPENAI -> {
                val base = App.prefs.openaiBaseUrl.ifBlank { "https://api.openai.com/v1" }
                val m = App.prefs.openaiModel.ifBlank { "gpt-4o-mini" }
                OpenAICompatibleTranslator(
                    endpoint = chatEndpoint(base),
                    apiKey = App.prefs.openaiApiKey,
                    model = m,
                    engineName = "OpenAI"
                ) to (hostOf(base) + "|" + m)
            }
            TranslationEngine.CLAUDE -> {
                val m = App.prefs.claudeModel.ifBlank { "claude-3-5-haiku-20241022" }
                ClaudeTranslator() to ("api.anthropic.com|" + m)
            }
            TranslationEngine.QWEN -> {
                val m = App.prefs.qwenModel.ifBlank { "qwen-plus" }
                OpenAICompatibleTranslator(
                    endpoint = chatEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1"),
                    apiKey = App.prefs.qwenApiKey,
                    model = m,
                    engineName = "通义千问"
                ) to ("dashscope.aliyuncs.com|" + m)
            }
            TranslationEngine.GLM -> {
                val m = App.prefs.glmModel.ifBlank { "glm-4-flash" }
                OpenAICompatibleTranslator(
                    endpoint = chatEndpoint("https://open.bigmodel.cn/api/paas/v4"),
                    apiKey = App.prefs.glmApiKey,
                    model = m,
                    engineName = "智谱 GLM"
                ) to ("open.bigmodel.cn|" + m)
            }
            TranslationEngine.DOUBAO -> {
                val m = App.prefs.doubaoModel.ifBlank { "doubao-lite-4k" }
                OpenAICompatibleTranslator(
                    endpoint = chatEndpoint("https://ark.cn-beijing.volces.com/api/v3"),
                    apiKey = App.prefs.doubaoApiKey,
                    model = m,
                    engineName = "火山豆包"
                ) to ("ark.cn-beijing.volces.com|" + m)
            }
            TranslationEngine.GOOGLE -> GoogleTranslator() to "translation.googleapis.com"
            // Azure 的区域决定后端节点，换区域等于换服务
            TranslationEngine.MICROSOFT ->
                MicrosoftTranslator() to ("cognitive.microsofttranslator.com|" + App.prefs.msRegion)
            TranslationEngine.DEEPL -> DeepLTranslatorEngine() to "deepl.com"
            TranslationEngine.BAIDU -> BaiduTranslator() to "fanyi-api.baidu.com"
            TranslationEngine.CAIYUN -> CaiyunTranslator() to "api.interpreter.caiyunai.com"
        }
        return CachingTranslator(built.first, engine.key + "|" + built.second)
    }

    /** 取 URL 的 host 作为作用域的一部分；解析失败就退化为原始串 */
    private fun hostOf(baseUrl: String): String =
        runCatching { baseUrl.toHttpUrl().host }.getOrDefault(baseUrl)

    private fun chatEndpoint(baseUrl: String): String =
        OpenAICompatibleTranslator.buildChatEndpoint(baseUrl)
}
