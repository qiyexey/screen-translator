package com.hunter.screentranslator.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃现场记录 —— v1.18.0 新增。
 *
 * ## 为什么是"本地记录"而不是"接入崩溃上报 SDK"
 *
 * 这个 App 的线上问题几乎全是**机型差异**：各家 ROM 的后台清理策略、无障碍实现、
 * MediaProjection 授权行为、TTS 引擎差异。开发者在真机上复现不了，手上也没有数据。
 *
 * 常规解法是接 Crashlytics / Bugly / Sentry，但三条都不通：
 *   · **Firebase Crashlytics 依赖 GMS** —— 本工程刻意支持无 GMS 国产 ROM
 *     （见 build.gradle.kts 里选 bundled OCR、自建 Whisper 链路的说明），
 *     用它在目标机型上根本不上报。
 *   · **Bugly / 友盟** 会引入统计 SDK，与本 App"零埋点、零服务器"的取向冲突
 *     （全量扫描确认目前不含任何统计/推送/广告 SDK）。
 *   · **Sentry** 需要一个自建或第三方的收集端点，等于凭空多一个数据出口。
 *
 * 所以这里做的是**零依赖、零网络的本地记录**：崩溃时把堆栈写到应用私有目录，
 * 由用户在「关于与用法 → 复制诊断信息」里带出来。它不解决"聚合统计"，但能解决
 * "用户说崩了，我们什么都不知道"这个当务之急，且不改变任何隐私承诺。
 *
 * 将来若要接真正的上报，[recentReport] 已经是一个现成的数据源，
 * 在用户明确同意后把它的内容交给任意 transport 即可。
 *
 * ## 边界
 *
 * - 只记录**本进程**的未捕获异常；native 崩溃（SIGSEGV）与 ANR 不在此列。
 * - 保留最近 [MAX_FILES] 份，超出按时间淘汰，避免占满存储。
 * - 文件写在 `filesDir/crash/`，属于应用私有目录，其它应用读不到。
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val DIR = "crash"
    private const val MAX_FILES = 5

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installed = false

    /** 由 [com.hunter.screentranslator.App] 在 onCreate 里调用。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        install()
    }

    private fun install() {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 记录本身绝不能抛异常：一旦抛了，原始崩溃堆栈就丢了，
            // 用户看到的是"应用无响应"，比崩溃更难排查。
            runCatching { write(thread, throwable) }
                .onFailure { Log.e(TAG, "failed to persist crash", it) }
            // 必须转交回原 handler —— 否则系统不会走正常的崩溃流程
            //（不弹"应用已停止"、不写 tombstone、进程状态也不干净）。
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(thread: Thread, throwable: Throwable): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.txt")

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            pw.println("thread=${thread.name}")
            pw.println("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            pw.println("android=${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            pw.println("abi=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
            pw.println()
            throwable.printStackTrace(pw)
        }
        file.writeText(sw.toString())
        prune(dir)
        Log.e(TAG, "crash written to ${file.name}")
        return file
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.name } ?: return
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    /** 最近一次崩溃的全文（没有则返回 null），供诊断信息使用。 */
    fun recentReport(): String? = latest()?.let { file ->
        runCatching { "===== ${file.name} =====\n" + file.readText() }.getOrNull()
    }

    /** 全部崩溃记录，新的在前。 */
    fun allReports(): List<String> {
        val dir = appContext?.let { File(it.filesDir, DIR) } ?: return emptyList()
        return dir.listFiles()
            ?.sortedByDescending { it.name }
            ?.mapNotNull { f -> runCatching { "===== ${f.name} =====\n" + f.readText() }.getOrNull() }
            ?: emptyList()
    }

    fun count(): Int =
        appContext?.let { File(it.filesDir, DIR).listFiles()?.size } ?: 0

    fun clear() {
        val dir = appContext?.let { File(it.filesDir, DIR) } ?: return
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun latest(): File? =
        appContext?.let { File(it.filesDir, DIR).listFiles() }
            ?.maxByOrNull { it.name }
}
