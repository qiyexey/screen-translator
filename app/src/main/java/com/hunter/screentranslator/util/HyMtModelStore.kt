package com.hunter.screentranslator.util

import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.HttpClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 本地大模型（腾讯 Hy-MT2-1.8B）的模型文件管理：目录、量化档表、下载、导入、校验、删除。
 *
 * 为什么模型**不打进 APK**：1.13GB 的 Q4_K_M 会把安装包从 36MB 撑到 1.2GB，
 * 三个量化档全带上就是 4.5GB。所以走"首次使用时按需下载 / 自行导入"。
 *
 * 关于下载源（2026-09-17 在本机实测）：
 *   ModelScope  4.0 MB/s   → 1.13GB 约 5 分钟（默认）
 *   HuggingFace 0.34 MB/s  → 约 55 分钟（备用）
 * 两个源是同一份上传，文件逐字节一致（已按大小核对）。
 *
 * ⚠️ 1.25bit（461MB，官方宣传的"仅需 440MB"那一档）**故意不在此表中**：
 * 它是 AngelSlim 的 STQ1_0 三元量化，需要 llama.cpp 的 PR #22836 内核，
 * 而该 PR 至今**未合并**（2026-09-17 查得 merged=false）。本 App 用的是主line
 * llama.cpp 的预编译库，二进制里确认没有 STQ1_0 内核 —— 列出来只会让用户
 * 白下 461MB 再看到"模型加载失败"。等内核合并后再加进来。
 */
enum class HyMtQuant(
    val id: String,
    val displayName: String,
    val fileName: String,
    /** 期望字节数，用于完整性校验（取自 HuggingFace LFS 元数据） */
    val sizeBytes: Long,
    /** HuggingFace 记录的 sha256，用于逐字节校验 */
    val sha256: String,
) {
    Q4_K_M(
        id = "q4_k_m",
        displayName = "Q4_K_M · 1.13GB · 推荐",
        fileName = "Hy-MT2-1.8B-Q4_K_M.gguf",
        sizeBytes = 1_133_080_448L,
        sha256 = "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699",
    ),
    Q6_K(
        id = "q6_k",
        displayName = "Q6_K · 1.47GB · 质量更好",
        fileName = "Hy-MT2-1.8B-Q6_K.gguf",
        sizeBytes = 1_474_785_120L,
        sha256 = "d98fe604dec1f28f58f80d7d560f7177e584d3b8e5835862687660e5ff97cb40",
    ),
    Q8_0(
        id = "q8_0",
        displayName = "Q8_0 · 1.91GB · 质量最好、最慢",
        fileName = "Hy-MT2-1.8B-Q8_0.gguf",
        sizeBytes = 1_908_528_192L,
        sha256 = "5c3fe0b1408a5ceb0143184ef247b11b579c525f4b02b060e6c851bb76fef1a4",
    );

    companion object {
        fun fromId(id: String): HyMtQuant = entries.firstOrNull { it.id == id } ?: Q4_K_M
    }
}

/** 下载源。默认 ModelScope：本机实测快 12 倍。 */
enum class HyMtSource(val id: String, val displayName: String) {
    MODELSCOPE("modelscope", "ModelScope（本机实测 4.0MB/s）"),
    HUGGINGFACE("huggingface", "HuggingFace（本机实测 0.34MB/s）");

    fun urlFor(quant: HyMtQuant): String = when (this) {
        MODELSCOPE ->
            "https://modelscope.cn/models/Tencent-Hunyuan/Hy-MT2-1.8B-GGUF/resolve/master/${quant.fileName}"
        HUGGINGFACE ->
            "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/${quant.fileName}"
    }

    companion object {
        fun fromId(id: String): HyMtSource = entries.firstOrNull { it.id == id } ?: MODELSCOPE
    }
}

/**
 * 模型文件状态。
 *
 * 刻意区分 [Missing] 与 [Partial] / [SizeMismatch]：前者该提示"下载"，
 * 后者该提示"续传/重下"，两者的下一步动作不同，合并成一个"不可用"会让用户无从下手。
 */
sealed class HyMtModelStatus {
    object Missing : HyMtModelStatus()
    data class Partial(val bytes: Long, val expected: Long) : HyMtModelStatus()
    data class Ready(val file: File) : HyMtModelStatus()
    data class SizeMismatch(val bytes: Long, val expected: Long) : HyMtModelStatus()
}

/** 进度回调。phase 区分"下载中 / 校验中" —— 校验阶段的百分比没有意义，界面要换文案。 */
fun interface HyMtProgress {
    fun onProgress(phase: Phase, bytesDone: Long, bytesTotal: Long)

    enum class Phase { DOWNLOADING, VERIFYING }
}

object HyMtModelStore {

    /** 同一时刻只允许一个下载/导入，避免两个入口写同一个文件 */
    private val mutex = Mutex()

    /** 模型目录：app 私有目录。sdcard 是 noexec 且会被媒体扫描器遍历，放这里最稳。 */
    val modelDir: File
        get() = File(App.appContext.filesDir, "models").apply { if (!exists()) mkdirs() }

    fun fileFor(quant: HyMtQuant): File = File(modelDir, quant.fileName)

    /** 半成品用 .part 后缀：既能 Range 续传，也避免不完整文件被当成成品加载 */
    private fun partFileFor(quant: HyMtQuant): File = File(modelDir, quant.fileName + ".part")

    fun status(quant: HyMtQuant): HyMtModelStatus {
        val f = fileFor(quant)
        if (f.exists()) {
            return if (f.length() == quant.sizeBytes) HyMtModelStatus.Ready(f)
            else HyMtModelStatus.SizeMismatch(f.length(), quant.sizeBytes)
        }
        val p = partFileFor(quant)
        if (p.exists() && p.length() > 0) {
            return HyMtModelStatus.Partial(p.length(), quant.sizeBytes)
        }
        return HyMtModelStatus.Missing
    }

    /** 已下载完成的量化档 */
    fun readyQuants(): List<HyMtQuant> =
        HyMtQuant.entries.filter { status(it) is HyMtModelStatus.Ready }

    /** 模型目录总占用（设置页显示，也便于用户决定删哪个） */
    fun usedBytes(): Long = modelDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(quant: HyMtQuant) {
        fileFor(quant).delete()
        partFileFor(quant).delete()
    }

    /**
     * 下载指定量化档，支持断点续传（HTTP Range）。
     *
     * 为什么必须支持续传：1.13GB 在手机网络上是"大概率会中断"的量级，
     * 不支持续传等于每次网络抖动都从头再来一遍。
     */
    /**
     * HTTP 非 2xx。带状态码是为了区分"该不该重试"：
     * 5xx/408/429 是服务端临时问题，值得再来一次；4xx（除 408/429）重试没意义。
     */
    private class HttpStatusException(val code: Int, message: String) : RuntimeException(message)

    /**
     * 单次请求的尝试上限。
     * 实测依据：ModelScope 在手机网络下会 `HTTP/2 stream 1 reset by server (INTERNAL_ERROR)`，
     * 本次就实际发生在只下了 1MB 的时候 —— 这类中断是常态，不是异常情况。
     */
    private const val MAX_DOWNLOAD_ATTEMPTS = 6

    /**
     * 下载指定量化档，支持断点续传（HTTP Range）与**自动重试**。
     *
     * 为什么必须自动重试：v1.17.0 实测中 ModelScope 的 HTTP/2 流在 1MB 处被服务端重置，
     * 而手机网络（切基站、锁屏、后台限速）让这类中断成为常态。把"再点一次"的负担
     * 丢给用户，等于让一个 5 分钟的下载需要人守着。
     *
     * 每次重试都从**磁盘上的实际字节数**续传（而不是内存里的计数），因为失败可能
     * 发生在数据已经落盘之后；连续两次"一字节没长"就放弃，避免服务端总在同一处
     * 断开时把重试次数白耗光。
     */
    suspend fun download(
        quant: HyMtQuant,
        source: HyMtSource,
        progress: HyMtProgress? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val part = partFileFor(quant)
                // 已超过期望大小（换过源 / 上次写坏）→ 从头来
                if (part.exists() && part.length() > quant.sizeBytes) part.delete()

                var attempt = 0
                while (true) {
                    val before = if (part.exists()) part.length() else 0L
                    try {
                        attempt++
                        fetchOnce(part, quant, source, progress)
                        break
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        val now = if (part.exists()) part.length() else 0L
                        // "一字节没长"要连续 3 次才判定为卡死：手机上一次十几秒的
                        // 网络中断很常见，若 2 次就放弃，等于出门右转丢给用户重来。
                        val stalled = attempt >= 3 && now <= before
                        if (!isRetryable(t) || attempt >= MAX_DOWNLOAD_ATTEMPTS || stalled) throw t
                        // 退避 2s, 4s, 6s, 8s, 10s（合计约 30s 重试窗口后放弃）
                        delay(2000L * attempt)
                    }
                }

                // 大小校验（快）→ sha256 校验（慢，但能挡住"下到一半的坏文件被当模型加载"）
                if (part.length() != quant.sizeBytes) {
                    throw RuntimeException("下载不完整：${part.length()} / ${quant.sizeBytes} 字节（已保留，可再次点击续传）")
                }
                progress?.onProgress(HyMtProgress.Phase.VERIFYING, 0, quant.sizeBytes)
                if (!sha256Of(part).equals(quant.sha256, ignoreCase = true)) {
                    part.delete()
                    throw RuntimeException("校验失败：sha256 不符，已删除损坏文件，请重新下载")
                }

                val dst = fileFor(quant)
                dst.delete()
                if (!part.renameTo(dst)) {
                    part.copyTo(dst, overwrite = true)
                    part.delete()
                }
                dst
            }
        }
    }

    /** 单次尝试：按 part 当前长度续传，读完 flush 即返回（失败由外层决定是否重试） */
    private fun fetchOnce(
        part: File,
        quant: HyMtQuant,
        source: HyMtSource,
        progress: HyMtProgress?,
    ) {
        var done = if (part.exists()) part.length() else 0L
        if (done > quant.sizeBytes) {
            part.delete()
            done = 0L
        }

        val req = Request.Builder()
            .url(source.urlFor(quant))
            .apply { if (done > 0) header("Range", "bytes=$done-") }
            .build()

        HttpClients.download.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, "HTTP ${resp.code}（${source.displayName}）")
            }
            // 服务端不支持 Range 却回了 200 → 必须截断重写，
            // 否则会在旧字节后面接着写，得到一个大小对但内容坏的文件。
            val append = done > 0 && resp.code == 206
            if (!append) done = 0L

            val body = resp.body ?: throw RuntimeException("空响应体")
            body.byteStream().use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = done
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        // 每 2MB 报一次：太频繁会把 UI 刷爆
                        if (done - lastReport >= 2L * 1024 * 1024) {
                            lastReport = done
                            progress?.onProgress(
                                HyMtProgress.Phase.DOWNLOADING, done, quant.sizeBytes
                            )
                        }
                    }
                    out.flush()
                }
            }
            progress?.onProgress(HyMtProgress.Phase.DOWNLOADING, done, quant.sizeBytes)
        }
    }

    /** 网络类错误与 5xx/408/429 值得重试；其余（4xx、空响应体等）不重试 */
    private fun isRetryable(t: Throwable): Boolean = when (t) {
        is java.io.IOException -> true      // 连接被重置 / 超时 / 断流都属这类
        is HttpStatusException -> t.code == 408 || t.code == 429 || t.code in 500..599
        else -> false
    }

    /**
     * 从用户选的 gguf 文件导入（SAF）。
     *
     * 刻意**不校验 sha256**：这正是"导入"存在的意义 —— 用户可能有自己量化/微调的 gguf。
     * 但必须落到所选量化档的文件名下，否则运行时不知道去哪找。
     * 大小不符由调用方提示（不阻断）。
     */
    suspend fun importFrom(
        quant: HyMtQuant,
        open: () -> InputStream?,
        progress: HyMtProgress? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        mutex.withLock {
            // 用 try 包住 runCatching：取消必须原样抛出，不能变成"失败结果"
            try {
            runCatching {
                val part = partFileFor(quant)
                part.delete()
                val input = open() ?: throw RuntimeException("无法读取所选文件")
                input.use { ins ->
                    java.io.FileOutputStream(part).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport >= 4L * 1024 * 1024) {
                                lastReport = done
                                progress?.onProgress(
                                    HyMtProgress.Phase.DOWNLOADING, done, quant.sizeBytes
                                )
                            }
                        }
                        out.flush()
                    }
                }
                if (part.length() == 0L) {
                    part.delete()
                    throw RuntimeException("所选文件是空的")
                }
                val dst = fileFor(quant)
                dst.delete()
                if (!part.renameTo(dst)) {
                    part.copyTo(dst, overwrite = true)
                    part.delete()
                }
                dst
            }
            } catch (ce: CancellationException) {
                // 用户退出设置页会让 lifecycleScope 取消协程。若把它当成"导入失败"，
                // 轻则显示误导信息，重则此时 Activity 已销毁、弹窗抛 BadTokenException 崩掉。
                throw ce
            }
        }
    }

    /**
     * 下载在 **App 级作用域**里跑，而不是 Activity 的 lifecycleScope。
     *
     * 理由：1.13GB 要下 5 分钟，用户在这期间退出设置页（或屏幕自动锁屏导致 Activity
     * 重建）是完全正常的操作 —— 挂在 Activity 上会让下载随之取消，
     * 用户看到的是"下载又回到 0%"。
     *
     * 进度靠两处：下载中回调（页面在前台时画进度条），以及 .part 文件长度
     * （页面重新进来时直接按文件大小算出百分比，不需要保存进度状态）。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var activeDownload: Job? = null
        private set

    fun startDownload(
        quant: HyMtQuant,
        source: HyMtSource,
        onProgress: HyMtProgress? = null,
    ): Job {
        activeDownload?.takeIf { it.isActive }?.let { return it }
        val job = appScope.launch { download(quant, source, onProgress) }
        activeDownload = job
        return job
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
