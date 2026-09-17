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

    // ---- v1.17.0 本地大模型（腾讯 Hy-MT2-1.8B，端侧 llama.cpp）----

    /** 量化档 id（见 [com.hunter.screentranslator.util.HyMtQuant]） */
    var hymtQuant: String
        get() = sp.getString(KEY_HYMT_QUANT, "q4_k_m") ?: "q4_k_m"
        set(value) = sp.edit().putString(KEY_HYMT_QUANT, value).apply()

    /** 模型下载源 id（modelscope / huggingface） */
    var hymtSource: String
        get() = sp.getString(KEY_HYMT_SOURCE, "modelscope") ?: "modelscope"
        set(value) = sp.edit().putString(KEY_HYMT_SOURCE, value).apply()

    /** 推理线程数；0 = 自动（核数 - 2，夹在 2~6） */
    var hymtThreads: Int
        get() = sp.getInt(KEY_HYMT_THREADS, 0)
        set(value) = sp.edit().putInt(KEY_HYMT_THREADS, value.coerceIn(0, 8)).apply()

    /** 上下文长度（tokens）。屏幕翻译都是短句，2048 足够；调大只会多吃内存 */
    var hymtContext: Int
        get() = sp.getInt(KEY_HYMT_CONTEXT, 2048)
        set(value) = sp.edit().putInt(KEY_HYMT_CONTEXT, value.coerceIn(512, 8192)).apply()

    /** 闲置多少分钟后自动卸载模型；0 = 不卸（模型常驻约 1.5GB 内存） */
    var hymtIdleUnloadMinutes: Int
        get() = sp.getInt(KEY_HYMT_IDLE_UNLOAD, 5)
        set(value) = sp.edit().putInt(KEY_HYMT_IDLE_UNLOAD, value.coerceIn(0, 120)).apply()

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

    /**
     * v1.15.10 迁移标记：修正"画面变化阈值"的错误默认值。
     *
     * 旧默认 8 远高于真实信号（2~5），老用户存下的 8 会让他们永远卡在
     * "只翻译第一段"，且从界面上完全看不出原因。所以升级时**统一重置一次**，
     * 而不是指望用户自己把滑杆拖到 1。
     */
    var migratedV11510: Boolean
        get() = sp.getBoolean(KEY_MIGRATED_V11510, false)
        set(value) = sp.edit().putBoolean(KEY_MIGRATED_V11510, value).apply()

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

    // ==================== v1.15.0：实时屏幕翻译叠层 ====================

    /**
     * 翻译区域（**屏幕绝对坐标**，格式 "left,top,right,bottom"；空串 = 还没框选）。
     *
     * 为什么存成一个字符串而不是四个 Int：这是一个**不可分割的整体**，
     * 拆成四个键会出现"只写成功了一半"的中间态（例如竖屏框好的区域，
     * 旋转后只更新了两个字段），读出来是个畸形矩形。整体读写不存在这个问题。
     *
     * 坐标是屏幕绝对坐标（与 RegionSelectView 的视图坐标一致），
     * 因为框选遮罩铺满全屏且带 FLAG_LAYOUT_IN_SCREEN。
     */
    var liveRoi: String
        get() = sp.getString(KEY_LIVE_ROI, "") ?: ""
        set(value) = sp.edit().putString(KEY_LIVE_ROI, value).apply()

    /**
     * 取帧间隔（毫秒）。越小越跟手，但耗电、耗额度都线性上升。
     *
     * 默认 1000ms 是刻意偏保守的：GBA 这类剧本文本框出现后会停留数秒，
     * 1 秒一轮完全够用；调快到 400ms 只在"菜单翻页很快"时才有意义。
     */
    var liveIntervalMs: Int
        get() = sp.getInt(KEY_LIVE_INTERVAL_MS, 1000).coerceIn(400, 5000)
        set(value) = sp.edit().putInt(KEY_LIVE_INTERVAL_MS, value.coerceIn(400, 5000)).apply()

    /**
     * 画面变化阈值（0~100，越大越迟钝）。低于阈值视为"画面没变"，不发请求。
     *
     * 语义是"**有多少百分比的格子明显变了**"（见 [FrameSignature.diff]），
     * 不是平均亮度差 —— 平均差会被大片不变的背景稀释，导致换了新对白也判不出变化。
     *
     * 典型值：文本框停着不动 / 只有闪烁光标 → 0~1；换成新的一段对白 → 10~40。
     * 默认 8 能把两者干净分开。
     */
    var liveDiffThreshold: Int
        get() = sp.getInt(KEY_LIVE_DIFF_THRESHOLD, DEFAULT_LIVE_DIFF).coerceIn(1, 20)
        set(value) = sp.edit()
            .putInt(KEY_LIVE_DIFF_THRESHOLD, value.coerceIn(1, 20)).apply()

    /**
     * 译文叠层位置微调（dp）。默认 0。
     *
     * 为什么需要它：叠层的窗口坐标与框选遮罩的视图坐标未必同源
     * （不同 ROM 对全屏悬浮窗的原点处理不一致，状态栏/挖孔/导航栏都可能造成
     * 一个**常量偏移**）。与其赌某个 ROM 的规则，不如给用户一个直接能用的校正口
     * —— 看到偏了多少就调回来。v1.15.4 同时去掉了 FLAG_LAYOUT_NO_LIMITS
     * （它会让窗口原点跑到屏幕外，是偏移的经典成因），这里是兜底。
     */
    var liveNudgeX: Int
        get() = sp.getInt(KEY_LIVE_NUDGE_X, 0).coerceIn(-NUDGE_LIMIT_DP, NUDGE_LIMIT_DP)
        set(value) = sp.edit()
            .putInt(KEY_LIVE_NUDGE_X, value.coerceIn(-NUDGE_LIMIT_DP, NUDGE_LIMIT_DP)).apply()

    var liveNudgeY: Int
        get() = sp.getInt(KEY_LIVE_NUDGE_Y, 0).coerceIn(-NUDGE_LIMIT_DP, NUDGE_LIMIT_DP)
        set(value) = sp.edit()
            .putInt(KEY_LIVE_NUDGE_Y, value.coerceIn(-NUDGE_LIMIT_DP, NUDGE_LIMIT_DP)).apply()

    /**
     * 译文放哪：`edge`（默认，贴在选区外侧，**不盖住选区**）/ `cover`（原位覆盖）。
     *
     * v1.15.7 默认改成 `edge`，因为 `cover` 有一个绕不开的代价：
     * **覆盖模式必须每轮把叠层藏起来才能拍到下面的游戏画面**，于是叠层会周期性闪烁
     * （实测"一直在闪，不行"）。贴边模式根本不遮挡选区，因此**不需要隐藏 → 不闪**，
     * 同时还顺手消掉了"截到自己"的整个问题类。
     * 想要原位覆盖（画面更"原生"）的用户可以手动打开，代价如实标注在设置项上。
     */
    var liveOverlayMode: String
        get() = sp.getString(KEY_LIVE_OVERLAY_MODE, LiveOverlayMode.EDGE)
            ?: LiveOverlayMode.EDGE
        set(value) = sp.edit()
            .putString(KEY_LIVE_OVERLAY_MODE, LiveOverlayMode.normalize(value)).apply()

    /**
     * 译文面板背景不透明度：0.15 ~ 1.0（v1.15.19）。
     *
     * 只在**贴边模式**下真正放开 —— 那种模式叠层不遮挡选区，透明多少都不影响取帧。
     * 原位覆盖模式下会强制拉回接近不透明：半透明会让"藏起来/露出来"两帧差异过小，
     * 自捕获校验就失效了（这是 v1.15.4 误报的根因），而且原文会透出来、两边都读不清。
     */
    var liveOverlayAlpha: Float
        get() = sp.getFloat(KEY_LIVE_OVERLAY_ALPHA, 0.95f).coerceIn(0.15f, 1f)
        set(value) = sp.edit()
            .putFloat(KEY_LIVE_OVERLAY_ALPHA, value.coerceIn(0.15f, 1f)).apply()

    /**
     * 译文面板宽度（**屏宽百分比**，0 = 跟随选区）（v1.15.27）。
     *
     * 用"屏宽百分比"而不是 dp：用户想的是"占屏幕多宽"，不是"多少 dp"；
     * 而且换设备后百分比仍然合适，dp 不会。
     * 0 是特殊值 = 跟随选区宽度 —— 默认保持原行为，不动滑杆就完全不变。
     */
    var livePanelWPercent: Int
        get() = sp.getInt(KEY_LIVE_PANEL_W, 0).coerceIn(0, 100)
        set(value) = sp.edit().putInt(KEY_LIVE_PANEL_W, value.coerceIn(0, 100)).apply()

    /** 译文面板高度（屏高百分比，0 = 跟随选区） */
    var livePanelHPercent: Int
        get() = sp.getInt(KEY_LIVE_PANEL_H, 0).coerceIn(0, 40)
        set(value) = sp.edit().putInt(KEY_LIVE_PANEL_H, value.coerceIn(0, 40)).apply()

    /** 译文面板字号缩放：0.6 ~ 1.8（1.0 为默认）（v1.15.19） */
    var liveTextScale: Float
        get() = sp.getFloat(KEY_LIVE_TEXT_SCALE, 1f).coerceIn(0.6f, 1.8f)
        set(value) = sp.edit()
            .putFloat(KEY_LIVE_TEXT_SCALE, value.coerceIn(0.6f, 1.8f)).apply()

    /**
     * 译文叠层是否接收触摸。**默认关**。
     *
     * 开着的话叠层会盖住整个翻译区域并吃掉那里的一切触摸——对手游/模拟器来说
     * 等于把屏幕上一块操作区变哑。默认必须关，否则"能翻译但玩不了"。
     * 需要的用户可以打开，换取"按住叠层看原文"这个手势。
     */
    var liveOverlayTouchable: Boolean
        get() = sp.getBoolean(KEY_LIVE_OVERLAY_TOUCHABLE, false)
        set(value) = sp.edit().putBoolean(KEY_LIVE_OVERLAY_TOUCHABLE, value).apply()

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
        private const val KEY_MIGRATED_V11510 = "migrated_v11510"
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
        // v1.17.0 本地大模型
        private const val KEY_HYMT_QUANT = "hymt_quant"
        private const val KEY_HYMT_SOURCE = "hymt_source"
        private const val KEY_HYMT_THREADS = "hymt_threads"
        private const val KEY_HYMT_CONTEXT = "hymt_context"
        private const val KEY_HYMT_IDLE_UNLOAD = "hymt_idle_unload"
        private const val KEY_CACHE_MAX_ENTRIES = "cache_max_entries"
        private const val KEY_LIVE_ROI = "live_roi"
        private const val KEY_LIVE_INTERVAL_MS = "live_interval_ms"
        private const val KEY_LIVE_DIFF_THRESHOLD = "live_diff_threshold"
        private const val KEY_LIVE_OVERLAY_TOUCHABLE = "live_overlay_touchable"
        private const val KEY_LIVE_NUDGE_X = "live_nudge_x"
        private const val KEY_LIVE_NUDGE_Y = "live_nudge_y"

        /**
         * 位置微调上限（dp）。
         *
         * 原来只有 ±80dp —— 实测**远远不够**：约屏高的 1/10，从对话框挪进下方那片
         * 黑区需要 200dp 以上，用户反馈"80 有点少，很容易就盖住游戏画面"。
         * 现在放到 ±400dp（约屏高的 2/3），配合默认"选余量更大的一侧"，基本够用。
         */
        /**
         * 位置偏移的**存储**上限（dp）。
         *
         * v1.15.13 从 400 提到 2000：拖动是"用户直接摆到想要的位置"，
         * 再拿 400dp 去裁就变成"拖了会被弹回来"（用户反馈"不能随意拖动"）。
         * 真正该做的约束是"别把框丢到屏幕外"，那是摆放时钳制的，不该在存储层卡死。
         */
        const val NUDGE_LIMIT_DP = 2000

        /**
         * 滑杆可调范围（dp）。比存储上限小得多 ——
         * 滑杆是"细调"用的，给 2000 格只会让人拖不准；拖动才是"任意摆位"的正路。
         */
        const val NUDGE_SLIDER_DP = 400

        /**
         * 画面变化阈值的默认值（v1.15.10 重新定标）。
         *
         * **原默认值是 8，定错了。** 真机实测：整句对白换掉时 Δ 只有 2~5，
         * 而噪声（抖动、闪烁光标）是 0 —— 所以正确的分界线在 1 附近，不是 8。
         * 阈值 8 等于"永远判定没变"，用户看到的就是"只翻译第一段、之后停在原文"。
         */
        const val DEFAULT_LIVE_DIFF = 1
        private const val KEY_LIVE_OVERLAY_MODE = "live_overlay_mode"
        private const val KEY_LIVE_OVERLAY_ALPHA = "live_overlay_alpha"
        private const val KEY_LIVE_TEXT_SCALE = "live_text_scale"
        private const val KEY_LIVE_PANEL_W = "live_panel_w"
        private const val KEY_LIVE_PANEL_H = "live_panel_h"
    }
}

/**
 * 译文叠层显示模式（v1.15.7）。
 *
 * 存字符串而不是布尔：将来若要加第三种（比如"左上角固定"）不用改数据格式。
 */
object LiveOverlayMode {
    /** 贴在选区外侧，不遮挡选区 —— 默认，且是唯一不闪的模式 */
    const val EDGE = "edge"
    /** 原位覆盖原文 —— 更接近"原生汉化"的观感，但叠层需周期性隐藏，会闪 */
    const val COVER = "cover"

    fun normalize(v: String?): String = if (v == COVER) COVER else EDGE
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

