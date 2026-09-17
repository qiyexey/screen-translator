package com.hunter.screentranslator.api

import android.os.Build
import android.os.SystemClock
import com.hunter.screentranslator.App
import com.hunter.screentranslator.util.HyMtModelStatus
import com.hunter.screentranslator.util.HyMtModelStore
import com.hunter.screentranslator.util.HyMtQuant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.codeshipping.llamakotlin.LlamaModel
import org.json.JSONObject
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

    suspend fun generate(prompt: String, maxNewTokens: Int): Result<String> = runCatching {
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
            // 用 GGUF 自带的对话模板包 prompt —— 手拼 <｜hy_User｜> 这类特殊 token
            // 只要有一个字符不对，模型就会退化成"续写"而不是"翻译"。
            val messagesJson = """[{"role":"user","content":${JSONObject.quote(prompt)}}]"""
            val templated = m.applyChatTemplate(messagesJson, true)
            cleanOutput(m.generate(templated, cfg))
        }
    }

    /** 设置页的"预加载模型"：把几秒的加载耗时挪到用户主动点击的时候 */
    suspend fun preload(): Result<Unit> = runCatching { withModel { } }

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
        val loaded = LlamaModel.load(file.absolutePath) {
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
        model = loaded
        loadedKey = key
        return loaded
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
