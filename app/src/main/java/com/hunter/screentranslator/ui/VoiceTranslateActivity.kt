package com.hunter.screentranslator.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.api.EngineReadiness
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.api.WhisperClient
import com.hunter.screentranslator.databinding.ActivityVoiceTranslateBinding
import com.hunter.screentranslator.service.AudioSegmenter
import com.hunter.screentranslator.util.EdgeToEdge
import com.hunter.screentranslator.util.WavUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 语音输入翻译（v1.6.0，v1.7.0 增强引擎选择，v1.22.0 修"点了没反应"）：
 *
 * - 系统引擎：SpeechRecognizer 连续听写，partial 边说边翻；依赖设备语音服务（GMS/厂商）
 * - Whisper 引擎（v1.7.0）：自建麦克风采集 + 静音切句 + Whisper API 转写，
 *   不依赖任何系统服务，无 GMS 设备（多数国产 ROM）也能用
 *
 * ## v1.22.0 修的两个真机问题
 *
 * 1) **点了「开始」完全没反应**：
 *    `createOnDeviceSpeechRecognizer()` / `createSpeechRecognizer()` 都可能返回
 *    **null**（前者在系统不提供设备端识别时明确返回 null）。原代码把它直接赋给
 *    `recognizer`，随后 `recognizer?.startListening()` 静默 no-op ——
 *    界面却已经切成"正在聆听 / 停止"，于是按钮再也不给你第二次机会。
 *    现在创建失败会立刻回滚按钮状态并给出原因。
 *
 * 2) **按钮有时能按有时不能**：
 *    原来一发现没有可用听写服务就自动弹 AlertDialog，而对话框会吃掉"点外面"
 *    那一下用来关闭自己，用户第一次点「开始」只是关掉了弹窗。
 *    见 activity_voice_translate.xml 里 hintBar 的注释 —— 改成常驻提示条。
 */
class VoiceTranslateActivity : BaseActivity(), RecognitionListener {

    private lateinit var b: ActivityVoiceTranslateBinding
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var restartJob: Job? = null
    private var partialJob: Job? = null

    /**
     * 连续出错次数。
     *
     * `ERROR_CLIENT` 这类错误原来是无条件 300ms 后重启 —— 服务不可用时
     * 就是**每 300ms 一次的无尽重试**，界面永远停在"正在聆听"，
     * 用户既看不到原因也停不下来。现在累计到 [MAX_CONSECUTIVE_ERRORS] 就停手报原因。
     */
    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 3

    /** 当前实际用的是哪一种识别器，只用于诊断展示 */
    private var recognizerKind = "未创建"
    private var usingOnDeviceRecognizer = false

    /**
     * 刚刚已经就"语言包缺失"给过用户引导了。
     *
     * ## 为什么必须有这个开关（v1.22.0 真机踩出来的顺序缺陷）
     *
     * Google 的识别服务在语言包缺失时**固定连发两个错误**：
     *
     *   onError: 该语言没有识别资源包(13)          ← 真正的病因
     *   onError: 客户端错误（服务不可用/未绑定）(5)  ← 会话被关掉后的衍生错误
     *
     * 第一支弹出引导对话框后，第二支（ERROR_CLIENT）立刻走 `stopAll()`，
     * 而 stopAll 会重置状态；在真机上表现为**对话框根本没机会显示**
     * —— 用户点了按钮、什么反应都没有，和"闪退"的观感完全一致。
     *
     * 所以第一个错误处理完就置位，第二个衍生错误直接忽略。
     * 下一次用户主动点「开始」时（[startSystem]）复位。
     */
    private var languagePackAdvised = false

    /**
     * 用系统听写 **Activity** 兜底。
     *
     * SpeechRecognizer 走的是"后台服务"（RecognitionService），很多 ROM 不提供；
     * 但系统往往装了能返回文字的听写 **界面**（Google 语音输入、输入法自带麦克风）。
     * 服务这条路彻底走不通时，把 ACTION_RECOGNIZE_SPEECH 交给 startActivityForResult，
     * 用户说完一句由系统界面把文字回传 —— 至少"能用"，不至于整页功能为零。
     */
    private val speechActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val text = res.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            b.tvHeard.text = text
            translate(text, isFinal = true)
            b.tvStatus.text = "● 已识别并翻译"
        } else {
            b.tvStatus.text = "● 系统语音输入没有返回文字"
        }
    }

    /**
     * ## ★ v1.24.0：语言选择「回归」，但换了一种更克制的形态
     *
     * ### 这条路是怎么绕回来的（三次真机实测的完整认知）
     *
     * **v1.20**：有语言下拉框。用户必须先声明语种，选到没包的语种时
     * 引擎回 12/13 关会话 → 表现为"按钮点一下就弹回"。
     *
     * **v1.23.0**：判断"指定语言"本身是病根，于是**彻底删掉语言选择、完全不下发
     * `EXTRA_LANGUAGE`**。结果引入了**新 bug**：云端识别回落到系统语言 `zh-CN`
     * 去解码，中文声学模型听到日语音节没有假名/汉字输出通道 →
     * **退化成拉丁转写**，用户念「こんにちは」得到 `konichiwa`。
     *
     * **v1.24.0**：真机对照实验定位到真正规律 ——
     *
     * | 下发内容 | SODA 日志 | 实际输出 |
     * |---|---|---|
     * | 不下发（回落系统 zh-CN） | `does not support locale: zh-CN` | `konichiwa`（罗马音，错） |
     * | `ja-JP` | `has not downloaded this pack yet` | `ハロー` / `こんにちは`（假名，对） |
     *
     * 两个关键结论：
     *
     *   1. **12/13 关会话的真正触发条件是"指到引擎根本不认的 locale"**
     *      （典型就是 `zh-CN` —— SODA 只认 `cmn-Hans-CN`），
     *      **而不是"指定了语言"这件事本身**。指定 `ja-JP` 时 SODA 只是
     *      "报 13 说没下载包"，**会话并不中断，云端识别照常返回正确结果**。
     *   2. **必须把语言告诉引擎**，否则它按系统语言硬解，外语一定出罗马音。
     *
     * ### 还试过 Android 14 的官方「多语种自动检测」，但本机用不了
     *
     * `EXTRA_ENABLE_LANGUAGE_DETECTION` + `EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES`
     * 看起来是"任意语种"的正解，但真机 logcat 给出硬约束：
     *
     *   E IntentParsingUtil: Language detection can't be enabled
     *                        when EXTRA_PREFER_OFFLINE is false
     *
     * 即**语言检测是本地（离线）能力**，必须 `EXTRA_PREFER_OFFLINE=true`；
     * 而本机 `Found matching installed packs:` 后面是**空的**（一个 SODA 语言包都没装），
     * 强制离线后识别直接不可用。所以这条路在当前设备上走不通 ——
     * 除非用户先去系统里下载语言包（App 无法代劳）。
     *
     * ### 最终方案：粗粒度的目标语种 + 记忆上次选择
     *
     * 既然"引擎必须知道语言"是不可回避的，那就让用户来做这个选择 ——
     * 但把代价降到最低：
     *
     *   - 默认值 = **中文**（[defaultLangIndex] 跟随系统语言，对不上退回中文），
     *     而不是过去那个"顺手写死的 en-US"——那正是最初"点一下弹回"的成因；
     *   - **记忆上次选择**（[App.prefs.voiceListenLang]），切到日语后就一直是日语，
     *     不需要每次重选；
     *   - 文案只说语言名，不暴露 BCP-47（用户不需要知道 `cmn-Hans-CN` 是什么）。
     */
    private val listenLangs = arrayOf(
        "cmn-Hans-CN",  // 中文（简体）—— 不能写 zh-CN，SODA 不认，见下方历史注释
        "en-US",        // 英语
        "ja-JP",        // 日语
        "ko-KR",        // 韩语
        "fr-FR",        // 法语
        "de-DE",        // 德语
        "es-ES",        // 西语
        "ru-RU"         // 俄语
    )
    private val listenLangNames = arrayOf(
        "中文", "英语", "日语", "韩语", "法语", "德语", "西语", "俄语"
    )

    /**
     * 默认识别语言的**下标** = 跟随系统语言（对不上退回中文）。
     *
     * 中文的映射要特别处理：系统 Locale 给的是 `zh-CN` / `zh-Hans-CN`，
     * 而 [listenLangs] 里存的是 SODA 认的 `cmn-Hans-CN` —— 一个是 zh、一个是 cmn，
     * 既不相等也不是前缀关系，所以按 language 兜底也匹配不上，必须显式映射。
     */
    private val defaultLangIndex: Int
        get() {
            val sys = java.util.Locale.getDefault()
            val byFull = listenLangs.indexOfFirst {
                it.equals("${sys.language}-${sys.country}", ignoreCase = true)
            }
            if (byFull >= 0) return byFull
            if (sys.language == "zh") {
                val byChinese = listenLangs.indexOfFirst { it.startsWith("cmn") }
                if (byChinese >= 0) return byChinese
            }
            val byLanguage = listenLangs.indexOfFirst { it.substringBefore('-') == sys.language }
            return if (byLanguage >= 0) byLanguage else 0   // 中文（列表首项，保底）
        }

    /** 当前识别语言下标。初值在 [onCreate] 里从偏好读，读不到才用系统语言。 */
    private var langIndex = 0

    /**
     * 历史注释（仍然关键，别删）：中文在 SODA 里叫 `cmn-Hans-CN`，不叫 `zh-CN`
     *
     * 真机 logcat 里 Google TTS 打印过它认得的语言包清单（节选）：
     *
     *   Loaded languagepack (LP) flag value from latest config:
     *     [en-US3056, ja-JP3056, ko-KR3056, cmn-Hans-CN3056, cmn-Hant-TW3056, ...]
     *   SodaLPDirGenerator: Returning no LP, as MDD does not support locale: zh-CN.
     *   SodaSpeechRecognizer: Failed to get language pack of required locale: error 12
     *
     * `zh-CN` 在 SODA 的表里不存在 —— cmn = 现代标准汉语，Hans = 简体。
     * 但旧版的系统默认识别器接受通用的 `zh-CN`。两种识别器的中文标签
     * 不能混用：设备端用 [listenLangs]，系统默认服务用 [systemLanguageTag]。
     */

    // ===== Whisper 引擎模式（v1.7.0）=====
    private var useWhisper = false
    private var useIme = false
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val whisperMutex = Mutex()   // 转写串行，避免结果乱序

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVoiceTranslateBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.install(this)

        // v1.27.0：用户没显式选过引擎时，按**本机实际能力**推断，而不是无脑默认系统。
        //
        // 本 App 要对付的典型设备恰恰是"ROM 把听写服务对三方 App 藏了"那一类，
        // 这类设备上系统识别是**一定失败**的。默认给它 = 用户一进页面就撞墙，
        // 表现是"按下没反应"。跟着能力走，第一屏就是能用的。
        useIme = App.prefs.voiceEngine == "ime"
        useWhisper = if (App.prefs.voiceEngineChosen) {
            App.prefs.voiceEngine == "whisper"
        } else {
            !hasSystemRecognizer()
        }
        // v1.24.0：优先用「上次选过的语言」，没选过才按系统语言推断。
        // 见 [listenLangs] 上方的长注释：引擎必须知道语言，否则外语会出罗马音。
        langIndex = storedLangIndex()
        refreshEngineUi()

        b.topAppBar.setNavigationOnClickListener { finish() }
        b.topAppBar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_system_speech_guide) {
                showSystemSpeechGuide()
                true
            } else false
        }

        b.btnEngine.setOnClickListener { showInputModeMenu() }

        b.btnImePicker.setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }
        b.btnImeTranslate.setOnClickListener { submitImeInput() }
        b.etImeInput.addTextChangedListener {
            b.imeInputLayout.error = null
            if (!it.isNullOrBlank()) b.tvHeard.text = it.toString()
        }

        // ---- 识别语言下拉框（v1.24.0 回归）----
        // 标签只写语言名，不暴露 BCP-47 标签 —— 用户不需要知道 cmn-Hans-CN 是什么。
        b.spinnerLang.setSimpleItems(listenLangNames)
        setSel(b.spinnerLang, langIndex.coerceIn(0, listenLangs.lastIndex))
        b.spinnerLang.setOnItemClickListener { _, _, pos, _ ->
            if (pos !in listenLangs.indices) return@setOnItemClickListener
            langIndex = pos
            // 记忆选择：切到日语后就一直是日语，不必每次重选。
            App.prefs.voiceListenLang = listenLangs[pos]
            // 系统模式在听的时候换语言必须重启识别：EXTRA_LANGUAGE 是
            // startListening 时一次性读入的，不重启则新语言不生效。
            // Whisper 模式不用重启 —— 它的 langHint 是每段转写时现读的。
            if (listening && !useWhisper) restartListening() else refreshListeningHint()
        }

        b.btnToggle.setOnClickListener { toggle() }

        // v1.22.0：提示条上的「处理」是**用户主动**点的，这时候弹对话框没问题
        // （不吃任何一次点击）。自动弹出的提示一律走 hintBar，不弹窗。
        // v1.26.0：提示条可能是在报"引擎没配"（此时该跳引擎设置）或"听写不可用"
        // （此时该走语音服务处理流程）。同一个按钮服务两种故障，动作必须跟着变，
        // 否则用户点「处理」会进错页面。
        b.btnSpeechFix.setOnClickListener {
            when (speechFixAction) {
                SpeechFix.ENGINE ->
                    startActivity(Intent(this, EngineSettingsActivity::class.java))
                SpeechFix.SPEECH -> showSpeechHelp("手动进入故障处理")
            }
        }

        // 长按引擎按钮随时看诊断 —— 排查"明明装了服务却还是不行"时，
        // 这一份 dump 比猜有用得多（同时也会打到 logcat，tag = VoiceASR）。
        b.btnEngine.setOnLongClickListener {
            showSpeechHelp("手动查看诊断")
            true
        }

        Log.w(TAG, "语音诊断（进页时）:\n" + speechDiagnostics())
    }

    /**
     * 读出「上次选过的识别语言」下标；没存过则按系统语言推断。
     *
     * 存的值是 BCP-47 标签；若列表里已经没有它了（比如后续版本删了某语言），
     * 就退回 [defaultLangIndex]，避免下标越界。
     */
    private fun storedLangIndex(): Int {
        val saved = App.prefs.voiceListenLang
        if (saved.isNotBlank()) {
            val i = listenLangs.indexOfFirst { it.equals(saved, ignoreCase = true) }
            if (i >= 0) return i
        }
        return defaultLangIndex
    }

    private companion object {
        const val TAG = "VoiceASR"
    }

    /**
     * 系统连续听写是否可用。
     *
     * Android 12+ 把“普通网络识别”与“设备端识别”拆成了两条能力；有些手机
     * [isRecognitionAvailable] 为 false，但仍带可用的 on-device recognizer。
     * 两条都检查，避免把真正的手机内置离线识别误判成不可用。
     */
    private fun hasSystemRecognizer(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(this) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        SpeechRecognizer.isOnDeviceRecognitionAvailable(this))

    private fun hasOnDeviceRecognizer(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

    private fun systemLanguageTag(): String =
        if (listenLangs[langIndex] == "cmn-Hans-CN")
            java.util.Locale.SIMPLIFIED_CHINESE.toLanguageTag()
        else listenLangs[langIndex]

    /**
     * 系统里有没有**语音输入法**（IME）通道。
     *
     * 这条通道和 SpeechRecognizer 是两套东西：IME 不查 SODA 语言包，
     * 所以在"服务在、包没下"的设备上它是唯一立刻能用的识别方式。
     * 用它来决定提示条该推荐"下载语言包"还是"用语音输入法"。
     */
    private fun hasVoiceIme(): Boolean {
        val imes = runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager)
                .inputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        // Google 语音输入法宿主是 com.google.android.tts；搜狗/讯飞等自带语音。
        return imes.any {
            it == "com.google.android.tts" ||
                    it.contains("sogou", true) ||
                    it.contains("iflytek", true) ||
                    it.contains("wetype", true) ||
                    it.contains("baidu", true)
        }
    }

    /**
     * v1.23.0：**[isLikelyOfflineCapable] 与 [warnIfLangMayBeUnavailable] 已删除**。
     *
     * 这两个方法存在的前提是"用户会指定一个识别语言"，于是需要判断"这个语言
     * 在本机有没有离线模型"并提前给一句提醒（Google 语音服务对没下载模型的语言
     * 会立刻回 ERROR_LANGUAGE_UNAVAILABLE(13) 并关掉会话）。
     *
     * 现在既然不下发 EXTRA_LANGUAGE、识别交给引擎按系统默认语言处理，
     * "预先知道某个语言没包"这件事就无从谈起了 —— 没有"某个语言"这个输入。
     * 真正的兜底仍然是 [onError] 里 12/13 分支弹出的下载引导（那条一定会触发）。
     */

    /**
     * 打开**语言包下载页** —— v1.22.0 真机挖出来的关键一环。
     *
     * ## 为什么"装了 Google 语音服务"还不够
     *
     * 真机（PLJ110 / Android 16 / ColorOS）实测，装完 `com.google.android.tts` 后
     * 系统确实有了 RecognitionService、`voice_recognition_service` 也指向它，
     * 但一点「开始」仍然是：服务被调起来 → 立刻报错 → 会话关闭 → 按钮弹回。
     * logcat 里 Google 服务自己说得很清楚：
     *
     *   SodaLPDirGenerator: Returning no LP, as MDD does not support locale: zh-CN.
     *   SodaSpeechRecognizer: Failed to get language pack of required locale: error 12
     *   → onError LANGUAGE_PACK_ERROR(12)   /  之前 en-US 时是 UNAVAILABLE(13)
     *
     * 也就是说：Google TTS **外壳装好了，但内部 SODA 引擎的语言包一个都没下**。
     * 这是"两段式"依赖 —— 装服务 ≠ 有识别能力，语言包必须单独下载。
     * 而这个下载页在系统设置里没有入口（`VOICE_INPUT_SETTINGS` 查询为空），
     * 只藏在 Google TTS 自己的 Activity 里：
     *
     *   com.google.android.apps.speech.tts.googletts.local.voicepack.ui.VoiceDataInstallActivity
     *
     * （它 **是** exported=true；而名字更像的那个 AddLanguagesActivity 是
     * not exported，第三方 App 启动会 SecurityException —— 两个都试过，只有前者能开。）
     *
     * 所以这里直接按组件名打开它，按顺序回退：语言包页 → TTS 设置 → 键盘设置。
     * 用户的动作因此变成"点一下 → 点下载 → 回来"，而不是自己猜去哪下载。
     */
    private fun openSpeechLanguagePack() {
        val candidates = listOf(
            Intent().setComponent(
                android.content.ComponentName(
                    "com.google.android.tts",
                    "com.google.android.apps.speech.tts.googletts.local.voicepack.ui.VoiceDataInstallActivity"
                )
            ),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        )
        for (intent in candidates) {
            val ok = runCatching { startActivity(intent) }.isSuccess
            if (ok) return
        }
        toast("没有找到语音语言包下载页，请到系统设置 → 语言与输入法里查看")
    }

    /** 打开 ROM 自己的“语音输入 / 助理”设置；没有专页时退回键盘设置。 */
    private fun openSystemVoiceSettings() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isSuccess
        if (!opened) {
            runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                .onFailure { toast("系统没有提供语音输入设置页") }
        }
    }

    /**
     * 打开 Google 的“Speech Recognition & Synthesis”安装页。
     *
     * 这台 ColorOS 设备没有独立语音识别包；Google App 内置的旧服务又在
     * Android 13+ 被 `speech_services_enabled=false` 禁用。安装这个系统组件后，
     * Android SpeechRecognizer 才可能得到面向第三方 App 的 RecognitionService。
     * 优先指定 Google Play，失败再退网页，避免 market:// 被 OPPO 商店截获后搜不到。
     */
    private fun openSpeechServiceStore() {
        val play = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=com.google.android.tts")
        ).setPackage("com.android.vending")
        val opened = runCatching { startActivity(play) }.isSuccess
        if (!opened) {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")
            )
            runCatching { startActivity(web) }
                .onFailure { toast("无法打开商店，请手动安装 Google 语音识别与合成") }
        }
    }

    /**
     * 刷新引擎按钮文字 + 提示条。
     *
     * v1.22.0：**这里不再弹对话框**。见布局里 hintBar 的注释 —— 自动弹的对话框
     * 会吃掉用户第一次点击，表现就是"按钮有时能按有时不能"。
     *
     * v1.21.1 真机结论（PLJ110 / Android 16 / ColorOS）保留备查：
     * 1) Google App 确实声明了 GoogleRecognitionService，但其清单 enabled 指向
     *    bool/speech_services_enabled；解包 APK 后确认该 bool 在 v33+ 配置里为 false，
     *    所以 Android 13+ 上这个服务被 Google 主动禁用。
     * 2) 设备原本没有独立的 Google Speech Recognition & Synthesis 包。
     * 3) HeyTap 小布只暴露 android.speech.action.WEB_SEARCH 助理界面，
     *    没有 RecognitionService，也没有能返回听写文本的 RECOGNIZE_SPEECH Activity。
     *
     * 因而不是"App 不愿意用手机自带服务"，而是要看**当前系统**到底有没有向
     * 第三方 App 开放的听写服务 —— 这一点每次进页都重新读（用户可能刚装完服务回来），
     * 结论直接写在提示条上。
     */
    private fun refreshEngineUi() {
        b.btnEngine.text = when {
            useIme -> "输入方式：输入法"
            useWhisper -> "输入方式：Whisper"
            else -> "输入方式：手机系统"
        }
        b.imeInputPanel.visibility = if (useIme) View.VISIBLE else View.GONE
        if (!listening) {
            b.btnToggle.text = if (useIme) getString(R.string.voice_translate_ime_open)
            else getString(R.string.voice_translate_btn_toggle)
        }

        // v1.26.0：提示条现在服务**两件**可能各自坏掉的事 ——
        // ① 听写（这个系统有没有开放语音识别服务）；② 翻译（引擎配密钥了没有）。
        // 语音翻译是"听写 + 翻译"两段拼起来的，任何一段断了结果都是"说了话没译文"，
        // 但**原因和修法完全不同**。原来只报 ①，用户配了引擎却被提示去装语音服务，
        // 或者反过来，都出现过。
        //
        // 优先级：先报翻译（更容易修、且影响所有翻译功能），再报听写。
        val engine = TranslationEngine.fromKey(App.prefs.engine)
        val engineReady = App.prefs.readiness(engine) is EngineReadiness.Ready
        val noRecognizer = !useWhisper && !useIme && !hasSystemRecognizer()

        b.hintBar.visibility = if (!engineReady || noRecognizer) View.VISIBLE else View.GONE
        when {
            !engineReady -> {
                b.tvSpeechHint.text =
                    "还不能翻译：当前引擎「${engine.displayName}」没有配置，" +
                            "语音能识别但译文这一栏会一直空着。\n" +
                            "两条出路：点右侧「处理」切到免密钥的必应网页版（立刻可用），" +
                            "或去填好这家引擎的密钥。"
                b.btnSpeechFix.text = "去配置"
                speechFixAction = SpeechFix.ENGINE
            }
            else -> {
                // v1.22.0：区分两种"没有服务"——
                // (a) Google TTS 根本没装：让他去装（装完还要下语言包，第二条会说）；
                // (b) 装了但系统没把它选为默认：让他去系统设置选一下。
                // 原来两种情况共用一句"安装语音服务"，对 (b) 的用户是错的引导。
                val ttsInstalled = runCatching {
                    packageManager.getPackageInfo("com.google.android.tts", 0)
                }.isSuccess
                val imeOk = hasVoiceIme()
                b.tvSpeechHint.text = when {
                    // 有语音输入法时不说"没有服务"这种让人放弃的话 —— 明明有能走的路。
                    imeOk ->
                        "系统听写服务当前不可用（多因识别语言包未下载）。" +
                                "可以先用系统语音输入识别：点右侧「处理」→ 选第一项。"
                    ttsInstalled ->
                        "Google 语音服务已安装，但当前系统没有把它用作听写服务。" +
                                "点右侧「处理」下载语言包或到系统设置里选择它。"
                    else ->
                        "当前系统没有向第三方应用开放听写服务，点「开始」不会有反应。" +
                                "点右侧「处理」安装语音服务或改用 Whisper 引擎。"
                }
                b.btnSpeechFix.text = getString(R.string.voice_translate_btn_fix)
                speechFixAction = SpeechFix.SPEECH
            }
        }
    }

    private fun showInputModeMenu() {
        val modes = arrayOf("system", "whisper", "ime")
        val names = arrayOf("手机系统听写", "Whisper 听写", "输入法听写")
        val selected = when {
            useIme -> 2
            useWhisper -> 1
            else -> 0
        }
        PopupMenu(this, b.btnEngine).apply {
            names.forEachIndexed { index, name ->
                menu.add(0, index, index, name).apply {
                    isCheckable = true
                    isChecked = index == selected
                }
            }
            setOnMenuItemClickListener {
                selectInputMode(modes[it.itemId])
                true
            }
            show()
        }
    }

    private fun selectInputMode(mode: String) {
        if (listening) stopAll()
        if (useIme && mode != "ime") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(b.etImeInput.windowToken, 0)
            b.etImeInput.clearFocus()
        }
        useIme = mode == "ime"
        useWhisper = mode == "whisper"
        App.prefs.voiceEngine = mode
        refreshEngineUi()
        if (useIme) openImeInput()
    }

    private fun openImeInput() {
        b.imeInputPanel.visibility = View.VISIBLE
        b.etImeInput.requestFocus()
        b.etImeInput.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(b.etImeInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun submitImeInput() {
        val text = b.etImeInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            b.imeInputLayout.error = getString(R.string.voice_translate_ime_empty)
            b.etImeInput.requestFocus()
            return
        }
        val engine = TranslationEngine.fromKey(App.prefs.engine)
        if (App.prefs.readiness(engine) is EngineReadiness.NotReady) {
            warnEngineNotReady(engine) { submitImeInput() }
            return
        }
        b.imeInputLayout.error = null
        b.tvHeard.text = text
        b.etImeInput.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.etImeInput.windowToken, 0)
        translate(text, isFinal = true)
    }

    private fun showSystemSpeechGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_translate_system_guide)
            .setMessage(R.string.voice_translate_system_guide_body)
            .setPositiveButton("打开系统语音设置") { _, _ -> openSystemVoiceSettings() }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 提示条右侧那个「处理」按钮当前该做什么（v1.26.0：同一位置服务两种故障） */
    private enum class SpeechFix { SPEECH, ENGINE }

    private var speechFixAction = SpeechFix.SPEECH

    /**
     * 刷新"正在聆听"提示里的语言名（v1.24.0 回归）。
     *
     * 只在 [listening] 时写：不监听时状态行显示的是"点击开始"，
     * 被这行覆盖会让用户以为已经在听了。
     */
    private fun refreshListeningHint() {
        if (!listening) return
        b.tvStatus.text = "● 正在聆听（说 ${listenLangNames[langIndex]}）"
    }

    private fun toggle() {
        if (useIme) {
            openImeInput()
            return
        }
        if (listening) {
            stopAll()
        } else {
            startWithPermission()
        }
    }

    private fun startWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            reallyStart()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            reallyStart()
        } else {
            toast(getString(R.string.voice_translate_t08))
        }
    }

    private fun reallyStart() {
        // v1.26.0：**开录之前**先确认翻译引擎配好了。
        //
        // 原来没有这道预检，用户没配引擎时会走完整个流程 ——
        // 授权麦克风、说一整句话、等识别返回，最后在 [translate] 里收到
        // 「翻译失败：未配置 DeepSeek API Key」。说了半天话才知道用不了，
        // 而且错误出现在"译文"栏里，看起来像是"识别失败"，归因也错。
        //
        // 这道闸门只拦"铁定翻不了"的情况；识别语言包、麦克风权限等
        // 各自有各自的提示，不在这里重复。
        val engine = TranslationEngine.fromKey(App.prefs.engine)
        if (App.prefs.readiness(engine) is EngineReadiness.NotReady) {
            warnEngineNotReady(engine)
            return
        }
        if (useWhisper) startWhisper() else startSystem()
    }

    /**
     * 引擎没配好时的提示（v1.26.0，v1.27.0 加了"一键切必应"）。
     *
     * 用对话框而不是 toast：这里需要给**可执行的出路**，toast 一闪而过、点不了。
     *
     * v1.27.0 起三个按钮，覆盖"立刻能用 / 能长期用 / 先别管"三种意图：
     *  · **改用必应网页版（免密钥）** —— 一键切过去并当场开始聆听。
     *    用户说"翻译完全做不了"时，他想要的是"让它赶紧能用"，
     *    而不是"被导航到另一个设置页去研究"。能一步做完就不要两步。
     *  · 去配置引擎 —— 想长期用正式引擎的人走这条。
     *  · 取消。
     */
    private fun warnEngineNotReady(
        engine: TranslationEngine,
        onReady: () -> Unit = { reallyStart() }
    ) {
        val reason = (App.prefs.readiness(engine) as? EngineReadiness.NotReady)
            ?.reason ?: "翻译引擎还没有配置"
        // 已经就是必应网页版却仍未配好，属于异常情况（它的 keyless=true 恒为 Ready），
        // 这时不该再显示"切到必应"这个按钮。
        val canUseBing = engine != TranslationEngine.BING_WEB

        AlertDialog.Builder(this)
            .setTitle("还不能翻译")
            .setMessage(
                "$reason。\n\n" +
                        "语音翻译分「听写」和「翻译」两步 —— 听写能跑，但翻译这步会失败，" +
                        "所以译文栏一直是空的。\n\n" +
                        if (canUseBing)
                            "最省事：点下面的「改用必应网页版」，它免费且不需要密钥，点完就能继续翻译。\n" +
                                    "缺点：走的是必应网页自用接口，随时可能失效，只适合先用着。\n" +
                                    "想长期稳定：选「去配置引擎」，填一家正式引擎的密钥。"
                        else
                            "请到「翻译引擎与密钥」里检查这个引擎的配置。"
            )
            .setPositiveButton(
                if (canUseBing) "改用必应网页版（免密钥）" else "去配置引擎"
            ) { _, _ ->
                if (canUseBing) {
                    App.prefs.engine = TranslationEngine.BING_WEB.key
                    toast("已切到必应网页版")
                    onReady()
                } else {
                    startActivity(Intent(this, EngineSettingsActivity::class.java))
                }
            }
            .apply {
                if (canUseBing) {
                    setNeutralButton("去配置引擎") { _, _ ->
                        startActivity(Intent(this@VoiceTranslateActivity, EngineSettingsActivity::class.java))
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============================ 系统引擎模式 ============================

    /**
     * 取一个真正能用的识别器。
     *
     * v1.22.0：两条**都可能返回 null**，这是上一版"点了没反应"的根因：
     *  - `createOnDeviceSpeechRecognizer()`：文档明确写"系统不支持设备端识别时返回 null"。
     *    但 `isOnDeviceRecognitionAvailable()` 判的是**能力声明**，与能否真的建出实例
     *    不是同一件事 —— 声明了却建不出来是完全可能的。
     *  - `createSpeechRecognizer()`：服务不存在时照样返回对象，但它随后只会回
     *    ERROR_CLIENT。这种"看起来建成功了"的情况由 [onError] 兜底。
     *
     * 优先使用系统默认识别器，与旧版保持一致。设备端识别器只在默认服务
     * 不可用时兜底；它可能要求另行下载语言包，不能仅凭能力声明就抢走默认路径。
     * 返回 false 时调用方必须把界面回滚，绝不能停在"正在聆听"。
     */
    private fun ensureRecognizer(): Boolean {
        if (recognizer != null) return true
        val created = runCatching {
            val default = if (SpeechRecognizer.isRecognitionAvailable(this)) {
                SpeechRecognizer.createSpeechRecognizer(this)
                    ?.also {
                        usingOnDeviceRecognizer = false
                        recognizerKind = "系统默认识别器"
                    }
            } else null
            default ?: if (hasOnDeviceRecognizer()) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    ?.also {
                        usingOnDeviceRecognizer = true
                        recognizerKind = "设备端识别器(默认服务不可用)"
                    }
            } else {
                null
            }
        }.onFailure {
            Log.w(TAG, "创建识别器抛异常: $it")
        }.getOrNull()

        if (created == null) {
            recognizerKind = "创建失败(null)"
            Log.w(TAG, "两种识别器都创建失败；诊断:\n" + speechDiagnostics())
            return false
        }
        created.setRecognitionListener(this)
        recognizer = created
        return true
    }

    private fun startSystem() {
        if (!hasSystemRecognizer()) {
            // v1.22.0：这里只刷新提示条 + toast，**不弹窗**（弹窗会吃掉本次点击）。
            refreshEngineUi()
            toast("系统当前没有可用的听写服务，请看上方提示")
            return
        }
        if (!ensureRecognizer()) {
            // 关键：创建失败要把界面回滚到"未开始"，否则按钮一直停在"停止"，
            // 而底下根本没有识别器在跑 —— 用户再点只是取消一个不存在的会话。
            listening = false
            b.btnToggle.text = getString(R.string.voice_translate_btn_toggle)
            b.tvStatus.text = "● 无法创建语音识别器，请看提示条「处理」"
            showSpeechHelp("创建语音识别器失败")
            return
        }
        consecutiveErrors = 0
        // 用户重新发起一次，给"语言包缺失"的引导重新获得一次机会：
        // 他可能刚去下完包回来，这次该由真实结果说话。
        languagePackAdvised = false
        listening = true
        b.btnToggle.text = getString(R.string.voice_translate_btn_stop)
        refreshListeningHint()
        startListening()
    }

    /**
     * 把"这台机器到底有没有听写能力"一次问清楚。
     *
     * Android 的语音能力散在四处，只看 `isRecognitionAvailable()` 一个布尔值
     * 永远只能得出"不行"、说不出"为什么不行"。这里并列查五项：
     * 能力声明 / RecognitionService 实际有几家 / 听写 Activity 有几家 /
     * 系统当前选中的语音服务 / 录音权限。任何一项异常都能直接定位。
     */
    private fun speechDiagnostics(): String = buildString {
        append("isRecognitionAvailable=")
            .append(SpeechRecognizer.isRecognitionAvailable(this@VoiceTranslateActivity)).append('\n')
        append("isOnDeviceRecognitionAvailable=").append(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this@VoiceTranslateActivity)
            else "N/A(需 Android 12+)"
        ).append('\n')

        val svcIntent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = packageManager.queryIntentServices(svcIntent, 0)
        append("RecognitionService=").append(services.size).append(" 个\n")
        services.forEach {
            append("  · ").append(it.serviceInfo.packageName)
                .append('/').append(it.serviceInfo.name).append('\n')
        }

        val acts = packageManager.queryIntentActivities(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0
        )
        append("RECOGNIZE_SPEECH Activity=").append(acts.size).append(" 个\n")
        acts.forEach { append("  · ").append(it.activityInfo.packageName).append('\n') }

        append("voice_recognition_service=").append(
            runCatching { Settings.Secure.getString(contentResolver, "voice_recognition_service") }
                .getOrNull() ?: "(读不到)"
        ).append('\n')
        append("RECORD_AUDIO=").append(
            ContextCompat.checkSelfPermission(
                this@VoiceTranslateActivity, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ).append('\n')
        // "服务在不在"和"语言包下没下"是两件事，后者才是真机上的实际卡点，
        // 但不能靠代码同步查（SODA 没给公开 API），所以只列出语言包管理器在不在。
        append("Google TTS 包=").append(
            runCatching {
                packageManager.getPackageInfo("com.google.android.tts", 0).versionName
            }.getOrNull() ?: "未安装"
        ).append('\n')
        append("系统语言=").append(java.util.Locale.getDefault().toString()).append('\n')
        // v1.24.0：把"实际下发给引擎的识别语言"也列出来。
        // 排查"外语被识别成罗马音"时，这一行是第一现场 ——
        // 如果它显示的是系统语言而不是用户选的语言，说明 EXTRA_LANGUAGE 丢了。
        append("识别语言=").append(listenLangs.getOrElse(langIndex) { "(下标越界)" })
            .append("（").append(listenLangNames.getOrElse(langIndex) { "?" }).append("）\n")
        append("当前识别器=").append(recognizerKind)
    }

    /**
     * "语言包没下载"的专用出路。
     *
     * 这是整条链路上**唯一真正解决问题**的一步：Google 语音服务要求先下载
     * SODA 语言包才有识别能力，下载页又不在系统设置里（见 openSpeechLanguagePack）。
     * 所以第一项永远是"去下载语言包"，其余两项是绕过这条路。
     *
     * 用 AlertDialog 不算违反"不自动弹窗"的约定 —— 它不是进页就弹，
     * 而是在**用户已经点过开始、并收到明确失败**之后才出现，
     * 此时用户正等着结果，弹窗不会白白吃掉他的点击。
     */
    private fun suggestDownloadLanguage() {
        // v1.22.0 真机结论：这条错误**只有两种出路**。
        //   (a) 真去把语言包下载下来 —— 但下载页不在系统设置里，只能从本 App 跳；
        //   (b) 绕开 RecognitionService，走语音输入法/Whisper。
        // 设备上装了 Google 语音输入法和搜狗，所以 (b) 是立刻可用的，
        // 把它排在前面；用户想彻底修好再选 (a)。
        val imeOk = hasVoiceIme()
        val items: Array<String>
        val subs: Array<String>
        val firstAction: (Int) -> Unit
        if (imeOk) {
            items = arrayOf(
                "① 用系统语音输入识别一次",
                "② 改用 Whisper（不依赖系统服务）",
                "③ 下载识别语言包",
                "④ 复制诊断信息（发给我排查用）"
            )
            subs = arrayOf(
                "立刻可用。绕开语言包这条链路，走手机输入法的语音功能。代价：说完一句要手动回本页。",
                "一劳永逸。不查 Google 语言包，配好地址即可连续听写；自建服务可以免 Key。",
                "根治。下完对应语言的包，系统听写服务才真正可用。需要联网 + Google 账号。",
                "把本机语音能力的完整体检结果复制到剪贴板，可以直接粘贴给开发者。"
            )
            firstAction = { which ->
                when (which) {
                    0 -> launchSpeechActivity()
                    1 -> switchToWhisper()
                    2 -> openSpeechLanguagePack()
                    else -> showSpeechHelp("查看诊断")
                }
            }
        } else {
            items = arrayOf(
                "① 改用 Whisper（不依赖系统服务）",
                "② 下载识别语言包",
                "③ 复制诊断信息（发给我排查用）"
            )
            subs = arrayOf(
                "本机最现实的方案 —— 系统里没有可用的听写服务时它仍然能工作。需要先在设置里配好地址。",
                "下完对应语言的包，系统听写服务才真正可用。需要联网 + Google 账号。",
                "把本机语音能力的完整体检结果复制到剪贴板，可以直接粘贴给开发者。"
            )
            firstAction = { which ->
                when (which) {
                    0 -> switchToWhisper()
                    1 -> openSpeechLanguagePack()
                    else -> showSpeechHelp("查看诊断")
                }
            }
        }

        // ⚠️ 真机踩坑：Material 主题下 `setItems` + `setMessage` 一起用时，
        // 列表项**不会渲染**（对话框只剩标题、正文和按钮，用户没有任何可选项）。
        // 所以这里不用 setItems，改成自己塞一个 LinearLayout 当列表，
        // 每一项就是一个 MaterialButton —— 外观与原生列表项接近，且一定渲染得出来。
        showActionSheet(
            title = "缺少识别语言包",
            message = "Google 语音服务已经装好，但它的识别引擎还需要单独下载语言包；" +
                    "没有语言包时一启动就会被服务端关掉 —— 这就是按钮看起来像闪退的原因。\n\n" +
                    if (imeOk)
                        "最省事：选第一项，用系统语音输入界面识别（说完一句回本页看译文）。\n" +
                                "想一劳永逸：选第二项改用 Whisper，或选第三项去下语言包。"
                    else
                        "本机没有输入法语音通道，只能选第一项改用 Whisper，或选第二项去下语言包。",
            items = items,
            subtitles = subs,
            onPick = firstAction
        )
    }

    /**
     * 自绘的"带若干可选项的对话框"。
     *
     * 为什么不用 `AlertDialog.setItems`：见 [suggestDownloadLanguage] 里的说明 ——
     * 在 Material3 主题下它与 setMessage 共存时列表项不渲染，是个静默失败。
     * 自己画虽然多几行，但渲染结果可预期，也不会随 Material 版本改行为。
     */
    private fun showActionSheet(
        title: String,
        message: String,
        items: Array<String>,
        onPick: (Int) -> Unit,
        // v1.27.0：每项可带一句副标题（"这个选项能解决什么、代价是什么"）。
        //
        // 起因是用户反馈"只给了选项、没有提示，我看不懂该选哪个"：
        // 原来六项全是裸标题（"下载识别语言包"/"安装 Google 语音服务"/…），
        // 用户既不知道当前坏在哪一步，也不知道哪一项最省事，只能挨个试。
        // 副标题就是**把选择所需的信息放在选择旁边**，而不是让人先点开试错。
        subtitles: Array<String>? = null
    ) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        items.forEachIndexed { i, label ->
            val sub = subtitles?.getOrNull(i)
            // 有副标题时用「自绘容器」而不是 MaterialButton：
            // MaterialButton 只接受单行文本，塞两行要靠 HTML 且行距不可控。
            val view: View = if (sub.isNullOrBlank()) {
                com.google.android.material.button.MaterialButton(
                    this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = label
                    isAllCaps = false
                    textSize = 14f
                    minHeight = dp(44)
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                }
            } else {
                android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    // 用主题描边样式取色，暗色模式下跟着变
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(9).toFloat()
                        setStroke(dp(1), resolveCtxColor(com.google.android.material.R.attr.colorOutlineVariant))
                        setColor(resolveCtxColor(android.R.attr.colorBackground))
                    }
                    isClickable = true
                    addView(android.widget.TextView(this@VoiceTranslateActivity).apply {
                        text = label
                        textSize = 14f
                        setTextColor(resolveCtxColor(com.google.android.material.R.attr.colorOnSurface))
                    })
                    addView(android.widget.TextView(this@VoiceTranslateActivity).apply {
                        text = sub
                        textSize = 12f
                        setPadding(0, dp(3), 0, 0)
                        setTextColor(resolveCtxColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    })
                }
            }
            view.layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
                .also { it.topMargin = dp(if (i == 0) 8 else 6) }
            view.setOnClickListener {
                dialogRef?.dismiss()
                onPick(i)
            }
            column.addView(view)
        }
        // ScrollView 只能有一个直接子 View，所以把整列按钮塞进去再交给 setView。
        // 选项多时（最多 6 项）在矮屏上也不会被裁掉。
        val scroll = android.widget.ScrollView(this).apply {
            addView(column, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        dialogRef = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 当前弹出的操作对话框。
     *
     * 自绘列表项时点击要能关掉对话框 —— `setItems` 会自动关，自绘不会，
     * 所以留一个引用显式 dismiss，避免"点了选项、对话框还挂在那里"。
     */
    private var dialogRef: AlertDialog? = null

    /**
     * 从当前主题取一个颜色（v1.27.0）。
     *
     * 用于自绘的对话框选项卡片 —— 硬编码色值在暗色模式下会变成"深底深字"。
     * 取不到就退回透明，至少不会挡住背后的字。
     */
    private fun resolveCtxColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
        } else android.graphics.Color.TRANSPARENT
    }

    /** 切到 Whisper 引擎。三处调用点共用，避免再复制一遍这几行。 */
    private fun switchToWhisper() {
        selectInputMode("whisper")
        toast("已切到 Whisper（需在设置里配好语音识别地址与 Key），点「开始」即可")
    }

    /**
     * 弹出故障处理菜单。只在**用户主动点**或"已确认走不通"时调用，不自动弹。
     *
     * ## v1.27.0 重做：从"一张选项单"改成"一句诊断 + 带说明的方案"
     *
     * 用户原话：「你可能没给出什么提示，别人也不知道怎么弄」。
     * 原来的问题不是信息少，而是**信息的位置错了** ——
     * 诊断详情（识别器有几家、语言包有没有）塞在对话框正文里，又长又技术；
     * 六项选项只有裸标题（"下载识别语言包"/"安装 Google 语音服务"…），
     * 用户读完仍然不知道该点哪个，只能挨个试。
     *
     * 现在拆成两层：
     *   · **正文**只留一句人话结论（当前卡在哪、最省事的一步是哪个）+ 技术详情折叠；
     *   · **每个选项配一句副标题**，写清"能解决什么、有什么代价"。
     *
     * 另外把「改用 Whisper」提到第一位 —— 它是唯一**不依赖系统服务**的方案，
     * 而本类要处理的场景（见 [hasSystemRecognizer]）恰恰就是"系统服务不可用"。
     * 把最可能生效的那条排在最前，比按"知识分类"排更贴近用户此刻的需求。
     */
    private fun showSpeechHelp(reason: String) {
        val imeOk = hasVoiceIme()

        // —— 第一层：一句人话，说清"坏在哪、先试哪个" ——
        val diagnosis = buildString {
            append("系统听写服务不可用")
            if (reason.isNotBlank() && reason != "查看诊断" && reason != "手动查看诊断") {
                append("（$reason）")
            }
            append("。\n\n")
            if (imeOk) {
                append("最省事：选第一项「用系统语音输入」，它绕开出问题的服务，")
                append("说完一句回本页就能看到译文 —— 代价是每句都要点一次。")
            } else {
                append("最省事：选第一项「改用 Whisper」，它完全不依赖系统语音服务。")
                append("需要先在「语音识别」设置里填好服务地址（自建的可以免 Key）。")
            }
            append("\n\n下面是全部可选项，每项都写了能解决什么和代价：")
        }

        // —— 第二层：带副标题的方案列表 ——
        // 顺序 = 从"最可能立刻生效"到"只是看看"，不按知识分类排。
        val labels: Array<String>
        val subs: Array<String>
        val onPick: (Int) -> Unit

        if (imeOk) {
            labels = arrayOf(
                "① 用系统语音输入识别一次",
                "② 改用 Whisper（不依赖系统服务）",
                "③ 下载语音识别语言包",
                "④ 安装 / 更新 Google 语音服务",
                "⑤ 打开系统语音设置",
                "⑥ 复制诊断信息（发给我排查用）"
            )
            subs = arrayOf(
                "立刻可用。绕开出问题的听写服务，走手机输入法的语音功能。代价：说完一句要手动回本页，不能连续听写。",
                "推荐长期方案。需要先在设置里配好语音识别地址；自建服务（faster-whisper / whisper.cpp）可以完全不填 Key。",
                "根治 Google 语音服务这条路。语言包不在系统设置里，只能从这里跳去下载页。需要联网 + Google 账号。",
                "新手机的第一步。装完还要回来选第 ③ 项下语言包，语言包没下时服务也会一启动就退出。",
                "如果系统里已经装了识别服务、只是没被选中，到这里把它设为默认即可。",
                "把本机语音能力的完整体检结果复制到剪贴板，可以直接粘贴给开发者。"
            )
            onPick = { which ->
                when (which) {
                    0 -> launchSpeechActivity()
                    1 -> switchToWhisper()
                    2 -> openSpeechLanguagePack()
                    3 -> openSpeechServiceStore()
                    4 -> openSystemVoiceSettings()
                    else -> copyDiagnostics()
                }
            }
        } else {
            labels = arrayOf(
                "① 改用 Whisper（不依赖系统服务）",
                "② 下载语音识别语言包",
                "③ 安装 / 更新 Google 语音服务",
                "④ 打开系统语音设置",
                "⑤ 复制诊断信息（发给我排查用）"
            )
            subs = arrayOf(
                "本机最现实的方案 —— 系统里没有任何可用的听写服务时，它仍然能工作。需要先在设置里配好地址。",
                "只有装了 Google 语音服务才有意义。语言包不在系统设置里，只能从这里跳去下载页。",
                "新手机的第一步，但本机**没检测到输入法语音通道**，所以装完还要回来下语言包。",
                "如果系统里其实装了识别服务、只是没被选中，到这里把它设为默认即可。",
                "把本机语音能力的完整体检结果复制到剪贴板，可以直接粘贴给开发者。"
            )
            onPick = { which ->
                when (which) {
                    0 -> switchToWhisper()
                    1 -> openSpeechLanguagePack()
                    2 -> openSpeechServiceStore()
                    3 -> openSystemVoiceSettings()
                    else -> copyDiagnostics()
                }
            }
        }

        showActionSheet(
            title = "语音识别不可用",
            // 技术详情仍然给，但不再占据正文首位 —— 用户要的是"我该点哪个"。
            message = diagnosis + "\n\n————\n本机检测详情：\n" + speechDiagnostics(),
            items = labels,
            subtitles = subs,
            onPick = onPick
        )
    }

    /**
     * 兜底：直接拉起系统的听写**界面**。
     *
     * ## 这是本设备上最现实的一条路
     *
     * 真机排查结论（PLJ110 / Android 16 / ColorOS）：
     * SpeechRecognizer 走的是 RecognitionService，而 Google 的
     * `GoogleTTSRecognitionService` 内部是 SODA 引擎，**必须先下载语言包**；
     * 该语言包在系统设置里没有入口、下载器（DownloadActivity）缺参数会静默退出、
     * 且实测设备上语言包目录始终为空 —— 也就是说这条路在用户把语言包下好之前
     * 根本走不通，而这恰恰不是 App 能替他完成的（要 Google 账号 + 联网 + 对应地区）。
     *
     * 但设备上装了两样很有用的东西（`ime list -a` 查出来的）：
     *   · com.google.android.tts/.settings.asr.voiceime.VoiceInputMethodService
     *   · com.sohu.inputmethod.sogouoem/.SogouIME（搜狗自带语音）
     * 这是**另一条通道**：语音输入法（IME），走的是 Activity 而不是服务，
     * 不查 SODA 语言包。所以哪怕 RecognitionService 完全不可用，
     * 用户说完一句也能拿回文字 —— 只是不能连续听写。
     *
     * "能用但需要每句点一次" 比 "整页功能为零" 好得多，所以这条兜底必须留着，
     * 并且在系统识别器不可用时**主动**告诉用户它存在。
     *
     * ## ✅ 已在本机实测跑通（v1.22.0）
     *
     * 点这一项 → 弹出「要允许"Google 语音识别和语音合成"录音吗？」→ 允许后
     * 识别界面出现并成功回传文字：`tvHeard = "哈啰What's your name"`，
     * 状态行变成「● 已识别并翻译」，译文照常产出发送。
     *
     * 这条路径**不经过 SODA、不查语言包**，是目前该设备上唯一可用的识别方式。
     */
    private fun launchSpeechActivity() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // 系统语音输入界面使用通用 BCP-47 标签，中文不使用 SODA 专用标签。
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, systemLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话")
        }
        val opened = runCatching { speechActivityLauncher.launch(intent) }.isSuccess
        if (!opened) toast("没有可打开的系统语音输入界面")
    }

    /** 诊断信息太长，对话框里不好选全，给一个复制出口方便贴出来排查 */
    private fun copyDiagnostics() {
        val text = speechDiagnostics()
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("语音诊断", text))
        }.onSuccess { toast("诊断信息已复制") }
            .onFailure { toast("复制失败：$it") }
    }

    private fun startListening() {
        val r = recognizer
        if (r == null) {
            // v1.22.0：原来是 `recognizer?.startListening()` —— 识别器为 null 时
            // 这行**静默什么都不做**，界面却已经显示"正在聆听"。现在直接说破。
            toast("语音识别器未就绪，请重新点「开始」")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // ★ v1.24.0：**必须把语言告诉引擎**（见 [listenLangs] 上方长注释）。
            //
            // 真机对照实验（同一句日语）：
            //   不下发语言 → `konichiwa`（引擎按系统 zh-CN 硬解，出罗马音）
            //   下发 ja-JP  → `ハロー`     （引擎用日语模型，出正确片假名）
            //
            // 系统默认服务沿用旧版的通用中文标签；只有设备端 SODA 使用
            // cmn-Hans-CN。语言选项仍由用户选择或按系统语言推断。
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                if (usingOnDeviceRecognizer) listenLangs[langIndex] else systemLanguageTag()
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { r.startListening(intent) }
            .onFailure {
                Log.w(TAG, "startListening 抛异常: $it")
                toast("语音识别启动失败：${it.message}")
            }
    }

    private fun restartListening() {
        recognizer?.stopListening()
        startListening()
    }

    // ============================ Whisper 引擎模式（v1.7.0） ============================

    private fun startWhisper() {
        // v1.20.0：原来这里是「没填 Key 就不让开始」。现在改成**提醒而不是拦截** ——
        // 自建/本地的 OpenAI 兼容语音服务（faster-whisper-server、whisper.cpp server 等）
        // 本来就不校验密钥，把用户拦在门外是错的。
        //
        // 但仍然要说一句：这个模式下 baseUrl 指向官方端点却没填 Key 是最常见的配置疏漏，
        // 直接放行会让用户在录完一大段话之后才收到 401，于是先问一次。
        // 用户确认是在用免鉴权的自建服务，就照常继续。
        if (App.prefs.asrApiKey.isBlank()) {
            // v1.20.2：区分两种"没 Key"——
            //   (a) 地址还是 OpenAI 官方默认值：那就是根本没配过，
            //       继续下去必定 401，所以**不提供"继续"**，只给"去配置"。
            //   (b) 地址是自建的：可能是免鉴权服务，允许继续。
            // 之前一律给「继续」，把 (a) 的用户直接送进了 401，体验很差。
            val defaultAsrUrl = "https://api.openai.com/v1"
            val onDefaultUrl = App.prefs.asrBaseUrl.trim().trimEnd('/') == defaultAsrUrl
            // v1.27.0：系统识别可用时，给一个"别配了、换回系统"的出口。
            // 用户点 Whisper 是因为听说它好用，但如果本机系统识别其实是好的，
            // 让他为了用语音翻译去申请一个 ASR 服务是绕远路。
            val sysOk = hasSystemRecognizer()
            val dlg = AlertDialog.Builder(this)
                .setTitle(if (onDefaultUrl) "还没有配置语音识别" else "没有填写语音识别 API Key")
                .setMessage(
                    if (onDefaultUrl)
                        "当前识别地址还是默认的 OpenAI 官方端点：\n$defaultAsrUrl\n\n" +
                                "这个端点必须带 API Key 才能用，而你还没有填。\n\n" +
                                "两条路：\n" +
                                "· 去「语音识别设置」填入 Key，或把地址改成你自己搭的服务" +
                                "（本地 faster-whisper / whisper.cpp 可以完全不用 Key）；\n" +
                                if (sysOk)
                                    "· 或者干脆点「改用系统识别」—— 本机检测到系统听写可用，不用配任何东西。"
                                else
                                    "· 本机检测到系统听写不可用，所以只能走上面这条路。"
                    else
                        "即将连接：\n${App.prefs.asrBaseUrl}\n\n" +
                                "如果是自己搭的免鉴权服务（本地 faster-whisper、whisper.cpp 等），" +
                                "直接继续即可。\n" +
                                "如果这里指的是需要鉴权的服务，请先填入 API Key，否则会收到 401。" +
                                if (sysOk) "\n\n也可以点「改用系统识别」放弃 Whisper，用本机自带的听写。" else ""
                )
                .setNegativeButton("取消", null)

            if (onDefaultUrl) {
                dlg.setPositiveButton("去配置") { _, _ ->
                    startActivity(Intent(this, AsrSettingsActivity::class.java))
                }
            } else {
                dlg.setPositiveButton("继续") { _, _ -> reallyStartWhisper() }
            }
            if (sysOk) {
                dlg.setNeutralButton("改用系统识别") { _, _ ->
                    selectInputMode("system")
                    reallyStart()
                }
            }
            dlg.show()
            return
        }
        reallyStartWhisper()
    }

    private fun reallyStartWhisper() {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 2))
                .build()
        } catch (e: Exception) {
            toast("麦克风初始化失败：${e.message}")
            return
        }

        audioRecord = record
        listening = true
        b.btnToggle.text = getString(R.string.voice_translate_btn_stop)
        b.tvStatus.text = getString(R.string.voice_translate_t05)

        // v1.24.0：Whisper 的 langHint 也按用户选的语言下发（ISO-639-3 前缀）：
        // cmn-Hans-CN → cmn，ja-JP → ja。传空串会让 Whisper 自己做语言检测，
        // 但它对短句检测很不稳，容易猜错并把整段带偏。
        val langHint = listenLangs[langIndex].substringBefore('-')
        val segmenter = AudioSegmenter(sampleRate) { pcm ->
            lifecycleScope.launch { processWhisperSegment(pcm, sampleRate, langHint) }
        }

        captureThread = Thread {
            val chunk = ByteArray(sampleRate / 10 * 2)  // 100ms
            try {
                record.startRecording()
                while (listening) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n > 0) segmenter.feed(chunk, n)
                    else if (n < 0) break
                }
                segmenter.flush()
            } catch (_: Exception) {
            }
        }.apply { start() }
    }

    private suspend fun processWhisperSegment(pcm: ByteArray, sampleRate: Int, langHint: String) {
        whisperMutex.withLock {
            if (!listening) return@withLock
            b.tvStatus.text = getString(R.string.voice_translate_t06)
            val wav = withContext(Dispatchers.Default) { WavUtils.pcmToWav(pcm, sampleRate, 1) }
            val whisper = WhisperClient(App.prefs.asrBaseUrl, App.prefs.asrApiKey, App.prefs.asrModel)
            val text = whisper.transcribe(wav, langHint).getOrElse { e ->
                b.tvStatus.text = "● 转写失败：${e.message?.take(60)}"
                return@withLock
            }
            if (text.isBlank()) {
                b.tvStatus.text = getString(R.string.voice_translate_t05)
                return@withLock
            }
            b.tvHeard.text = text
            b.tvStatus.text = getString(R.string.voice_translate_t04)
            translate(text, isFinal = true)
            b.tvStatus.text = getString(R.string.voice_translate_t05)
        }
    }

    // ============================ 停止 ============================

    private fun stopAll() {
        listening = false
        consecutiveErrors = 0
        restartJob?.cancel()
        partialJob?.cancel()
        runCatching { recognizer?.stopListening() }
        // Whisper 模式：停采集线程并释放麦克风
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        captureThread = null
        b.btnToggle.text = getString(R.string.voice_translate_btn_toggle)
        b.tvStatus.text = getString(R.string.voice_translate_tv_status)
    }

    // ============================ RecognitionListener（系统引擎） ============================

    override fun onReadyForSpeech(params: Bundle?) {
        // 服务绑定成功不等于识别成功；网络错误后重连也会回调这里。
        // 只有真正返回文字时才能清零，否则网络错误会无限重试。
        b.tvStatus.text = "● 正在聆听（说 ${listenLangNames[langIndex]}）"
    }

    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        b.tvStatus.text = getString(R.string.voice_translate_t03)
    }

    /**
     * 错误码 → 中文名。
     *
     * 上一版只往状态行写"出错了（代码 5）"，用户（和看日志的我）根本无从判断
     * 是服务没装、没网、没权限还是语言不支持。名字必须直给。
     */
    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_AUDIO -> "录音失败（麦克风被占用？）"
        SpeechRecognizer.ERROR_SERVER -> "识别服务端错误"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误（服务不可用/未绑定）"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有录音权限"
        // 12 / 13 在真机上其实是同一件事的两种表现：Google TTS 外壳在、
        // 但内部 SODA 引擎缺少对应语言包。见 openSpeechLanguagePack() 的注释。
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "该语言没有识别资源包"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "该语言没有识别资源包"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "识别服务已断开"
        else -> "未知错误(代码 $error)"
    }

    override fun onError(error: Int) {
        val name = errorName(error)
        Log.w(TAG, "onError: $name($error) kind=$recognizerKind 连续=$consecutiveErrors")

        // 语言包缺失时 Google 服务会连发 13 → 5。5 是"会话已被关掉"的衍生物，
        // 不是独立故障；此时用户已经拿到引导，再处理一次只会清掉对话框。
        if (languagePackAdvised &&
            (error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_SERVER ||
                    error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
        ) {
            Log.w(TAG, "忽略语言包错误引发的衍生错误 $name($error)")
            return
        }

        when (error) {
            // 正常的"这一轮没听清"：不算故障，继续听下一轮。
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                consecutiveErrors = 0
                if (listening) scheduleRestart(200)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (listening) scheduleRestart(500)

            // ★★ 真机实测（PLJ110 / Android 16 / ColorOS + Google 语音服务）：
            // 这两个码就是"点一下按钮就弹回开始聆听"的元凶。
            //
            // 因果链（logcat 原文，当时我们还在显式下发 EXTRA_LANGUAGE = zh-CN）：
            //   SodaLPDirGenerator: Returning no LP, as MDD does not support locale: zh-CN.
            //   SodaSpeechRecognizer: Failed to get language pack of required locale: error 12
            //   → Google TTS 立刻回 12，会话随即关闭，按钮瞬间复原 = 用户看到的"闪退"
            //
            // en-US 时报的是 13、zh-CN 时报 12，**本质相同**：外壳服务装了、
            // 系统也选中了它，但内部 SODA 引擎的语言包一个都没下载。
            //
            // v1.23.0：现在已不下发 EXTRA_LANGUAGE，所以不会再出现"我们指了一个
            // 没包的语言"这种自造失败；但**一个包都没下**时（用户从没进过下载页）
            // 引擎仍会回 12/13 —— 这一支必须留着，且要直接给下载入口。
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                stopAll()
                b.tvStatus.text = "● 缺少识别语言包"
                // 置位后再弹：紧跟其后的 ERROR_CLIENT(5) 会被上面的分支忽略掉，
                // 否则它会 stopAll() 把刚弹出的对话框一起带走（真机实测）。
                languagePackAdvised = true
                suggestDownloadLanguage()
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                stopAll()
                b.tvStatus.text = "● $name"
                startWithPermission()
            }

            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_AUDIO -> {
                consecutiveErrors++
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    // 连续失败说明这条路根本走不通，别再 300ms 一次地空转了。
                    stopAll()
                    b.tvStatus.text = "● 识别失败：$name"
                    showSpeechHelp(name)
                } else if (listening) {
                    b.tvStatus.text = "● $name，重试中（$consecutiveErrors/$maxConsecutiveErrors）"
                    scheduleRestart(800)
                }
            }

            else -> {
                b.tvStatus.text = "● $name，已停止"
                stopAll()
            }
        }
    }

    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            consecutiveErrors = 0
            b.tvHeard.text = text
            translate(text, isFinal = true)
        }
        if (listening) scheduleRestart(100)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            b.tvHeard.text = text
            partialJob?.cancel()
            partialJob = lifecycleScope.launch {
                delay(1200)
                translate(text, isFinal = false)
            }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ============================ 翻译 ============================

    private var translating = false

    private fun translate(text: String, isFinal: Boolean) {
        if (translating && !isFinal) return
        translating = true
        lifecycleScope.launch {
            if (!isFinal) b.tvResult.text = getString(R.string.common_t11)
            val result = TranslatorFactory.current().translate(text, App.prefs.targetLang, App.prefs.sourceLang)
            translating = false
            result.fold(
                onSuccess = {
                    b.tvResult.text = it
                    b.scrollResult.post { b.scrollResult.fullScroll(ScrollView.FOCUS_DOWN) }
                },
                onFailure = {
                    if (isFinal) b.tvResult.text = "翻译失败：${it.message ?: "未知错误"}"
                }
            )
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        restartJob?.cancel()
        restartJob = lifecycleScope.launch {
            delay(delayMs)
            if (listening && !useWhisper) startListening()
        }
    }

    override fun onResume() {
        super.onResume()
        // v1.22.0：从"安装语音服务 / 系统设置"返回后要重查一次 ——
        // 用户很可能刚装完服务回来，提示条必须立刻消失，否则他会以为装了也没用。
        refreshEngineUi()
    }

    override fun onStop() {
        // 切后台就别继续占麦克风了
        if (listening) stopAll()
        super.onStop()
    }

    override fun onDestroy() {
        listening = false
        restartJob?.cancel()
        partialJob?.cancel()
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        super.onDestroy()
    }

    // v1.20.0：本类原来是 AppCompatActivity 并自带一个 private toast()。
    // 改成继承 BaseActivity 后（为了用 setSel/selPos 操作下拉框），
    // BaseActivity 已提供 protected toast()，同签名的 private 方法会变成
    // "hides member of supertype" —— Kotlin 对此直接报错而非警告。
    // 删掉本地副本，统一用基类那份。
}
