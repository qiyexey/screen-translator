package com.hunter.screentranslator.util

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v1.11.0 端侧 OCR（拍照翻译用）。
 *
 * **为什么用 ML Kit 的 bundled 变体，而不是 play-services 变体：**
 * bundled 把识别模型直接打进 APK，运行时**不需要 GMS**——与本项目"无 GMS 国产 ROM
 * 也能用"的取向一致（参见 v1.7.0 为同样原因做的 Whisper 引擎）。
 * 代价是 APK 体积（中文模型约 4~8MB）。play-services 变体体积小，但依赖 GMS
 * 在运行时下载模型，在国行机器上可能直接不可用。
 *
 * **中文识别器同时支持拉丁字母与数字**，所以中英混排只需要一个模型。
 * 日后若要认日文/韩文，加 com.google.mlkit:text-recognition-japanese / -korean，
 * 并在 [recognize] 里按目标脚本挑 client 即可（结构与这里一致）。
 *
 * 与屏幕截图那条路（[ScreenCapture] / MediaProjection）无关：本类的位图直接来自
 * 相机，因此不受 MediaProjection"每次会话都要重新授权"的限制，也不需要无障碍截图能力。
 */
object OcrEngine {

    private const val TAG = "ScreenTranslator"

    /** 一行识别结果：文本 + 在原图中的位置（供排版还原/框选使用） */
    data class Line(val text: String, val box: Rect)

    // 识别器是重量级对象（持有 native 模型），进程内共用一个，勿每次新建。
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 日文识别器（v1.15.15）。
     *
     * 与中文识别器**必须分开**：ML Kit 的识别模型是按脚本分的，中文模型认不了假名，
     * 这也是当初"识别不到日文"的根因（依赖里根本没装日文模型，不是参数问题）。
     */
    private val japaneseRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    /** 用日文模型识别整张位图（识别失败返回空列表，由调用方决定怎么告知用户） */
    suspend fun recognizeJapanese(bitmap: Bitmap, throwOnFailure: Boolean = false): List<Line> =
        suspendCancellableCoroutine { cont ->
            val image = runCatching { InputImage.fromBitmap(bitmap, 0) }.getOrNull()
            if (image == null) {
                if (throwOnFailure) cont.resumeWithException(IllegalArgumentException("无法读取 OCR 图片"))
                else cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            japaneseRecognizer.process(image)
                .addOnSuccessListener { result ->
                    val lines = ArrayList<Line>()
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            val t = line.text
                            if (t.isNotBlank()) lines.add(Line(t, box))
                        }
                    }
                    if (cont.isActive) cont.resume(lines)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "日文 OCR 失败: $e")
                    if (cont.isActive) {
                        if (throwOnFailure) cont.resumeWithException(e)
                        else cont.resume(emptyList())
                    }
                }
        }

    /**
     * 识别整张位图。
     *
     * 失败时返回**空列表**而不是抛异常：一次识别失败不应升级成崩溃，
     * 由调用方决定怎么如实告知用户。
     */
    suspend fun recognize(bitmap: Bitmap): List<Line> = suspendCancellableCoroutine { cont ->
        val image = runCatching { InputImage.fromBitmap(bitmap, 0) }.getOrNull()
        if (image == null) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val lines = ArrayList<Line>()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val t = line.text
                        if (t.isNotBlank()) lines.add(Line(t, box))
                    }
                }
                if (cont.isActive) cont.resume(lines)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "端侧 OCR 失败: $e")
                if (cont.isActive) cont.resume(emptyList())
            }
    }

    /**
     * 把识别结果拼成交给翻译引擎的纯文本。
     *
     * 按**行**拼接并保留换行，而不是按块合成整段：菜单、路牌、表单这类内容
     * 逐行独立，保留换行能让模型理解结构，译文也更贴合原排版。
     */
    fun toPlainText(lines: List<Line>): String =
        lines.joinToString("\n") { it.text }.trim()
}
