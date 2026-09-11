package com.hunter.screentranslator.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 偏好设置封装。
 * v1.1.0 新增：划词翻译、悬浮球模式、复制即翻译。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("screen_translator", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_API_KEY, value).apply()

    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL, "https://api.deepseek.com") ?: "https://api.deepseek.com"
        set(value) = sp.edit().putString(KEY_BASE_URL, value).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, "deepseek-chat") ?: "deepseek-chat"
        set(value) = sp.edit().putString(KEY_MODEL, value).apply()

    /** 翻译引擎：deepseek / google / microsoft / deepl */
    var engine: String
        get() = sp.getString(KEY_ENGINE, "deepseek") ?: "deepseek"
        set(value) = sp.edit().putString(KEY_ENGINE, value).apply()

    /** Google Cloud Translation API Key */
    var googleApiKey: String
        get() = sp.getString(KEY_GOOGLE_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_GOOGLE_API_KEY, value).apply()

    /** 微软 Azure Translator 密钥 */
    var msApiKey: String
        get() = sp.getString(KEY_MS_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_MS_API_KEY, value).apply()

    /** 微软区域（如 eastasia、southeastasia；global 可留空）*/
    var msRegion: String
        get() = sp.getString(KEY_MS_REGION, "") ?: ""
        set(value) = sp.edit().putString(KEY_MS_REGION, value).apply()

    /** DeepL Auth Key（Free 计划以 :fx 结尾）*/
    var deeplApiKey: String
        get() = sp.getString(KEY_DEEPL_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_DEEPL_API_KEY, value).apply()

    /** OpenAI：API Key / Base URL / 模型 */
    var openaiApiKey: String
        get() = sp.getString(KEY_OPENAI_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_OPENAI_API_KEY, value).apply()

    var openaiBaseUrl: String
        get() = sp.getString(KEY_OPENAI_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = sp.edit().putString(KEY_OPENAI_BASE_URL, value).apply()

    var openaiModel: String
        get() = sp.getString(KEY_OPENAI_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) = sp.edit().putString(KEY_OPENAI_MODEL, value).apply()

    /** Claude */
    var claudeApiKey: String
        get() = sp.getString(KEY_CLAUDE_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_CLAUDE_API_KEY, value).apply()

    var claudeModel: String
        get() = sp.getString(KEY_CLAUDE_MODEL, "claude-3-5-haiku-20241022") ?: "claude-3-5-haiku-20241022"
        set(value) = sp.edit().putString(KEY_CLAUDE_MODEL, value).apply()

    /** 通义千问 */
    var qwenApiKey: String
        get() = sp.getString(KEY_QWEN_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_QWEN_API_KEY, value).apply()

    var qwenModel: String
        get() = sp.getString(KEY_QWEN_MODEL, "qwen-plus") ?: "qwen-plus"
        set(value) = sp.edit().putString(KEY_QWEN_MODEL, value).apply()

    /** 智谱 GLM */
    var glmApiKey: String
        get() = sp.getString(KEY_GLM_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_GLM_API_KEY, value).apply()

    var glmModel: String
        get() = sp.getString(KEY_GLM_MODEL, "glm-4-flash") ?: "glm-4-flash"
        set(value) = sp.edit().putString(KEY_GLM_MODEL, value).apply()

    /** 火山豆包（模型填推理接入点 ep-xxx）*/
    var doubaoApiKey: String
        get() = sp.getString(KEY_DOUBAO_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_DOUBAO_API_KEY, value).apply()

    var doubaoModel: String
        get() = sp.getString(KEY_DOUBAO_MODEL, "") ?: ""
        set(value) = sp.edit().putString(KEY_DOUBAO_MODEL, value).apply()

    /** 百度翻译：AppID + 密钥 */
    var baiduAppId: String
        get() = sp.getString(KEY_BAIDU_APP_ID, "") ?: ""
        set(value) = sp.edit().putString(KEY_BAIDU_APP_ID, value).apply()

    var baiduKey: String
        get() = sp.getString(KEY_BAIDU_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_BAIDU_KEY, value).apply()

    /** 彩云小译 Token */
    var caiyunToken: String
        get() = sp.getString(KEY_CAIYUN_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_CAIYUN_TOKEN, value).apply()

    /** 目标语言代码：zh / en / ja / ko ... */
    var targetLang: String
        get() = sp.getString(KEY_TARGET_LANG, "zh") ?: "zh"
        set(value) = sp.edit().putString(KEY_TARGET_LANG, value).apply()

    /** 全屏自动翻译（屏幕变化即翻译整个界面）。v1.2.0 起默认关闭——干扰大且费 API。*/
    var autoTranslate: Boolean
        get() = sp.getBoolean(KEY_AUTO_TRANSLATE, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_TRANSLATE, value).apply()

    /** 是否显示悬浮窗（翻译结果面板）*/
    var overlayEnabled: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()

    /** 划词翻译：选中文字即翻译。v1.2.1 起默认关（选区事件易被输入框自动全选误触发）。*/
    var selectionTranslate: Boolean
        get() = sp.getBoolean(KEY_SELECTION_TRANSLATE, false)
        set(value) = sp.edit().putBoolean(KEY_SELECTION_TRANSLATE, value).apply()

    /** 悬浮球模式：拖动悬浮球到文字上松手即翻译该处 */
    var floatingBall: Boolean
        get() = sp.getBoolean(KEY_FLOATING_BALL, true)
        set(value) = sp.edit().putBoolean(KEY_FLOATING_BALL, value).apply()

    /** 复制即翻译：监听剪贴板 */
    var clipboardTranslate: Boolean
        get() = sp.getBoolean(KEY_CLIPBOARD_TRANSLATE, false)
        set(value) = sp.edit().putBoolean(KEY_CLIPBOARD_TRANSLATE, value).apply()

    /** v1.2.0 迁移标记：老版本默认开了全屏自动翻译，升级后统一切到手动模式 */
    var migratedV12: Boolean
        get() = sp.getBoolean(KEY_MIGRATED_V12, false)
        set(value) = sp.edit().putBoolean(KEY_MIGRATED_V12, value).apply()

    /** v1.2.1 迁移标记：划词翻译同样默认改为关闭（选区事件易误触发）*/
    var migratedV121: Boolean
        get() = sp.getBoolean(KEY_MIGRATED_V121, false)
        set(value) = sp.edit().putBoolean(KEY_MIGRATED_V121, value).apply()

    /** 悬浮球透明度：0.2 ~ 1.0 */
    var ballAlpha: Float
        get() = sp.getFloat(KEY_BALL_ALPHA, 0.85f)
        set(value) = sp.edit().putFloat(KEY_BALL_ALPHA, value.coerceIn(0.2f, 1f)).apply()

    /** 悬浮球直径：36 ~ 64 dp */
    var ballSizeDp: Int
        get() = sp.getInt(KEY_BALL_SIZE, 46)
        set(value) = sp.edit().putInt(KEY_BALL_SIZE, value.coerceIn(36, 64)).apply()

    /** 悬浮球颜色：ARGB */
    var ballColor: Int
        get() = sp.getInt(KEY_BALL_COLOR, 0xCC2E7D32.toInt())
        set(value) = sp.edit().putInt(KEY_BALL_COLOR, value).apply()

    /** 翻译结果面板背景透明度：0.4 ~ 1.0（v1.5.1，只影响背景不影响文字清晰度） */
    var panelAlpha: Float
        get() = sp.getFloat(KEY_PANEL_ALPHA, 1f)
        set(value) = sp.edit().putFloat(KEY_PANEL_ALPHA, value.coerceIn(0.4f, 1f)).apply()

    /** 语音识别（ASR）：OpenAI 兼容 /v1/audio/transcriptions，v1.6.0 听视频翻译用 */
    var asrApiKey: String
        get() = sp.getString(KEY_ASR_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_ASR_API_KEY, value).apply()

    var asrBaseUrl: String
        get() = sp.getString(KEY_ASR_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = sp.edit().putString(KEY_ASR_BASE_URL, value).apply()

    var asrModel: String
        get() = sp.getString(KEY_ASR_MODEL, "whisper-1") ?: "whisper-1"
        set(value) = sp.edit().putString(KEY_ASR_MODEL, value).apply()

    /** 语音输入引擎：system（系统 SpeechRecognizer）/ whisper（自建采集+Whisper，v1.7.0） */
    var voiceEngine: String
        get() = sp.getString(KEY_VOICE_ENGINE, "system") ?: "system"
        set(value) = sp.edit().putString(KEY_VOICE_ENGINE, value).apply()

    /** 用户曾开启过无障碍服务（开机时若发现被 ROM 关掉，发通知提醒重开，v1.7.0） */
    var accessibilityEverOn: Boolean
        get() = sp.getBoolean(KEY_ACCESSIBILITY_EVER_ON, false)
        set(value) = sp.edit().putBoolean(KEY_ACCESSIBILITY_EVER_ON, value).apply()

    /**
     * 首次引导是否已完成（v1.13.0）。
     *
     * 引导只在首次启动时自动出现一次：**跳过**与**走完**都置位，避免反复打扰。
     * 想再看一次可从 设置 → 关于与用法 → 「重新查看引导」手动进入。
     */
    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    // ==================== v1.8.0：朗读译文 ====================

    /** 翻译完成后自动朗读译文（默认关——多数场景会打扰） */
    var ttsAutoSpeak: Boolean
        get() = sp.getBoolean(KEY_TTS_AUTO_SPEAK, false)
        set(value) = sp.edit().putBoolean(KEY_TTS_AUTO_SPEAK, value).apply()

    /** 朗读语速：0.5 ~ 2.0（1.0 为正常） */
    var ttsRate: Float
        get() = sp.getFloat(KEY_TTS_RATE, 1.0f).coerceIn(0.5f, 2.0f)
        set(value) = sp.edit().putFloat(KEY_TTS_RATE, value.coerceIn(0.5f, 2.0f)).apply()

    /** 朗读音调：0.5 ~ 2.0（1.0 为正常） */
    var ttsPitch: Float
        get() = sp.getFloat(KEY_TTS_PITCH, 1.0f).coerceIn(0.5f, 2.0f)
        set(value) = sp.edit().putFloat(KEY_TTS_PITCH, value.coerceIn(0.5f, 2.0f)).apply()

    // ==================== v1.9.3：朗读原文 / 译文 ====================

    /**
     * 朗读内容：只读译文 / 只读原文 / 先原文后译文。
     * 存字符串常量（见 [TtsContent]）而不是序号 —— 序号在增删枚举时会错位。
     */
    var ttsContent: String
        get() = TtsContent.normalize(sp.getString(KEY_TTS_CONTENT, TtsContent.TRANSLATED))
        set(value) = sp.edit().putString(KEY_TTS_CONTENT, TtsContent.normalize(value)).apply()

    /** 原文用的语言（内部语言码），供朗读原文时选对发音 */
    var ttsSourceLang: String
        get() = sp.getString(KEY_TTS_SOURCE_LANG, "auto") ?: "auto"
        set(value) = sp.edit().putString(KEY_TTS_SOURCE_LANG, value).apply()

    /**
     * v1.10.0 翻译缓存总开关。
     * 关掉后所有翻译请求直接走网络，用于排查"译文不对是不是缓存串了"这类问题。
     */
    var cacheEnabled: Boolean
        get() = sp.getBoolean(KEY_CACHE_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_CACHE_ENABLED, value).apply()

    /** v1.10.0 缓存条数上限，超出按 LRU 淘汰（TranslationCache 内部夹到 50~2000） */
    var cacheMaxEntries: Int
        get() = sp.getInt(KEY_CACHE_MAX_ENTRIES, 500)
        set(value) = sp.edit().putInt(KEY_CACHE_MAX_ENTRIES, value).apply()

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_ENGINE = "engine"
        private const val KEY_GOOGLE_API_KEY = "google_api_key"
        private const val KEY_MS_API_KEY = "ms_api_key"
        private const val KEY_MS_REGION = "ms_region"
        private const val KEY_DEEPL_API_KEY = "deepl_api_key"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_BASE_URL = "openai_base_url"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_CLAUDE_API_KEY = "claude_api_key"
        private const val KEY_CLAUDE_MODEL = "claude_model"
        private const val KEY_QWEN_API_KEY = "qwen_api_key"
        private const val KEY_QWEN_MODEL = "qwen_model"
        private const val KEY_GLM_API_KEY = "glm_api_key"
        private const val KEY_GLM_MODEL = "glm_model"
        private const val KEY_DOUBAO_API_KEY = "doubao_api_key"
        private const val KEY_DOUBAO_MODEL = "doubao_model"
        private const val KEY_BAIDU_APP_ID = "baidu_app_id"
        private const val KEY_BAIDU_KEY = "baidu_key"
        private const val KEY_CAIYUN_TOKEN = "caiyun_token"
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_AUTO_TRANSLATE = "auto_translate"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_SELECTION_TRANSLATE = "selection_translate"
        private const val KEY_FLOATING_BALL = "floating_ball"
        private const val KEY_CLIPBOARD_TRANSLATE = "clipboard_translate"
        private const val KEY_MIGRATED_V12 = "migrated_v12"
        private const val KEY_MIGRATED_V121 = "migrated_v121"
        private const val KEY_BALL_ALPHA = "ball_alpha"
        private const val KEY_BALL_SIZE = "ball_size"
        private const val KEY_BALL_COLOR = "ball_color"
        private const val KEY_PANEL_ALPHA = "panel_alpha"
        private const val KEY_ASR_API_KEY = "asr_api_key"
        private const val KEY_ASR_BASE_URL = "asr_base_url"
        private const val KEY_ASR_MODEL = "asr_model"
        private const val KEY_VOICE_ENGINE = "voice_engine"
        private const val KEY_ACCESSIBILITY_EVER_ON = "accessibility_ever_on"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_TTS_AUTO_SPEAK = "tts_auto_speak"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_TTS_CONTENT = "tts_content"
        private const val KEY_TTS_SOURCE_LANG = "tts_source_lang"
        private const val KEY_CACHE_ENABLED = "cache_enabled"
        private const val KEY_CACHE_MAX_ENTRIES = "cache_max_entries"
    }
}

/**
 * 朗读内容（v1.9.3）。
 *
 * 存字符串而不是 ordinal —— 序号在枚举增删时会静默错位，导致老用户的设置变成别的项。
 */
object TtsContent {
    /** 只读译文（默认，最常用） */
    const val TRANSLATED = "translated"
    /** 只读原文（用来听外语发音、练听力） */
    const val SOURCE = "source"
    /** 先读原文，再读译文（对照学习） */
    const val BOTH = "both"

    /** 下拉/菜单展示用的顺序与文案 */
    val OPTIONS = listOf(
        TRANSLATED to "只读译文",
        SOURCE to "只读原文",
        BOTH to "原文 + 译文"
    )

    fun displayOf(value: String): String =
        OPTIONS.firstOrNull { it.first == value }?.second ?: "只读译文"

    /** 兼容历史值/脏数据 */
    fun normalize(value: String?): String =
        if (OPTIONS.any { it.first == value }) value!! else TRANSLATED
}

