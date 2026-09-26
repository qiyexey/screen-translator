package com.hunter.screentranslator.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hunter.screentranslator.api.SOURCE_AUTO
import com.hunter.screentranslator.api.EngineReadiness
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.api.engineReadiness

/**
 * 偏好设置封装。
 * v1.1.0 新增：划词翻译、悬浮球模式、复制即翻译。
 * v1.18.0：API Key / Token 改为经 AndroidKeyStore 加密后存储（见 [SecretStore]）。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("screen_translator", Context.MODE_PRIVATE)

    /**
     * 密钥专用文件（v1.18.0）。
     *
     * 与 [sp] 分开的理由不是"更好看"，而是**备份排除必须能按文件生效** ——
     * `res/xml/backup_rules.xml` 与 `res/xml/data_extraction_rules.xml` 是按
     * SharedPreferences 文件名排除的。混在同一个文件里就只能"要么全备份、
     * 要么全不备份"，而用户的历史与设置是值得备份的。
     */
    private val spSecret: SharedPreferences =
        context.getSharedPreferences(SECRET_FILE, Context.MODE_PRIVATE)

    /**
     * 读敏感值（v1.18.0）。四级回退，保证升级不丢配置：
     *
     * 1. 密钥文件里的密文 → 解密；
     * 2. 密钥文件里的明文（Keystore 不可用时的降级写入）；
     * 3. 旧文件 `screen_translator.xml` 里的明文（v1.17.0 及以前）；
     * 4. 默认值。
     *
     * 第 2、3 步都不是多余的：第 2 步是 Keystore 不可用时的正常路径，
     * 第 3 步是升级路径 —— 少了它，老用户升上来会看到所有密钥变成空。
     */
    private fun getSecret(key: String, def: String = ""): String {
        spSecret.getString(key, null)?.let { raw ->
            if (!SecretStore.isEncrypted(raw)) return raw
            SecretStore.decrypt(raw)?.let { return it }
            // 解不开（换机 / 清数据）→ 当它不存在，继续往下找
        }
        return sp.getString(key, def) ?: def
    }

    /**
     * 写敏感值（v1.18.0）。
     *
     * 加密不可用时**退回明文写进密钥文件**，而不是拒绝写入 ——
     * 写不进去等于"用户填了密钥但翻译永远失败"，那是更糟的失败模式。
     * 降级这件事通过 [secretStorageDegraded] 暴露给「诊断信息」，不静默假装成功。
     */
    private fun putSecret(key: String, value: String) {
        val enc = SecretStore.encrypt(value)
        spSecret.edit().apply {
            if (enc != null) putString(key, enc) else putString(key, value)
        }.apply()
        // 顺手清掉旧文件里的明文，避免"改了密钥但旧明文还留在那儿"
        if (sp.contains(key)) sp.edit().remove(key).apply()
    }

    /** 密钥存储是否已降级为明文（供「诊断信息」展示，不静默）。 */
    fun secretStorageDegraded(): Boolean = SecretStore.unavailable

    /**
     * 按"偏好键"直接读一个字符串值（v1.26.0）。
     *
     * 用途只有一个：给 [com.hunter.screentranslator.api.engineReadiness] 当取值器。
     * 那套判据要一次判断**所有**引擎是否配好（引导页要展示"当前引擎是否可用"，
     * 语音页要在开录前预检），而各引擎的密钥散在不同字段上；走一遍
     * `if (engine == DEEPSEEK) prefs.apiKey else if (...)` 的写法，
     * 等于把那 12 个分支在三个页面各写一遍。
     *
     * 敏感字段会走 [getSecret]（加密存储 + 旧明文回退），
     * 其余走普通 SharedPreferences —— 判据本身与"存哪儿"无关，但读法必须一致，
     * 否则会出现"设置页显示已配置、引导页说没配置"的矛盾。
     *
     * 未知键返回空串，**不抛异常**：判据函数对未知引擎就应得出"没配"，
     * 而不是把整个页面带崩。
     */
    fun raw(key: String): String =
        if (key in SENSITIVE_KEYS) getSecret(key) else sp.getString(key, "") ?: ""

    fun readiness(engine: TranslationEngine = TranslationEngine.fromKey(this.engine)): EngineReadiness =
        engineReadiness(
            engine,
            localModelReady = engine == TranslationEngine.HYMT_LOCAL &&
                HyMtModelStore.status(HyMtQuant.fromId(hymtQuant)) is HyMtModelStatus.Ready,
            valueOf = ::raw
        )

    /** Compare stored credentials without decrypting them on every captured frame. Never log this snapshot. */
    fun translationConfiguration(): Map<String, Any?> {
        val keys = (SENSITIVE_KEYS - KEY_ASR_API_KEY) + setOf(
            KEY_ENGINE, KEY_BASE_URL, KEY_MODEL, KEY_OPENAI_BASE_URL, KEY_OPENAI_MODEL,
            KEY_CLAUDE_MODEL, KEY_QWEN_MODEL, KEY_GLM_MODEL, KEY_DOUBAO_MODEL,
            KEY_MS_REGION, KEY_SOURCE_LANG, KEY_TARGET_LANG, KEY_HYMT_QUANT,
            KEY_HYMT_CONTEXT, KEY_HYMT_THREADS, KEY_LIVE_ROI
        )
        val normal = sp.all
        val secret = spSecret.all
        return keys.associateWith { secret[it] ?: normal[it] } +
            if (engine == "hymt-local") {
                val file = HyMtModelStore.fileFor(HyMtQuant.fromId(hymtQuant))
                mapOf("local_model_size" to file.length(), "local_model_modified" to file.lastModified())
            } else emptyMap()
    }

    /**
     * 把 v1.17.0 及以前留在旧文件里的明文密钥迁到加密文件（v1.18.0）。
     *
     * 幂等：迁移过的键在旧文件里已被删除，下次进来 `sp.contains` 就是 false。
     * 在 `App.onCreate` 里跑一次。
     *
     * 注意：加密失败（Keystore 不可用）时**保留明文不动**，下次启动再试 ——
     * 而不是"删掉明文但没写进密文"，那等于直接丢密钥。
     */
    fun migrateSecrets() {
        val pending = SENSITIVE_KEYS.filter { sp.contains(it) }
        if (pending.isEmpty()) return
        var moved = 0
        pending.forEach { key ->
            val plain = sp.getString(key, null)
            if (plain.isNullOrEmpty()) {
                sp.edit().remove(key).apply()
                return@forEach
            }
            val enc = SecretStore.encrypt(plain) ?: return@forEach
            spSecret.edit().putString(key, enc).apply()
            sp.edit().remove(key).apply()
            moved++
        }
        if (moved > 0) Log.i(TAG, "migrated $moved plaintext secret(s) to encrypted store")
    }

    /** DeepSeek（默认引擎）API Key —— v1.18.0 起加密存储 */
    var apiKey: String
        get() = getSecret(KEY_API_KEY)
        set(value) = putSecret(KEY_API_KEY, value)

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
        get() = getSecret(KEY_GOOGLE_API_KEY)
        set(value) = putSecret(KEY_GOOGLE_API_KEY, value)

    /** 微软 Azure Translator 密钥 */
    var msApiKey: String
        get() = getSecret(KEY_MS_API_KEY)
        set(value) = putSecret(KEY_MS_API_KEY, value)

    /** 微软区域（如 eastasia、southeastasia；global 可留空）*/
    var msRegion: String
        get() = sp.getString(KEY_MS_REGION, "") ?: ""
        set(value) = sp.edit().putString(KEY_MS_REGION, value).apply()

    /** DeepL Auth Key（Free 计划以 :fx 结尾）*/
    var deeplApiKey: String
        get() = getSecret(KEY_DEEPL_API_KEY)
        set(value) = putSecret(KEY_DEEPL_API_KEY, value)

    /** OpenAI：API Key / Base URL / 模型 */
    var openaiApiKey: String
        get() = getSecret(KEY_OPENAI_API_KEY)
        set(value) = putSecret(KEY_OPENAI_API_KEY, value)

    var openaiBaseUrl: String
        get() = sp.getString(KEY_OPENAI_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = sp.edit().putString(KEY_OPENAI_BASE_URL, value).apply()

    var openaiModel: String
        get() = sp.getString(KEY_OPENAI_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini"
        set(value) = sp.edit().putString(KEY_OPENAI_MODEL, value).apply()

    /** Claude */
    var claudeApiKey: String
        get() = getSecret(KEY_CLAUDE_API_KEY)
        set(value) = putSecret(KEY_CLAUDE_API_KEY, value)

    var claudeModel: String
        get() = sp.getString(KEY_CLAUDE_MODEL, "claude-3-5-haiku-20241022") ?: "claude-3-5-haiku-20241022"
        set(value) = sp.edit().putString(KEY_CLAUDE_MODEL, value).apply()

    /** 通义千问 */
    var qwenApiKey: String
        get() = getSecret(KEY_QWEN_API_KEY)
        set(value) = putSecret(KEY_QWEN_API_KEY, value)

    var qwenModel: String
        get() = sp.getString(KEY_QWEN_MODEL, "qwen-plus") ?: "qwen-plus"
        set(value) = sp.edit().putString(KEY_QWEN_MODEL, value).apply()

    /** 智谱 GLM */
    var glmApiKey: String
        get() = getSecret(KEY_GLM_API_KEY)
        set(value) = putSecret(KEY_GLM_API_KEY, value)

    var glmModel: String
        get() = sp.getString(KEY_GLM_MODEL, "glm-4-flash") ?: "glm-4-flash"
        set(value) = sp.edit().putString(KEY_GLM_MODEL, value).apply()

    /** 火山豆包（模型填推理接入点 ep-xxx）*/
    var doubaoApiKey: String
        get() = getSecret(KEY_DOUBAO_API_KEY)
        set(value) = putSecret(KEY_DOUBAO_API_KEY, value)

    var doubaoModel: String
        get() = sp.getString(KEY_DOUBAO_MODEL, "") ?: ""
        set(value) = sp.edit().putString(KEY_DOUBAO_MODEL, value).apply()

    /** 百度翻译：AppID + 密钥 */
    var baiduAppId: String
        get() = getSecret(KEY_BAIDU_APP_ID)
        set(value) = putSecret(KEY_BAIDU_APP_ID, value)

    var baiduKey: String
        get() = getSecret(KEY_BAIDU_KEY)
        set(value) = putSecret(KEY_BAIDU_KEY, value)

    /** 彩云小译 Token */
    var caiyunToken: String
        get() = getSecret(KEY_CAIYUN_TOKEN)
        set(value) = putSecret(KEY_CAIYUN_TOKEN, value)

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

    /**
     * 源语言代码：`auto`（自动识别）或 zh / en / ja / ko ...
     *
     * v1.20.0：此前源语言是写死的 —— 六家传统机翻（Google/微软/DeepL/百度/Bing/彩云）
     * 都把 from 位硬编码成 auto，五条大模型提示词里也写死了「自动识别原文语言」。
     * 自动识别猜错的代价不小：中日混排会被判成日语、纯英文短句偶尔被判成荷兰语，
     * 而猜错之后目标语言再对也是白搭。这里给用户一个显式的兜底开关。
     *
     * 默认 [SOURCE_AUTO]，行为与 v1.19.0 完全一致 —— 老用户升级后译文不会突然变化。
     */
    var sourceLang: String
        get() = sp.getString(KEY_SOURCE_LANG, SOURCE_AUTO) ?: SOURCE_AUTO
        set(value) = sp.edit().putString(KEY_SOURCE_LANG, value).apply()

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

    /** Whisper 语音识别：OpenAI 兼容 /v1/audio/transcriptions，语音翻译使用。 */
    var asrApiKey: String
        get() = getSecret(KEY_ASR_API_KEY)
        set(value) = putSecret(KEY_ASR_API_KEY, value)

    var asrBaseUrl: String
        get() = sp.getString(KEY_ASR_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = sp.edit().putString(KEY_ASR_BASE_URL, value).apply()

    var asrModel: String
        get() = sp.getString(KEY_ASR_MODEL, "whisper-1") ?: "whisper-1"
        set(value) = sp.edit().putString(KEY_ASR_MODEL, value).apply()

    /**
     * 语音输入方式：system（系统连续听写）/ whisper（自建采集+Whisper）/
     * ime（当前输入法听写，由用户在键盘内选择麦克风）。
     *
     * v1.27.0：空串表示"用户还没选过"，由界面按**本机实际能力**推断
     * （系统听写可用 → system，不可用 → whisper），而不是无脑默认 system。
     *
     * 为什么改：本 App 要对付的典型设备正是"ROM 把识别服务对第三方 App 藏了"
     * 那一类，而这类设备上 system 是**一定失败**的。默认给 system 等于
     * 一进语音页就撞墙，用户看到的是"按下没反应"。
     * 让默认值跟着能力走，第一屏就是可用的。
     */
    var voiceEngine: String
        get() = sp.getString(KEY_VOICE_ENGINE, "") ?: ""
        set(value) = sp.edit().putString(KEY_VOICE_ENGINE, value).apply()

    /** 用户是否显式选过语音引擎（没选过时界面自行推断，见 [voiceEngine]） */
    val voiceEngineChosen: Boolean
        get() = voiceEngine.isNotBlank()

    /**
     * 语音识别的语言（v1.24.0）。
     *
     * 存的是 BCP-47 标签（如 `cmn-Hans-CN` / `ja-JP`），空串表示"还没选过"，
     * 此时界面按系统语言推断默认值。
     *
     * ## 为什么必须记住它
     *
     * 语音识别引擎**必须知道用什么语言模型去解码**，否则就按系统语言硬解 ——
     * 中文系统下说日语会被转成罗马音（`konichiwa` 而不是 `こんにちは`）。
     * 而每句都让用户重选一遍语种是不可接受的，所以把上次的选择持久化：
     * 切到日语后就一直是日语，直到用户主动改回。
     */
    var voiceListenLang: String
        get() = sp.getString(KEY_VOICE_LISTEN_LANG, "") ?: ""
        set(value) = sp.edit().putString(KEY_VOICE_LISTEN_LANG, value).apply()

    /**
     * 图片翻译的呈现方式（v1.25.0）：`overlay` 译文原位覆盖 / `region` 框选翻译。
     *
     * 默认 `overlay` —— 用户对"图片翻译"的期待是"看到的就是译文"，
     * 而不是"下面出现一段不知道对应图上哪里的文字"。
     * 保留 `region` 是因为"只想要某一段的译文"这个诉求真实存在
     * （例如一张长图里只想翻中间那一块），删掉它等于砍掉一条有用的路径。
     *
     * 存字符串而不是布尔：将来若要加第三种（例如"双语对照"）不用改数据格式。
     */
    var imageTranslateMode: String
        get() = ImageTranslateMode.normalize(sp.getString(KEY_IMAGE_MODE, ImageTranslateMode.OVERLAY))
        set(value) = sp.edit().putString(KEY_IMAGE_MODE, ImageTranslateMode.normalize(value)).apply()

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

        /** v1.20.0：源语言。默认 auto，与升级前行为一致 */
        private const val KEY_SOURCE_LANG = "source_lang"
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
        private const val KEY_VOICE_LISTEN_LANG = "voice_listen_lang"
        /** v1.25.0 图片翻译呈现方式（overlay / region） */
        private const val KEY_IMAGE_MODE = "image_translate_mode"
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

        private const val TAG = "Prefs"

        /** 密钥专用 SharedPreferences 文件名。备份规则按这个**文件名**排除，改名前先看 xml/。 */
        private const val SECRET_FILE = "screen_translator_secrets"

        /**
         * 需要加密存储的键（v1.18.0）。
         *
         * 判断标准是"泄漏后会造成实际损失"：各家 API Key / Secret / Token 全部在内。
         * baseUrl / model / 各种开关**不在内** —— 它们不是秘密，加密只会拖慢读写、
         * 增加故障面。
         *
         * 这个集合放在 companion object 的**末尾**：Kotlin 的对象属性按书写顺序初始化，
         * 虽然 KEY_* 都是 `const val`（编译期内联、不产生字段访问，顺序其实无所谓），
         * 但把它写在所有常量之后能避免将来有人把某个 KEY_ 改成普通 val 时踩坑。
         */
        private val SENSITIVE_KEYS = setOf(
            KEY_API_KEY,
            KEY_GOOGLE_API_KEY,
            KEY_MS_API_KEY,
            KEY_DEEPL_API_KEY,
            KEY_OPENAI_API_KEY,
            KEY_CLAUDE_API_KEY,
            KEY_QWEN_API_KEY,
            KEY_GLM_API_KEY,
            KEY_DOUBAO_API_KEY,
            KEY_BAIDU_APP_ID,
            KEY_BAIDU_KEY,
            KEY_CAIYUN_TOKEN,
            KEY_ASR_API_KEY
        )
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
 * 图片翻译的呈现方式（v1.25.0）。
 *
 * 存字符串而不是布尔：将来若要加第三种（例如"双语对照"）不用改数据格式。
 */
object ImageTranslateMode {
    /** 译文盖在原文上（默认）—— 与拍照翻译一致的"看到的就是译文" */
    const val OVERLAY = "overlay"
    /** 拖框选区域，只翻框内，结果在下方文本框（v1.8.0 原始交互） */
    const val REGION = "region"

    fun normalize(v: String?): String = if (v == REGION) REGION else OVERLAY
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

