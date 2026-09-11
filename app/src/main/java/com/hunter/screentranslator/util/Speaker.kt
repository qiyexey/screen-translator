package com.hunter.screentranslator.util

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.hunter.screentranslator.App
import java.util.Locale

/**
 * 译文朗读（TTS）封装。
 *
 * 设计要点：
 * 1. **单例**。TextToSpeech 的构造会绑定系统 TTS 服务，开销不小；而它会被悬浮窗服务
 *    反复用到，每次新建会泄漏 TtsService 连接。因此进程内共用一个实例。
 * 2. **异步就绪**。TextToSpeech 初始化是异步的，未就绪时调用 speak() 会静默失败。
 *    这里把请求排队，onInit 回调里补播。
 * 3. **语言按需切换**。目标语言变化时 setLanguage()，切换失败会返回 LANG_MISSING_DATA
 *    或 LANG_NOT_SUPPORTED —— 此时尝试其它引擎，仍不行才降级并如实告知。
 *
 * v1.9.1 修复（用户反馈"提示没有语音引擎，但手机明明装了"）：
 *
 * ① **初始化中永久卡死**：原实现
 *      `if (tts != null) { if (ready) cb(); return }`
 *    当首次初始化仍在异步途中（tts 已赋值、ready 还是 false）时，后续所有调用都
 *    直接 return —— 既不排队也不重试，也不报错。表现就是「第一次之后彻底不出声，
 *    且没有任何提示」。现改为状态机 + 等待者队列。
 *
 * ② **错误提示过早读取**：调用方 `speak()` 之后**立刻**读 `lastInitError`，而初始化
 *    是异步的，此刻读到的往往是「上一轮的残留」或「尚未开始」—— 于是手机明明能用
 *    却弹出"未安装语音引擎"。现改为通过回调上报，只有真正失败才置位。
 *
 * ③ **默认引擎不支持目标语言**：国产 ROM 常见「默认引擎没有中文包，但用户另装了
 *    讯飞/eSpeak」。原实现只会 setLanguage(默认) 然后照念 —— 念出来是错的或无声。
 *    现会自动扫描系统内其它引擎，挑一个支持该语言的。
 *
 * ④ **失败原因不可操作**：原提示只有一句"可能未安装语音引擎"。现区分
 *    「一个引擎都没有」「引擎在但缺语言数据」「服务绑定失败」，各自给出具体操作。
 */
object Speaker {

    private const val TAG = "ScreenTranslator"
    private const val UTTERANCE_ID = "st_translate"

    /** 探测单个候选引擎的绑定超时 */
    private const val PROBE_ONE_TIMEOUT_MS = 1500L
    /** 探测全部候选的总超时上限，防止阻塞过久 */
    private const val PROBE_TOTAL_TIMEOUT_MS = 4000L

    /** 初始化状态机 */
    private enum class State { IDLE, INITIALIZING, READY, FAILED }

    @Volatile private var state = State.IDLE
    private var tts: TextToSpeech? = null
    private var pendingText: String? = null

    /** 与 pendingText 配对的语言码（不能等到播放时才读 Prefs，那样会丢失调用方的意图） */
    private var pendingLang: String? = null

    /**
     * 引擎探测专用线程池。
     * 单线程即可（探测天然串行），且必须常驻 —— 原先每次朗读都 new 一个线程池，
     * 频繁朗读时会反复创建/销毁线程。
     */
    private val probeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-engine-probe").apply { isDaemon = true }
    }

    /** 主线程 Handler：探测完把结果切回主线程再操作 UI / 播放 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 朗读句序号：拼进 utteranceId。
     * 队列连读时若所有句用同一个 id，UtteranceProgressListener 无法区分是哪句
     * 开始/结束（onDone 会重复触发同名 id）。
     */
    private var utterSeq = 0

    /** 初始化进行中挂起的等待者；结束（成功或失败）后统一唤醒 */
    private val waiters = mutableListOf<(Boolean) -> Unit>()

    /** 当前实际使用的引擎包名（诊断用） */
    @Volatile var currentEngine: String? = null
        private set

    /** 初始化失败原因；UI 层可据此给出可操作提示 */
    @Volatile var lastInitError: String? = null
        private set

    /** 上一次 speak() 是否真的入队成功（供 UI 判断是否要提示） */
    @Volatile var lastSpeakFailed = false
        private set

    // ==================== 初始化 ====================

    /**
     * 确保 TTS 已初始化。可重复调用（幂等）。
     *
     * @param onReady 初始化结束后的回调（主线程）。true=就绪，false=失败
     *                （此时 [lastInitError] 有可操作的原因说明）。
     *                若初始化仍在进行中，回调会挂起、结束后执行。
     */
    fun ensureReady(ctx: Context, onReady: ((Boolean) -> Unit)? = null) {
        when (state) {
            State.READY -> onReady?.invoke(true)
            State.FAILED -> onReady?.invoke(false)
            State.INITIALIZING -> onReady?.let { waiters.add(it) }
            State.IDLE -> {
                onReady?.let { waiters.add(it) }
                startInit(ctx.applicationContext)
            }
        }
    }

    private fun startInit(appCtx: Context) {
        state = State.INITIALIZING
        lastInitError = null

        // 探测引擎 —— **仅用于诊断信息，不参与成败判定**。
        // v1.9.2 修复：原实现在 installed.isEmpty() 时直接 failAndNotify 并 return，
        // 把「探测结果为空」当成「没有引擎」的充分证据，连 TextToSpeech 都不构造。
        // 但 Android 11+ 包可见性过滤会让 queryIntentServices 返回空，
        // 于是「查询被过滤」被误判为「手机没有引擎」—— 用户看到的就是那句误报。
        // 正确顺序：先构造，让系统自己回答；探测结果只在真失败时用于解释原因。
        val installed = installedEngines(appCtx)
        if (installed.isEmpty()) {
            Log.w(TAG, "引擎探测为空（可能被包可见性过滤，不代表没有引擎）")
        } else {
            Log.i(TAG, "系统内 TTS 引擎: ${installed.joinToString { it.packageName }}")
        }

        // 优先用系统默认引擎；不指定则走用户选择，最符合预期
        tts = TextToSpeech(appCtx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                bindUtteranceListener()
                currentEngine = runCatching { tts?.defaultEngine }.getOrNull()
                Log.i(TAG, "TTS 就绪，默认引擎=$currentEngine")
                state = State.READY
                lastInitError = null
                drainWaiters(true)
                flushPending()
            } else {
                failAndNotify(explainBindFailure(installed, status))
            }
        }
    }

    private fun bindUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            @Deprecated("deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "TTS 播放出错: $utteranceId")
            }
        })
    }

    /** 绑定失败的归因：区分"引擎被禁/被限制"和"引擎本身有问题" */
    private fun explainBindFailure(
        installed: List<android.content.pm.ServiceInfo>,
        status: Int
    ): String = when {
        // v1.9.2：原实现无条件访问 installed.first()，空列表会抛 NoSuchElementException，
        // 把一次"朗读失败"升级成"应用崩溃"。此处先判空。
        installed.isEmpty() ->
            "语音引擎启动失败（状态 $status）。\n\n" +
                    "系统未返回可用的语音引擎。请检查：\n" +
                    "① 设置→辅助功能→文字转语音，确认已选择并启用一个引擎\n" +
                    "② 若那里已有引擎，可能是缺少包可见性声明（v1.9.2 已修，请升级）\n" +
                    "③ 没有引擎可安装「讯飞语记」或「eSpeak TTS」"

        installed.size > 1 ->
            "语音引擎服务启动失败（状态 $status）。\n\n" +
                    "系统可能限制了后台服务。请到 设置→应用管理，把本应用和使用中的" +
                    "语音引擎都设为「允许后台运行 / 不限制电量」"

        else ->
            "语音引擎启动失败（状态 $status）。\n\n" +
                    "请到 设置→辅助功能→文字转语音 检查「${installed.first().packageName}」是否被禁用"
    }

    private fun failAndNotify(msg: String) {
        Log.e(TAG, msg)
        lastInitError = msg
        state = State.FAILED
        // 关键：置为 null 让下次调用能重新尝试（引擎可能是用户刚装的）
        tts = null
        currentEngine = null
        drainWaiters(false)
    }

    private fun drainWaiters(ok: Boolean) {
        val list = waiters.toList()
        waiters.clear()
        list.forEach { runCatching { it(ok) } }
    }

    /**
     * 播放初始化期间排队的请求。
     *
     * v1.9.2 修复两处：
     * ① **重复朗读**：原实现在这里 `speak(...)`，而 state 可能仍是 INITIALIZING
     *    （drainWaiters 的回调里调用的），于是又重新入队 + 再触发一次 flushPending，
     *    同一段文字被念两遍。现直接走 doSpeak，不回到 speak() 的状态判断。
     * ② **语言被丢弃**：原来固定用 `App.prefs.targetLang`，忽略了调用方传入的语言码
     *    （如设置页试听选的语言和目标语言不一致时会念错语言）。
     */
    private fun flushPending() {
        val text = pendingText ?: return
        val lang = pendingLang
        pendingText = null
        pendingLang = null
        // 语言码缺失时退回当前目标语言（正常流程下不会走到这里）
        doSpeak(text, lang ?: App.prefs.targetLang, null)
    }

    // ==================== 引擎探测 ====================

    /**
     * 枚举系统内已安装的 TTS 引擎服务。
     * 用于诊断——比 TextToSpeech 的 status 状态码信息量大得多。
     */
    fun installedEngines(ctx: Context): List<android.content.pm.ServiceInfo> = runCatching {
        val pm = ctx.packageManager
        pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo }
    }.getOrDefault(emptyList())

    /** 人类可读的引擎清单，给诊断面板用 */
    fun engineSummary(ctx: Context): String {
        val list = installedEngines(ctx)
        if (list.isEmpty()) return "❌ 未检测到任何语音引擎"
        val def = runCatching { tts?.defaultEngine }.getOrNull()
        return buildString {
            append("✅ 检测到 ${list.size} 个语音引擎：\n")
            list.forEach { info ->
                val label = runCatching {
                    info.loadLabel(ctx.packageManager).toString()
                }.getOrDefault(info.packageName)
                val isDefault = info.packageName == def
                append("  · $label")
                if (isDefault) append("（当前使用）")
                append("\n")
            }
        }.trimEnd()
    }

    // ==================== 语言 ====================

    /** 把内部语言码（zh/en/ja…）映射为 TTS Locale */
    fun localeOf(langCode: String): Locale = when (langCode) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.US
        "ja" -> Locale.JAPANESE
        "ko" -> Locale.KOREAN
        "fr" -> Locale.FRENCH
        "de" -> Locale.GERMAN
        "es" -> Locale("es", "ES")
        "ru" -> Locale("ru", "RU")
        else -> Locale.getDefault()
    }

    /**
     * 解析"原文语言"设置（v1.9.3）。
     *
     * `"auto"` 不能简单当成 `Locale.getDefault()` —— 原文通常是外语，而手机系统语言
     * 往往是中文，那样会把英文原文用中文发音念出来。这里按字体判断：
     * 含 CJK 字符 → 中文；否则有假名 → 日语；否则有谚文 → 韩语；其余按拉丁字母 → 英语。
     *
     * 判断不出来时退回 [Locale.getDefault()]（至少能出声）。
     */
    fun resolveSourceLang(setting: String, sample: String): String {
        if (setting != "auto") return setting
        return detectLangOf(sample)
    }

    /** 粗粒度语言识别，只用于给 TTS 选发音，不追求准确率 */
    private fun detectLangOf(text: String): String {
        var hasKana = false
        var hasHangul = false
        var hasHan = false
        var hasLatin = false
        for (ch in text) {
            val code = ch.code
            when {
                code in 0x3040..0x30FF -> hasKana = true      // 平假名/片假名
                code in 0xAC00..0xD7AF -> hasHangul = true    // 谚文
                code in 0x4E00..0x9FFF -> hasHan = true       // 汉字
                code in 0x0041..0x007A -> hasLatin = true     // 拉丁字母
            }
            if (hasKana && hasHangul && hasHan && hasLatin) break
        }
        return when {
            // 只要出现假名就基本可判定日语（日语里也有汉字，所以假名优先级最高）
            hasKana -> "ja"
            hasHangul -> "ko"
            hasHan -> "zh"
            hasLatin -> "en"
            else -> ""   // 交给 localeOf 的默认分支
        }
    }

    /**
     * 当前引擎是否支持 [locale]（供诊断面板使用）。
     * 注意：必须在初始化成功之后调用才有意义。
     */
    fun isLanguageSupported(locale: Locale): Boolean = runCatching {
        tts?.isLanguageAvailable(locale)?.let { it >= TextToSpeech.LANG_AVAILABLE } ?: false
    }.getOrDefault(false)

    /** 设置朗读语言。
     * @return true 表示该语言可用（已切过去）；false 表示系统缺数据/不支持
     */
    fun setLanguage(locale: Locale): Boolean {
        val t = tts ?: return false
        return when (t.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                Log.w(TAG, "TTS 不支持 $locale")
                false
            }
            else -> true
        }
    }

    // ==================== 朗读 ====================

    /**
     * 按用户的「朗读内容」设置朗读原文/译文（v1.9.3）。
     *
     * 三种模式：
     * - 只读译文：默认，最常用
     * - 只读原文：听外语发音
     * - 原文 + 译文：对照学习（两句用 QUEUE_ADD 顺序入队，不会互相打断）
     *
     * 原文与译文可能语言不同，需要分别 setLanguage。TTS 的语言设置在**入队时**生效，
     * 所以顺序是「切原文语言 → 入队原文 → 切译文语言 → 入队译文」。
     *
     * @param source    原文（可能为空——比如视频字幕没抓到原文）
     * @param translated 译文
     */
    fun speakContent(
        ctx: Context,
        source: String,
        translated: String,
        onResult: ((Boolean, String?) -> Unit)? = null
    ) {
        val mode = App.prefs.ttsContent
        val src = source.trim()
        val dst = translated.trim()
        val dstLang = App.prefs.targetLang
        // 原文语言可能是"auto"，按原文内容粗判（用整段原文而不是译文）
        val srcLang = resolveSourceLang(App.prefs.ttsSourceLang, src)

        // 原文为空时自动退化为只读译文（不留"读了半句"的怪状态）
        if (mode != TtsContent.TRANSLATED && src.isEmpty()) {
            if (dst.isEmpty()) {
                onResult?.invoke(false, "没有可朗读的内容")
                return
            }
            speak(ctx, dst, dstLang, onResult)
            return
        }

        when (mode) {
            TtsContent.SOURCE -> {
                if (src.isEmpty()) {
                    onResult?.invoke(false, "没有可朗读的原文")
                    return
                }
                speak(ctx, src, srcLang, onResult)
            }

            TtsContent.BOTH -> {
                if (src.isEmpty() || dst.isEmpty()) {
                    // 只有一半就只读另一半
                    val only = if (dst.isNotEmpty()) dst else src
                    val lang = if (dst.isNotEmpty()) dstLang else srcLang
                    speak(ctx, only, lang, onResult)
                    return
                }
                speakBoth(ctx, src, srcLang, dst, dstLang, onResult)
            }

            else -> {   // TRANSLATED
                if (dst.isEmpty()) {
                    onResult?.invoke(false, "没有可朗读的译文")
                    return
                }
                speak(ctx, dst, dstLang, onResult)
            }
        }
    }

    /**
     * 连读原文 + 译文。
     *
     * 这是唯一需要「两句不同语言」的路径，所以不能复用 speak()（它会 QUEUE_FLUSH）。
     * 做法：等初始化就绪后，按顺序切语言 + 入队（第一句 FLUSH、第二句 ADD）。
     */
    private fun speakBoth(
        ctx: Context,
        src: String,
        srcLang: String,
        dst: String,
        dstLang: String,
        onResult: ((Boolean, String?) -> Unit)?
    ) {
        ensureReady(ctx) { ok ->
            if (!ok) {
                onResult?.invoke(false, lastInitError)
                return@ensureReady
            }
            val srcLocale = localeOf(srcLang)
            val dstLocale = localeOf(dstLang)

            if (setLanguage(srcLocale)) {
                speakNow(src, flush = true)
                // 切到译文语言再追加。若译文语言不可用，保持原文语言续读，
                // 至少能出声（口语化的"两种语言都念"比"只念一半"更符合预期）
                setLanguage(dstLocale)
                speakNow(dst, flush = false)
                onResult?.invoke(!lastSpeakFailed, if (lastSpeakFailed) "朗读失败，请检查语音引擎" else null)
            } else {
                // 原文语言不可用：只念译文，且如实说明
                if (setLanguage(dstLocale)) {
                    speakNow(dst, flush = true)
                    onResult?.invoke(
                        !lastSpeakFailed,
                        if (lastSpeakFailed) "朗读失败，请检查语音引擎"
                        else "系统缺少「$srcLang」语言包，已只朗读译文"
                    )
                } else {
                    tts?.let { runCatching { it.setLanguage(Locale.getDefault()) } }
                    speakNow(src, flush = true)
                    speakNow(dst, flush = false)
                    onResult?.invoke(false, "系统语音引擎缺少「$srcLang」「$dstLang」语言包，已用默认语言朗读")
                }
            }
        }
    }

    /**
     * 朗读译文。未就绪时会排队，就绪后自动补播。
     *
     * @param onResult 可选回调：(是否成功入队, 失败原因)。失败原因可直接展示给用户。
     */
    fun speak(
        ctx: Context,
        text: String,
        langCode: String,
        onResult: ((Boolean, String?) -> Unit)? = null
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            onResult?.invoke(false, "没有可朗读的内容")
            return
        }
        lastSpeakFailed = false

        when (state) {
            State.READY -> doSpeak(clean, langCode, onResult)
            State.FAILED -> {
                // 失败过，重新尝试一次（用户可能刚装了引擎）
                state = State.IDLE
                pendingText = clean
                pendingLang = langCode
                ensureReady(ctx) { ok ->
                    if (ok) flushPending() else onResult?.invoke(false, lastInitError)
                }
            }
            State.INITIALIZING -> {
                pendingText = clean
                pendingLang = langCode
                ensureReady(ctx) { ok ->
                    if (!ok) onResult?.invoke(false, lastInitError)
                }
            }
            State.IDLE -> {
                pendingText = clean
                pendingLang = langCode
                ensureReady(ctx) { ok ->
                    if (ok) flushPending() else onResult?.invoke(false, lastInitError)
                }
            }
        }
    }

    /**
     * 朗读（可能需要在多个引擎间切换）。
     *
     * v1.9.2 修复 ANR：切换引擎要探测候选引擎的绑定情况，每个最多等 1.5 秒。
     * 原实现从 speak() 同步调用，而 speak() 是主线程入口（按钮点击）——
     * 候选多时主线程会被卡住数秒，触发 ANR（输入事件 5 秒无响应即 ANR）。
     * 现把"探测+切换+朗读"整体放到单线程池执行，主线程立即返回。
     */
    private fun doSpeak(clean: String, langCode: String, onResult: ((Boolean, String?) -> Unit)?) {
        val locale = localeOf(langCode)
        if (setLanguage(locale)) {
            // 快路径：当前引擎就支持，直接念（主线程即可，speak() 本身是异步的）
            speakNow(clean)
            onResult?.invoke(
                !lastSpeakFailed,
                if (lastSpeakFailed) "朗读失败，请检查语音引擎是否正常" else null
            )
            return
        }

        // 慢路径：当前引擎不支持该语言，需要探测其它引擎 —— 全部放到后台
        probeExecutor.execute {
            val switched = syncSwitch(locale)
            mainHandler.post {
                if (switched) {
                    speakNow(clean)
                    onResult?.invoke(
                        !lastSpeakFailed,
                        if (lastSpeakFailed) "朗读失败，请检查语音引擎是否正常" else null
                    )
                } else {
                    // 换不了就退回默认语言，如实告知（不假装成功）
                    tts?.let { runCatching { it.setLanguage(Locale.getDefault()) } }
                    Log.w(TAG, "无引擎支持 $locale，降级到系统默认语言")
                    val msg = "系统语音引擎缺少「$langCode」语言包\n" +
                            "已用默认语言朗读。可到 设置→辅助功能→文字转语音 下载该语言数据"
                    speakNow(clean)
                    onResult?.invoke(!lastSpeakFailed, msg)
                }
            }
        }
    }

    /**
     * 换用一个支持 [locale] 的引擎。
     *
     * 场景：国产 ROM 默认引擎无中文包，但用户另装了讯飞/eSpeak —— 此时不该报错，
     * 而应自动用上那个能用的。
     *
     * 注意：`TextToSpeech(ctx, listener, enginePkg)` 的构造与绑定是**异步**的，
     * 构造完立刻调 `isLanguageAvailable()` 会拿到错误结果，所以这里用 CountDownLatch
     * 等 onInit 回调（每个候选最多 1.5 秒）再探测。
     *
     * **本方法会阻塞当前线程**（最长约 [PROBE_TOTAL_TIMEOUT_MS]），
     * 调用方必须保证自己在后台线程 —— 见 [doSpeak] 的慢路径。
     *
     * @return true 表示已切到某个支持该语言的引擎
     */
    private fun syncSwitch(locale: Locale): Boolean {
        val candidates = installedEngines(App.appContext).map { it.packageName }
            .filter { it != currentEngine }
        if (candidates.isEmpty()) {
            Log.i(TAG, "没有其它可切换的引擎（探测为空或只有一个引擎）")
            return false
        }
        // 总超时保护：候选过多时不至于无限期探测下去
        val deadline = System.currentTimeMillis() + PROBE_TOTAL_TIMEOUT_MS
        for (pkg in candidates) {
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "引擎探测总超时，放弃剩余候选")
                break
            }
            if (probeOne(pkg, locale)) return true
        }
        return false
    }

    /** 探测单个引擎：绑定成功且支持该语言则切换过去 */
    private fun probeOne(pkg: String, locale: Locale): Boolean {
        val ctx = App.appContext
        var ok = false
        val latch = java.util.concurrent.CountDownLatch(1)
        val probe = runCatching {
            TextToSpeech(ctx, { status ->
                ok = status == TextToSpeech.SUCCESS
                latch.countDown()
            }, pkg)
        }.getOrNull() ?: return false

        runCatching { latch.await(PROBE_ONE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        if (!ok) {
            runCatching { probe.shutdown() }
            return false
        }
        val supported = runCatching {
            probe.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
        }.getOrDefault(false)
        if (!supported) {
            runCatching { probe.shutdown() }
            return false
        }
        // 这个引擎可用：把它作为主实例
        runCatching { tts?.shutdown() }
        tts = probe
        currentEngine = pkg
        bindUtteranceListener()
        runCatching { probe.setLanguage(locale) }
        Log.i(TAG, "已切换到引擎 $pkg 朗读 $locale")
        return true
    }

    /** 实际入队播放；切语言后立刻 speak 偶尔会被系统丢弃，重试一次 */
    /**
     * 实际入队播放。
     *
     * v1.9.2 修复：原实现在首次失败时既 `postDelayed` 排了一次重试，**又立刻同步
     * 再调一次** —— 等于念两遍，且返回值被第二次调用覆盖，`lastSpeakFailed` 的判断
     * 也跟着失真。现改为：失败就延后重试一次，且只有真正失败才置位错误标记。
     *
     * 注意：`TextToSpeech.speak()` 本身是线程安全的、内部异步执行，
     * 因此从后台线程调用没问题；但 [applyTuning] 的偏好读取要在主线程一致。
     *
     * @param flush true 清空队列重新开始（默认）；false 追加到队列末尾
     *              —— "原文+译文"连读时必须用 false，否则后半段会把前半段打断。
     */
    private fun speakNow(text: String, flush: Boolean = true) {
        val t = tts ?: run {
            lastSpeakFailed = true
            return
        }
        applyTuning()
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val r = runCatching {
            t.speak(text, queueMode, null, UTTERANCE_ID + "_" + utterSeq++)
        }.getOrDefault(TextToSpeech.ERROR)

        if (r == TextToSpeech.ERROR) {
            // 首次失败常见于"引擎刚绑定完还没热"，延后重试一次
            Log.w(TAG, "TTS speak 首次失败，120ms 后重试一次")
            mainHandler.postDelayed({
                val retry = runCatching {
                    tts?.speak(text, queueMode, null, UTTERANCE_ID + "_" + utterSeq++)
                }.getOrDefault(TextToSpeech.ERROR)
                lastSpeakFailed = retry == TextToSpeech.ERROR
                if (lastSpeakFailed) Log.e(TAG, "TTS 重试仍失败（文本长度 ${text.length}）")
            }, 120)
            // 首次失败不立即判定为失败（重试可能成功）
            lastSpeakFailed = false
            return
        }
        lastSpeakFailed = false
    }

    /**
     * 应用语速/音调偏好。
     * 每次朗读前重读一次偏好，用户在设置里改完立即生效，无需重启。
     */
    private fun applyTuning() {
        val t = tts ?: return
        runCatching {
            t.setSpeechRate(App.prefs.ttsRate)
            t.setPitch(App.prefs.ttsPitch)
        }.onFailure { Log.w(TAG, "TTS 参数设置失败: $it") }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun isSpeaking(): Boolean = runCatching { tts?.isSpeaking == true }.getOrDefault(false)

    /** 是否已就绪（供 UI 决定按钮可用态） */
    fun isReady(): Boolean = state == State.READY

    /** 打开系统「文字转语音输出」设置页，让用户装引擎/下语言包 */
    fun openSystemTtsSettings(ctx: Context): Boolean = runCatching {
        val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        true
    }.getOrElse {
        // 部分 ROM 用这个 action
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    /** 进程退出时释放（一般不需要调用，TTS 单例随进程存活） */
    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        currentEngine = null
        state = State.IDLE
        pendingText = null
        pendingLang = null
        waiters.clear()
        runCatching { mainHandler.removeCallbacksAndMessages(null) }
        // 探测线程池是常驻的，这里一并停掉，避免泄漏线程
        runCatching { probeExecutor.shutdownNow() }
    }
}
