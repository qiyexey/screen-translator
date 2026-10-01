package com.hunter.screentranslator.api

import com.hunter.screentranslator.App
import com.hunter.screentranslator.util.Glossary
import com.hunter.screentranslator.util.HyMtModelStore
import com.hunter.screentranslator.util.HyMtQuant
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 翻译器工厂：根据用户选择的引擎创建对应的 Translator 实例。
 * 每次调用都读最新偏好，这样切换引擎立即生效。
 *
 * v1.10.0：所有引擎统一在最外层包一层 [CachingTranslator]。
 * 第 7 个调用点（含图片翻译与"测试翻译"按钮）因此一次性获得缓存能力，调用点零改动。
 */
object TranslatorFactory {

    private val bingWeb by lazy { BingWebTranslator() }

    /**
     * 当前引擎。v1.29.0：配了备用引擎（且它已就绪、不同于主引擎）时，
     * 外面再包一层 [FallbackTranslator]，主引擎失败自动改用备用。
     */
    fun current(useCache: Boolean = true): Translator {
        val engine = TranslationEngine.fromKey(App.prefs.engine)
        val primary = build(engine, useCache)
        val fbKey = App.prefs.fallbackEngine
        if (fbKey.isBlank()) return primary
        val fb = TranslationEngine.fromKey(fbKey)
        if (fb == engine || App.prefs.readiness(fb) !is EngineReadiness.Ready) return primary
        return FallbackTranslator(primary, build(fb, useCache), fb.displayName, fb.visionCapable)
    }

    /** 只取主引擎，不带备用（「测试翻译」要测的就是主引擎本身） */
    fun primaryOnly(useCache: Boolean = true): Translator =
        build(TranslationEngine.fromKey(App.prefs.engine), useCache)

    /**
     * v1.29.0 查词用：当前引擎若支持查词（AI 引擎 / 必应），返回不带缓存、不带术语表的裸实例。
     * 装饰层（缓存 / 备用 / 术语表）都不实现 [DictionaryLookup]，所以必须取裸实例。
     */
    fun dictionary(): DictionaryLookup? {
        val engine = TranslationEngine.fromKey(App.prefs.engine)
        if (!engine.supportsDictionary) return null
        return bare(engine, emptyList()).first as? DictionaryLookup
    }

    private fun build(engine: TranslationEngine, useCache: Boolean): Translator {
        val glossary = App.prefs.glossaryEntries()
        val built = bare(engine, glossary)
        // v1.29.0：AI 引擎已经把术语写进提示词；其余引擎在送出前替换原文
        val withGlossary =
            if (glossary.isEmpty() || built.first is OpenAICompatibleTranslator || built.first is ClaudeTranslator) {
                built.first
            } else {
                GlossaryTranslator(built.first, glossary)
            }
        // 术语表改了，旧译文就不该再命中：把术语表指纹并进缓存作用域（没有术语表时不变）
        val fp = Glossary.fingerprint(glossary)
        val scope = engine.key + "|" + built.second + if (fp.isEmpty()) "" else "|$fp"
        return if (useCache) CachingTranslator(withGlossary, scope) else withGlossary
    }

    private fun bare(engine: TranslationEngine, glossary: List<Glossary.Entry>): Pair<Translator, String> {
        // 第二项是「缓存作用域」的细粒度部分（端点 + 模型）。换中转、换模型、换区域后
        // 译文可能不同，不能复用老译文；同名模型挂在不同中转后端上甚至可能是两套服务。
        //
        // v1.20.0：源语言同样是影响译文的维度，但它**不在这里拼**。
        // 它随 [Translator.translate] 的第三个参数逐次传入，由 [CachingTranslator]
        // 并入作用域。放在这里就等于承认"源语言是全局的"，而它其实是每次调用
        // 都可能不同的入参 —— 同一次屏幕翻译里，正文和按钮可能是两种语言。
        return when (engine) {
            TranslationEngine.HYMT_LOCAL -> {
                val q = HyMtQuant.fromId(App.prefs.hymtQuant)
                // 缓存作用域带上模型文件长度：用户若用「导入」换了同名的自定义 gguf，
                // 译文可能不同，不能复用老缓存（与 DeepSeek 那行"换中转/换模型"同理）。
                val len = runCatching { HyMtModelStore.fileFor(q).length() }.getOrDefault(0L)
                HyMtLocalTranslator(q) to ("local|hymt|${q.id}|$len|c${App.prefs.hymtContext}")
            }
            TranslationEngine.DEEPSEEK -> {
                val base = App.prefs.baseUrl.ifBlank { "https://api.deepseek.com" }
                val m = App.prefs.model.ifBlank { "deepseek-chat" }
                OpenAICompatibleTranslator(
                    endpoint = chatEndpoint(base),
                    apiKey = App.prefs.apiKey,
                    model = m,
                    engineName = "DeepSeek",
                    glossary = glossary
                ) to (hostOf(base) + "|" + m)
            }
            TranslationEngine.OPENAI -> {
                val base = App.prefs.openaiBaseUrl.ifBlank { "https://api.openai.com/v1" }
                val m = App.prefs.openaiModel.ifBlank { "gpt-4o-mini" }
                OpenAICompatibleTranslator(
                    endpoint = chatEndpoint(base),
                    apiKey = App.prefs.openaiApiKey,
                    model = m,
                    engineName = "OpenAI",
                    glossary = glossary
                ) to (hostOf(base) + "|" + m)
            }
            TranslationEngine.CLAUDE -> {
                val m = App.prefs.claudeModel.ifBlank { "claude-3-5-haiku-20241022" }
                ClaudeTranslator(glossary = glossary) to ("api.anthropic.com|" + m)
            }
            TranslationEngine.QWEN -> {
                val m = App.prefs.qwenModel.ifBlank { "qwen-plus" }
                OpenAICompatibleTranslator(
                    endpoint = chatEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1"),
                    apiKey = App.prefs.qwenApiKey,
                    model = m,
                    engineName = "通义千问",
                    glossary = glossary
                ) to ("dashscope.aliyuncs.com|" + m)
            }
            TranslationEngine.GLM -> {
                val m = App.prefs.glmModel.ifBlank { "glm-4-flash" }
                OpenAICompatibleTranslator(
                    endpoint = chatEndpoint("https://open.bigmodel.cn/api/paas/v4"),
                    apiKey = App.prefs.glmApiKey,
                    model = m,
                    engineName = "智谱 GLM",
                    glossary = glossary
                ) to ("open.bigmodel.cn|" + m)
            }
            TranslationEngine.DOUBAO -> {
                val m = App.prefs.doubaoModel.ifBlank { "doubao-lite-4k" }
                OpenAICompatibleTranslator(
                    endpoint = chatEndpoint("https://ark.cn-beijing.volces.com/api/v3"),
                    apiKey = App.prefs.doubaoApiKey,
                    model = m,
                    engineName = "火山豆包",
                    glossary = glossary
                ) to ("ark.cn-beijing.volces.com|" + m)
            }
            TranslationEngine.GOOGLE -> GoogleTranslator() to "translation.googleapis.com"
            // Azure 的区域决定后端节点，换区域等于换服务
            TranslationEngine.MICROSOFT ->
                MicrosoftTranslator() to ("cognitive.microsofttranslator.com|" + App.prefs.msRegion)
            TranslationEngine.DEEPL -> DeepLTranslatorEngine() to "deepl.com"
            TranslationEngine.BAIDU -> BaiduTranslator() to "fanyi-api.baidu.com"
            TranslationEngine.CAIYUN -> CaiyunTranslator() to "api.interpreter.caiyunai.com"
            // 免密钥：会话由网页端现场引导，作用域固定（换不了端点）
            TranslationEngine.BING_WEB -> bingWeb to "bing.com/translator"
        }
    }

    /** 取 URL 的 host 作为作用域的一部分；解析失败就退化为原始串 */
    private fun hostOf(baseUrl: String): String =
        runCatching { baseUrl.toHttpUrl().host }.getOrDefault(baseUrl)

    private fun chatEndpoint(baseUrl: String): String =
        OpenAICompatibleTranslator.buildChatEndpoint(baseUrl)
}
