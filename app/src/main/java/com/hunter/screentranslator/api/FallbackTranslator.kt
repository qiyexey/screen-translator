package com.hunter.screentranslator.api

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * 主引擎失败时自动改用备用引擎（v1.29.0）。
 *
 * 动机：默认引擎改成必应网页版后，它是"随时可能失效"的非公开接口。一旦失效，
 * 全屏 / 划词 / 图片翻译全部报错，用户只能手动去设置里换引擎。这里包一层，
 * 主引擎失败就当场换备用引擎再试一次，用户看到的是译文而不是报错。
 *
 * 两条护栏：
 *  · **取消不算失败**：协程被取消（新事件到来、页面关掉）时直接往上抛，
 *    不能误判成"主引擎挂了"去烧备用引擎的额度。
 *  · **熔断**：主引擎失败后 [COOLDOWN_MS] 内直接走备用，不再每次先等主引擎超时。
 *    全屏模式一分钟能发几十次请求，每次都先让必应超时 15 秒，体验等于卡死。
 *
 * [primary] / [fallback] 各自已经包了 [CachingTranslator]，缓存按各自引擎分开存，
 * 不会把备用引擎的译文记到主引擎名下。
 */
class FallbackTranslator(
    private val primary: Translator,
    private val fallback: Translator,
    private val fallbackName: String,
    /** 备用引擎能否读图；不能的话图片翻译不切换，原样返回主引擎的错误 */
    private val fallbackVision: Boolean
) : Translator {

    override suspend fun translate(
        text: String,
        targetLang: String,
        sourceLang: String
    ): Result<String> = run(
        { primary.translate(text, targetLang, sourceLang) },
        { fallback.translate(text, targetLang, sourceLang) }
    )

    override suspend fun translateImage(
        imageBytes: ByteArray,
        mimeType: String,
        targetLang: String,
        hint: String?,
        sourceLang: String
    ): Result<String> {
        val first = primary.translateImage(imageBytes, mimeType, targetLang, hint, sourceLang)
        if (first.isSuccess || !fallbackVision) return first
        rethrowIfCancelled(first)
        return fallback.translateImage(imageBytes, mimeType, targetLang, hint, sourceLang)
            .also { if (it.isSuccess) markUsedFallback() }
    }

    private suspend fun run(
        tryPrimary: suspend () -> Result<String>,
        tryFallback: suspend () -> Result<String>
    ): Result<String> {
        if (!primaryCoolingDown()) {
            val first = tryPrimary()
            if (first.isSuccess) {
                lastUsedFallbackAt = 0L
                return first
            }
            rethrowIfCancelled(first)
            log("主引擎失败，改用备用引擎「$fallbackName」: ${first.exceptionOrNull()?.message}")
            primaryFailedAt = clock()
        }
        val second = tryFallback()
        rethrowIfCancelled(second)
        if (second.isSuccess) markUsedFallback()
        return second
    }

    private suspend fun rethrowIfCancelled(r: Result<*>) {
        val e = r.exceptionOrNull()
        if (e is CancellationException) throw e
        currentCoroutineContext().ensureActive()
    }

    private fun markUsedFallback() {
        lastUsedFallbackAt = clock()
        lastFallbackName = fallbackName
    }

    private fun primaryCoolingDown(): Boolean =
        primaryFailedAt != 0L && clock() - primaryFailedAt < COOLDOWN_MS

    companion object {
        private const val TAG = "ScreenTranslator"

        /** 主引擎失败后多久内不再先试它 */
        const val COOLDOWN_MS = 60_000L

        // 进程内共享：FallbackTranslator 每次翻译都由工厂新建，状态不能放实例上
        @Volatile private var primaryFailedAt = 0L
        @Volatile private var lastUsedFallbackAt = 0L
        @Volatile private var lastFallbackName = ""

        // 单元测试里没有 Android 运行时（Log / SystemClock 是空桩），留两个注入点
        internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
        internal var log: (String) -> Unit = { Log.w(TAG, it) }

        /** 切换主引擎 / 备用引擎后清掉熔断状态，新配置立刻从主引擎开始试 */
        fun reset() {
            primaryFailedAt = 0L
            lastUsedFallbackAt = 0L
        }

        /**
         * 最近一次译文是否来自备用引擎（[withinMs] 内），是的话返回备用引擎名。
         * 悬浮面板用它在底栏标一句「备用：xxx」，免得用户以为必应还好着。
         */
        fun recentFallbackName(withinMs: Long = 5_000L): String? {
            val t = lastUsedFallbackAt
            return if (t != 0L && clock() - t < withinMs) lastFallbackName else null
        }
    }
}
