package com.hunter.screentranslator.api

/**
 * 翻译接口抽象。所有翻译引擎实现这个接口。
 * 后续要换模型只需实现一个新类即可。
 */
interface Translator {
    /**
     * 翻译 [text] 到 [targetLang]，返回结果或异常。
     *
     * v1.20.0 新增 [sourceLang]：原文语言，[SOURCE_AUTO] 表示让引擎自己识别。
     *
     * 为什么必须进签名、而不是让各引擎去读 `App.prefs`：源语言**改变翻译语义**。
     * 同一段 `"の"`，指定 ja 时该原样输出，不指定时可能被判成中文而译成「的」；
     * 反过来同一段中日混排文字，指定源语言与否会得到两种译文。
     * 语义进了引擎，就必须能被调用方看见 —— 否则 [CachingTranslator] 的缓存键
     * 无从包含它，一次 `auto→en` 的缓存会被后来的 `ja→en` 请求命中，用户改了
     * 源语言却拿到旧译文，且毫无提示。
     *
     * 默认值 [SOURCE_AUTO] 让所有既有实现与调用点**无需改动即可编译**，
     * 行为与 v1.19.0 逐字一致。
     */
    suspend fun translate(
        text: String,
        targetLang: String,
        sourceLang: String = SOURCE_AUTO
    ): Result<String>

    /**
     * v1.8.0 图片翻译：把一张图片（JPEG/PNG 字节）直接交给模型，返回译文。
     *
     * 默认实现为"不支持"——只有真正具备视觉能力的引擎才覆盖它。
     * 这样设计是为了**避免静默失败**：视觉能力是逐模型的（例如 qwen3.7-plus 支持
     * 而 qwen-plus / glm-4-flash 不支持），如果不支持却仍把图片塞进请求，
     * 上游要么报错、要么忽略图片只翻译提示词，用户完全无法理解发生了什么。
     *
     * v1.15.0 新增 [hint]：**调用场景的额外说明**（可为 null）。
     * 同一个视觉通道要服务两种差别很大的输入 —— 图片翻译是"手机截图，印刷体、
     * 字号正常"，实时翻译是"模拟器里的复古游戏画面，低分辨率点阵字、有扫描线"。
     * 对后者，一句"这是游戏画面、按短译文处理"能明显压低识别失败率与译文啰嗦度。
     * 不传就完全沿用原来的提示词，所以既有调用点零改动。
     *
     * v1.20.0 新增 [sourceLang]，含义同 [translate]。参数顺序上 [hint] 带默认值
     * 而它排在中间，所以 [sourceLang] 只能追加在尾部 —— 不能插到 hint 前面，
     * 那样 `translateImage(bytes, mime)` 这类只传前两个参的调用虽然仍能编译，
     * 但任何按位置传第三个参的旧调用会把 hint 当成 sourceLang 静默传错。
     */
    suspend fun translateImage(
        imageBytes: ByteArray,
        mimeType: String,
        targetLang: String,
        hint: String? = null,
        sourceLang: String = SOURCE_AUTO
    ): Result<String> = Result.failure(
        UnsupportedOperationException("当前翻译引擎不支持图片输入，请在设置里换用支持视觉的模型")
    )
}

/**
 * 源语言："auto" 表示让引擎自动识别。
 *
 * v1.20.0 起不再是死常量：它是 [Prefs.sourceLang] 的默认值，也是源语言下拉框的第一项。
 */
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
    /**
     * v1.17.0 腾讯 Hy-MT2-1.8B：**唯一完全在本机跑**的引擎（llama.cpp，CPU 推理）。
     *
     * 无密钥、无额度、断网可用；代价是比云端慢、更耗电 —— 定位是离线/隐私兜底，
     * 不是"更快"。实测数据见 FIXES-1.17.0.md。
     *
     * [visionCapable] = false：它是纯文本翻译模型。图片翻译/实时读屏会自动走
     * "本机 OCR 认字 → 本地翻译"那条既有链路（LiveTranslateService、
     * ImageTranslateActivity 均已按 visionCapable 分支）。
     */
    HYMT_LOCAL("hymt-local", "腾讯 Hy-MT2 1.8B（本地·离线）"),
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
    CAIYUN("caiyun", "彩云小译"),

    /**
     * 必应网页端：**免密钥、免费**，但走的是 bing.com 网页自用的非公开接口，
     * 且**只能翻文字**（不接受图片），所以实时屏幕翻译用不了它。
     *
     * v1.18.0 曾把显示名写成「必应网页（免费 · 仅文字 · 随时可能失效）」：
     * 用意是如实说明它是灰区兜底方案、不该被当成可长期依赖的选项 —— 这个判断没错，
     * 但**说明放错了地方**。displayName 会同时出现在三处宽度受限的容器里：
     *  · 设置页 `tvEngineSummary`（"当前：%1$s"，单行）
     *  · 首页 `btnEngineQuick`（"必应网页（…）· 切换或配置"，胶囊按钮）
     *  · 引擎下拉框的条目与选中后的回显框
     * 20 多个字把胶囊按钮撑到换行、把下拉框的回显文字截断，
     * 用户切到这个引擎时先看到的不是"能用了"，而是"框装不下了"（v1.22.0 反馈）。
     *
     * 结论：**列表里的名字只负责"这是谁"，风险说明交给说明性文案。**
     * 因此这里收敛成 "必应网页版"，与 GOOGLE「Google 翻译」、
     * MICROSOFT「微软翻译」保持同一粒度。"免费 / 仅文字 / 随时可能失效"
     * 这些属性改由引擎页的引擎说明区承载（那里有空间写完整的句子）。
     */
    BING_WEB("bingweb", "必应网页版");

    /**
     * 该引擎**无需任何用户配置**就能直接翻译。
     *
     * 只有两个：必应网页版（公共网页接口，免密钥）与本地大模型（离线跑）。
     * 其余引擎都必须由用户填密钥 / AppID / Token。
     *
     * 这个属性存在的意义是给"引导页"和"语音翻译开录前的预检"一个**统一判据** ——
     * 判据散在各处时，新增一个免密钥引擎（例如将来又加一个公开接口）
     * 必然有人忘了同步，表现就是"选了它还被提示去填密钥"。
     * 注意本地模型免密钥、但要判"模型文件是否已下载"，那是 [readyCheck] 的事，
     * 不能靠这个布尔值单独下结论。
     */
    val keyless: Boolean
        get() = this == BING_WEB || this == HYMT_LOCAL

    companion object {
        fun fromKey(key: String): TranslationEngine =
            entries.firstOrNull { it.key == key } ?: DEEPSEEK
    }
}

/**
 * 某个引擎的"是否已可用"判据。
 *
 * 为什么要单独一个函数而不是写在枚举里：判据要读 [Prefs]（各引擎的密钥存在不同字段上），
 * 而 `api` 包不应依赖 `util` 包的具体存储实现 —— 交给调用方把值读出来传进来。
 *
 * 调用方只需传一个 `(key) -> String` 的取值器（一般是 `App.prefs::secretOf`）。
 *
 * 返回三态，而不是一个 Boolean：
 *  - [Ready]：配好了，可以翻译；
 *  - [MissingKey]：需要用户去填密钥 / 下载模型，附一句**可直接展示给用户**的说明；
 *  - 免密钥引擎（必应网页版）永远返回 [Ready]。
 *
 * 为什么不是 Boolean：调用方要的从来不只是"行不行"，而是"不行的话告诉他去做什么"。
 * 只返回 Boolean 会逼着每个调用点各写一遍"那到底缺什么"的判断，
 * 于是同一套规则在引导页、语音页、设置页各有一份，迟早漂移。
 */
sealed interface EngineReadiness {
    /** 可以直接翻译 */
    data object Ready : EngineReadiness

    /**
     * 还不能翻译。[reason] 是给用户看的一句话（指明缺什么、去哪配）。
     * [keyless] 为 true 表示这个引擎本来就不需要密钥（当前只有本地模型走这条，
     * 它缺的是模型文件而不是密钥）。
     */
    data class NotReady(val reason: String) : EngineReadiness
}

/**
 * 判断某引擎当前是否可用。
 *
 * @param engine 当前选中的引擎
 * @param valueOf 按偏好键取名，例如 `{ key -> App.prefs.raw(key) }`；
 *                实现里对未知键返回空串即可。
 */
fun engineReadiness(
    engine: TranslationEngine,
    valueOf: (String) -> String
): EngineReadiness = when (engine) {
    // 公共网页接口，不需要任何凭据
    TranslationEngine.BING_WEB -> EngineReadiness.Ready
    // 本地模型免密钥，但"模型文件在不在"由调用方另行判断（见 OnboardingActivity）
    TranslationEngine.HYMT_LOCAL -> EngineReadiness.Ready
    TranslationEngine.DEEPSEEK ->
        if (valueOf("api_key").isNotBlank()) EngineReadiness.Ready
        else EngineReadiness.NotReady("还没有填 DeepSeek 的 API Key")
    TranslationEngine.OPENAI ->
        if (valueOf("openai_api_key").isNotBlank()) EngineReadiness.Ready
        else EngineReadiness.NotReady("还没有填 OpenAI 的 API Key")
    TranslationEngine.CLAUDE ->
        if (valueOf("claude_api_key").isNotBlank()) EngineReadiness.Ready
        else EngineReadiness.NotReady("还没有填 Claude 的 API Key")
    TranslationEngine.QWEN ->
        if (valueOf("qwen_api_key").isNotBlank()) EngineReadiness.Ready
        else EngineReadiness.NotReady("还没有填通义千问的 API Key")
    TranslationEngine.GLM ->
        if (valueOf("glm_api_key").isNotBlank()) EngineReadiness.Ready
        else EngineReadiness.NotReady("还没有填智谱 GLM 的 API Key")
    TranslationEngine.DOUBAO ->
        if (valueOf("doubao_api_key").isNotBlank()) EngineReadiness.Ready
        else EngineReadiness.NotReady("还没有填火山豆包的 API Key 与模型名")
    TranslationEngine.GOOGLE ->
        if (valueOf("google_api_key").isNotBlank()) EngineReadiness.Ready
        else EngineReadiness.NotReady("还没有填 Google 翻译的 API Key")
    TranslationEngine.MICROSOFT ->
        if (valueOf("ms_api_key").isNotBlank()) EngineReadiness.Ready
        else EngineReadiness.NotReady("还没有填微软翻译的密钥")
    TranslationEngine.DEEPL ->
        if (valueOf("deepl_api_key").isNotBlank()) EngineReadiness.Ready
        else EngineReadiness.NotReady("还没有填 DeepL 的 Auth Key")
    TranslationEngine.BAIDU ->
        if (valueOf("baidu_app_id").isNotBlank()) EngineReadiness.Ready
        else EngineReadiness.NotReady("还没有填百度翻译的 AppID 与密钥")
    TranslationEngine.CAIYUN ->
        if (valueOf("caiyun_token").isNotBlank()) EngineReadiness.Ready
        else EngineReadiness.NotReady("还没有填彩云小译的 Token")
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
