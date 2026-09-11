package com.hunter.screentranslator.api

/**
 * 翻译接口抽象。所有翻译引擎实现这个接口。
 * 后续要换模型只需实现一个新类即可。
 */
interface Translator {
    /** 翻译 text 到 targetLang，返回结果或异常 */
    suspend fun translate(text: String, targetLang: String): Result<String>

    /**
     * v1.8.0 图片翻译：把一张图片（JPEG/PNG 字节）直接交给模型，返回译文。
     *
     * 默认实现为"不支持"——只有真正具备视觉能力的引擎才覆盖它。
     * 这样设计是为了**避免静默失败**：视觉能力是逐模型的（例如 qwen3.7-plus 支持
     * 而 qwen-plus / glm-4-flash 不支持），如果不支持却仍把图片塞进请求，
     * 上游要么报错、要么忽略图片只翻译提示词，用户完全无法理解发生了什么。
     */
    suspend fun translateImage(
        imageBytes: ByteArray,
        mimeType: String,
        targetLang: String
    ): Result<String> = Result.failure(
        UnsupportedOperationException("当前翻译引擎不支持图片输入，请在设置里换用支持视觉的模型")
    )
}

/** 源语言："auto" 表示让模型自动识别。 */
const val SOURCE_AUTO = "auto"

/**
 * 翻译引擎枚举。
 *
 * [visionCapable] 表示该引擎**具备图片输入通道**（v1.8.0 图片翻译）。
 *
 * 注：此标记基于 2026-09-10 对 DeepSeek 官方端点（api.deepseek.com）的**实测**：
 * 连 deepseek-v4-flash-vision-exp / deepseek-v4-flash / deepseek-chat 三个模型名
 * 都能正确读图（用"数条纹"这类无法靠猜的图验证通过），且回显 model 均为
 * "deepseek-flash"，说明服务端统一路由、模型名不决定视觉能力。
 *
 * ⚠️ 但**自定义 baseUrl（中转/代理商）的能力无法由本 App 验证** —— 取决于中转方实现。
 * 因此图片翻译失败时，错误提示应引导用户换引擎或换模型，而不是断言"不支持"。
 */
enum class TranslationEngine(
    val key: String,
    val displayName: String,
    val visionCapable: Boolean = false
) {
    DEEPSEEK("deepseek", "DeepSeek（AI）", visionCapable = true),
    OPENAI("openai", "OpenAI GPT（AI）", visionCapable = true),
    CLAUDE("claude", "Claude（AI）", visionCapable = true),
    QWEN("qwen", "通义千问（AI）", visionCapable = true),
    GLM("glm", "智谱 GLM（AI）", visionCapable = true),
    DOUBAO("doubao", "火山豆包（AI）", visionCapable = true),
    GOOGLE("google", "Google 翻译"),
    MICROSOFT("microsoft", "微软翻译"),
    DEEPL("deepl", "DeepL 翻译"),
    BAIDU("baidu", "百度翻译"),
    CAIYUN("caiyun", "彩云小译");

    companion object {
        fun fromKey(key: String): TranslationEngine =
            entries.firstOrNull { it.key == key } ?: DEEPSEEK
    }
}

/**
 * 目标语言显示名映射（通用）
 * 各引擎语言代码有差异，由各 Translator 内部转换。
 */
val LANG_DISPLAY = linkedMapOf(
    "zh" to "中文",
    "en" to "English",
    "ja" to "日本語",
    "ko" to "한국어",
    "fr" to "Français",
    "de" to "Deutsch",
    "es" to "Español",
    "ru" to "Русский"
)
