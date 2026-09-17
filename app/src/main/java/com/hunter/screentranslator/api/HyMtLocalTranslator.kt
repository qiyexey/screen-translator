package com.hunter.screentranslator.api

import android.os.Build
import android.os.SystemClock
import com.hunter.screentranslator.App
import com.hunter.screentranslator.util.HyMtModelStatus
import com.hunter.screentranslator.util.HyMtModelStore
import com.hunter.screentranslator.util.HyMtQuant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File

/**
 * 腾讯 Hy-MT2-1.8B（本地·离线）翻译引擎。
 *
 * 与其它引擎的根本差别：**完全不出网**。模型在设备 CPU 上跑（llama.cpp），
 * 所以它没有密钥、没有额度、断网可用；代价是速度和耗电不如云端。
 * 实测数据见 FIXES-1.17.0.md。
 *
 * 语言名用"全称"而不是 App 的语言码：
 * 官方提示词规范明确要求 target_lang 用全称（中文用中文全称、英文用英文全称），
 * 传 "zh"/"en" 会明显掉质量 —— 这是该模型与通用 LLM 提示词习惯最大的不同。
 */
private val HYMT_TARGET_NAMES = mapOf(
    "zh" to "中文",
    "en" to "英语",
    "ja" to "日语",
    "ko" to "韩语",
    "fr" to "法语",
    "de" to "德语",
    "es" to "西班牙语",
    "ru" to "俄语",
)

class HyMtLocalTranslator(private val quant: HyMtQuant) : Translator {

    override suspend fun translate(text: String, targetLang: String): Result<String> {
        if (text.isBlank()) return Result.success("")
        val targetName = HYMT_TARGET_NAMES[targetLang] ?: targetLang
        val prompt = buildHyMtPrompt(text, targetName)
        return HyMtRuntime.generate(prompt, maxTokensFor(text))
    }

    /**
     * 输出上限按输入长度推算，而不是固定 4096。
     *
     * 屏幕翻译的输入是短句（UI 文字、字幕一行），而 4096 的上限意味着
     * 模型一旦"跑偏"（例如开始解释而不是翻译）会持续生成几十秒才停 ——
     * 用户看到的是界面卡住。按长度给上限能让跑偏很快自己撞墙结束。
     */
    private fun maxTokensFor(text: String): Int =
        (text.length * 2 + 96).coerceIn(128, 1024)

    private fun buildHyMtPrompt(sourceText: String, targetName: String): String = buildString {
        append("将以下文本翻译为 ")
        append(targetName)
        append("，注意只需要输出翻译后的结果，不要额外解释：\n\n")
        append(sourceText)
    }
}

/**
 * 设备能力守卫。
 *
 * 为什么必须有：本 App 自带的 `libllama-android.so` 是用
 * `-march=armv8.6-a`（dotprod + i8mm + fp16）编译的 —— 在不支持这些指令的
 * CPU 上执行会触发 **SIGILL，整个进程直接死**，而且**捕获不到**（不是异常）。
 * 所以只能在加载前读 /proc/cpuinfo 判断，宁可明确拒绝，也不能崩。
 *
 * 12KB 的兼容性代价换来的是 2.25× 的整句延迟差（实测 2.25s → 1.00s/句），
 * 详见 FIXES-1.17.0.md。
 */
object HyMtDeviceSupport {

    /** null = 支持；非 null = 不支持的原因（可直接展示给用户） */
    val reasonIfUnsupported: String? by lazy {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            return@lazy "本机不是 arm64 设备：本地模型运行时只提供 arm64-v8a 版本"
        }
        val feats = runCatching {
            File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Features") }
                ?.substringAfter(':')
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())

        if (feats.isEmpty()) {
            // 读不到就拒绝：万一 CPU 不支持，代价是进程崩溃，比"用不了本地模型"严重得多
            return@lazy "读不到 /proc/cpuinfo，无法确认 CPU 指令集，出于安全不加载本地模型"
        }
        val need = listOf("asimddp" to "dotprod", "i8mm" to "i8mm", "asimdhp" to "fp16")
        val missing = need.filter { (k, _) -> k !in feats }.map { it.second }
        if (missing.isEmpty()) null
        else "本机 CPU 缺少 ${missing.joinToString("、")} 指令，本地模型运行时无法安全加载（换回云端引擎即可）"
    }

    val isSupported: Boolean get() = reasonIfUnsupported == null
}

/**
 * 模型运行时单例：持有 [LlamaModel]，串行化推理，并在合适的时候释放。
 *
 * 三个必须放在一处的约束：
 * 1. **串行**：一个 llama.cpp context 不能被并发解码。屏幕翻译有 7 个调用点
 *    （读屏 / 听视频 / 拍照 / 输入 / 语音 / 划词 / 测试按钮），它们可能同时触发，
 *    所以这里用 Mutex 排队，而不是让调用点各自小心。
 * 2. **单实例**：1.13GB 的权重 + KV cache 约 1.5GB 常驻内存，同时加载两份会直接
 *    把设备压到 OOM。换量化档时先关旧的再开新的。
 * 3. **该放就放**：CPU 推理会让模型页常驻内存，低内存时系统宁可杀后台。
 *    这里在 [com.hunter.screentranslator.App.onTrimMemory] 与闲置超时两条路径上卸载。
 */
object HyMtRuntime {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 全角竖线 U+FF5C 与 U+2581：Hy-MT2 特殊 token 的组成部分 */
    private const val BAR = "\uFF5C"
    private const val UNDER = "\u2581"

    /** Hy-MT2 官方对话格式的三个标记（字节级与 GGUF 模板里的字面量一致） */
    private val HYMT_BOS = "<${BAR}hy_begin${UNDER}of${UNDER}sentence$BAR>"
    private val HYMT_USER = "<${BAR}hy_User$BAR>"
    private val HYMT_ASSISTANT = "<${BAR}hy_Assistant$BAR>"

    private var model: LlamaModel? = null
    private var loadedKey: String? = null
    private var lastUsedAt = 0L
    private var idleJob: Job? = null

    /** 线程数：留两个核给界面的渲染与网络，否则翻译时整机发卡 */
    private fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    val isLoaded: Boolean get() = model != null
    val loadedKeyOrNull: String? get() = loadedKey

    /** 供设置页显示"模型已加载/未加载" */
    fun statusLine(): String {
        val m = model ?: return "未加载"
        val key = loadedKey ?: ""
        return "已加载 $key（${m.config.threads} 线程 / 上下文 ${m.config.contextSize}）"
    }

    suspend fun generate(prompt: String, maxNewTokens: Int): Result<String> = try {
        // 调用方（读屏在"屏幕又变了"时、输入翻译的 600ms 防抖）会 cancel 上一个 job。
        // 这里先抓住调用方的 Job，供下面的哨兵判断"是我被取消了，还是正常跑完"。
        val callerJob = coroutineContext[Job]
        Result.success(
        withModel { m ->
            // 用模型自身配置复制一份，只改采样参数：避免把 contextSize/threads
            // 这类"加载期参数"在生成期重新传一遍而语义不明。
            val cfg = m.config.copy {
                temperature = 0.7f        // 官方 1.8B 推荐值
                topP = 0.6f
                topK = 20
                repeatPenalty = 1.05f
                this.maxTokens = maxNewTokens
                seed = -1
            }
            // 哨兵：协程取消**打断不了**正在跑的 native 解码循环（它只在每次生成
            // 开始时看一次取消标志），所以挂一个随父协程一起被取消的子协程，
            // 在它的 finally 里显式通知 native 停下。否则这一句会白算到 maxTokens
            // （16 tok/s 下上限 1024 就是一分多钟），期间推理 mutex 一直被占，
            // 后面所有翻译请求全排队 —— 现象是"本地引擎卡死"。
            // 正常跑完时 callerJob 未被取消，哨兵不会误设取消标志（下一句不受影响；
            // 而且 native 每次 generate 开头都会重置该标志）。
            coroutineScope {
                val sentinel = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (callerJob?.isCancelled == true) {
                            runCatching { m.cancelGeneration() }
                        }
                    }
                }
                try {
                    cleanOutput(m.generate(buildPrompt(m, prompt), cfg))
                } finally {
                    sentinel.cancel()
                }
            }
        }
        )
    } catch (ce: CancellationException) {
        // 关键：取消不是"翻译失败"。以前这里用 runCatching 把 CancellationException
        // 转成了 Result.failure，于是被取消的那一句会显示成
        // "翻译失败：StandaloneCoroutine was cancelled"（v1.17.0 装机反馈的实际现象）。
        // 必须原样抛出，让调用方的协程正常结束、由新的一次翻译接管界面。
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }

    /** 设置页的"预加载模型"：把几秒的加载耗时挪到用户主动点击的时候 */
    suspend fun preload(): Result<Unit> = try {
        Result.success(withModel { })
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }

    suspend fun unload() {
        mutex.withLock { closeLocked() }
    }

    /** 低内存回调里用：不能阻塞主线程，也不能 suspend */
    fun unloadAsync() {
        scope.launch { runCatching { unload() } }
    }

    private suspend fun <T> withModel(block: suspend (LlamaModel) -> T): T = mutex.withLock {
        val m = ensureLoadedLocked()
        lastUsedAt = SystemClock.elapsedRealtime()
        scheduleIdleUnloadLocked()
        block(m)
    }

    private suspend fun ensureLoadedLocked(): LlamaModel {
        HyMtDeviceSupport.reasonIfUnsupported?.let { throw IllegalStateException(it) }
        val quant = HyMtQuant.fromId(App.prefs.hymtQuant)
        val ctx = App.prefs.hymtContext
        // 注意变量名：不能用 threads —— 下面 load{} 的接收者 LlamaConfig 也有 threads，
        // 在带接收者的 lambda 里 `this.threads = threads` 的右边会解析成接收者自己的
        // 属性（默认值），于是"设置线程数"静默失效。
        val nThreads = if (App.prefs.hymtThreads > 0) App.prefs.hymtThreads else defaultThreads()
        val key = "${quant.id}|t$nThreads|c$ctx"

        model?.let { if (loadedKey == key) return it }

        val file = when (val st = HyMtModelStore.status(quant)) {
            is HyMtModelStatus.Ready -> st.file
            HyMtModelStatus.Missing ->
                throw IllegalStateException("本地模型还没下载。请到 设置 → 翻译引擎与密钥 → 腾讯 Hy-MT2，先点「下载模型」")
            is HyMtModelStatus.Partial ->
                throw IllegalStateException(
                    "模型只下了一部分（${st.bytes * 100 / st.expected}%）。回到设置页再点一次「下载模型」可续传"
                )
            is HyMtModelStatus.SizeMismatch ->
                throw IllegalStateException("模型文件大小不对，请重新下载或删除后重来")
        }

        // 换档/换参：先彻底关掉旧的，再开新的（两份同时存在会 OOM）
        closeLocked()
        // 加载 1.13GB 要 1~2 秒，这期间调用方（Activity/Service）可能被销毁而取消协程。
        // 若在加载途中被取消，native 侧可能留下半初始化的 context，而 mutex 已经释放 ——
        // 用 NonCancellable 包住，保证"要么完整加载，要么完整不加载"。
        val loaded = withContext(NonCancellable) {
            LlamaModel.load(file.absolutePath) {
                contextSize = ctx
                batchSize = 256
                this.threads = nThreads
                threadsBatch = nThreads
                temperature = 0.7f
                topP = 0.6f
                topK = 20
                repeatPenalty = 1.05f
                maxTokens = 512
                useMmap = true     // 1.13GB 权重靠 mmap 按需换页，比一次性读进堆稳
                useMlock = false   // 锁内存会让系统无法回收，低内存时反而更容易被杀
                gpuLayers = 0      // 预编译库只有 CPU 后端
                seed = -1
            }
        }
        model = loaded
        loadedKey = key
        return loaded
    }

    /**
     * 构造送进模型的 prompt。
     *
     * **优先手工拼官方格式**，而不是走 `applyChatTemplate`。原因是实测发现的：
     * llama.cpp 核心里的 `llama_chat_apply_template` 只有**老式启发式解析器**
     * （只认识 chatml / llama2 / gemma 等固定几种模板），Hy-MT2 的 jinja 模板
     * 不在其中，会走到 fallback 分支，**把 `<｜hy_User｜>` 放到了正文之后**
     * （实测字节：`BOS + 正文 + <｜hy_User｜>`，而官方格式是 `BOS + <｜hy_User｜> + 正文`）。
     * jinja 引擎只在 common 层（llama-cli / llama-server 用的那层），核心库没有。
     *
     * 手工拼的收益（实测 12 句对比）：13 句里 10 句输出相同，3 句是同义改写，
     * 而**手工格式那 3 句每次都跟 llama-server（jinja 参考实现）的输出一致**。
     * 分词侧也验证过：`<｜hy_User｜>` → 单个 token id 120006、
     * `<｜hy_Assistant｜>` → 120007（用官方格式时它们是独立 token，
     * `generate()` 内部是 `parse_special=true`，所以能被正确识别）。
     *
     * 但**不硬闯**：若模型自带的模板里没有这两个标记（换模型、或用户导入了别的 gguf），
     * 就退回 `applyChatTemplate` —— 让 llama.cpp 自己处理，总比瞎拼强。
     */
    private fun buildPrompt(m: LlamaModel, userText: String): String {
        val tpl = runCatching { m.getChatTemplate() }.getOrDefault("")
        return if (tpl.contains(HYMT_USER) && tpl.contains(HYMT_ASSISTANT)) {
            HYMT_BOS + HYMT_USER + userText + HYMT_ASSISTANT
        } else {
            m.applyChatTemplate(
                """[{"role":"user","content":${jsonEscape(userText)}}]""",
                true
            )
        }
    }

    /**
     * 自己转义，不用 `org.json.JSONObject.quote`。
     *
     * 因为 llama.cpp 那个 wrapper 里是**手写 JSON 解析器**，只认识
     * `\"` / `\n` / `\t` / `\\` 四种转义，其它一律当成"去掉反斜杠的字面量" ——
     * 而 Android 的 JSONObject.quote 会对 0x7F~0x9F、U+2028/2029 等字符输出
     * `\uXXXX`，被那个解析器还原成字面量 `uXXXX`，译文输入就被污染了
     * （屏幕文本里的"…"（U+2026）、引号等很容易踩到）。这里只产出它认识的转义。
     */
    private fun jsonEscape(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n', '\r' -> append("\\n")   // 解析器不认 \r，统一成 \n
                '\t' -> append("\\t")
                else -> if (ch.code >= 0x20) append(ch)  // 其余控制字符直接丢掉
            }
        }
    }

    private fun closeLocked() {
        idleJob?.cancel()
        idleJob = null
        runCatching { model?.close() }
        model = null
        loadedKey = null
    }

    /**
     * 闲置自动卸载。
     *
     * 触发条件是"最后一次使用之后 N 分钟没有任何调用"。之所以需要它：
     * 用户翻完一页资料就把 App 放后台了，模型白占 1.5GB，系统会优先杀这种进程。
     */
    private fun scheduleIdleUnloadLocked() {
        idleJob?.cancel()
        val minutes = App.prefs.hymtIdleUnloadMinutes
        if (minutes <= 0) return
        idleJob = scope.launch {
            delay(minutes * 60_000L)
            unload()
        }
    }

    /**
     * 后处理：模型偶尔会加"译文："前缀、包引号，或把提示词里的要求复述一遍。
     * 这些都会直接显示在悬浮窗里，必须在返回前清掉。
     */
    private fun cleanOutput(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) throw IllegalStateException("模型没有返回译文（可尝试降低量化档或换回云端引擎）")
        // 去前缀："译文：/翻译：/Translation:"
        s = s.removePrefix("译文：").removePrefix("译文:").removePrefix("翻译：").removePrefix("翻译:")
        s = s.trim()
        // 整个结果被引号包住时才去掉（保留原文里正常的引号）
        for (q in listOf('"', '“', '「')) {
            val end = when (q) {
                '"' -> '"'
                '“' -> '”'
                else -> '」'
            }
            if (s.length >= 2 && s.first() == q && s.last() == end) {
                s = s.substring(1, s.length - 1).trim()
            }
        }
        return s
    }
}
