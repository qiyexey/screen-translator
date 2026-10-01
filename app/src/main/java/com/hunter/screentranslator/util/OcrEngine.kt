package com.hunter.screentranslator.util

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
 * v1.15.15 加了日文、v1.29.0 加了韩文与拉丁模型，各入口按源语言挑模型，见 [recognizeFor]。
 *
 * 与屏幕截图那条路（[ScreenCapture] / MediaProjection）无关：本类的位图直接来自
 * 相机，因此不受 MediaProjection"每次会话都要重新授权"的限制，也不需要无障碍截图能力。
 */
object OcrEngine {

    private const val TAG = "ScreenTranslator"

    /** 一行识别结果：文本 + 在原图中的位置（供排版还原/框选使用） */
    data class Line(val text: String, val box: Rect)

    // 识别器是重量级对象（持有 native 模型），进程内共用一个，勿每次新建。
    private val chineseRecognizer by lazy {
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

    /** 韩文识别器（v1.29.0）：中文/日文模型都不认谚文，必须单独一个 */
    private val koreanRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /** 拉丁字母识别器（v1.29.0）：纯英/法/德/西文本用它最准，也最快 */
    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** 按语言挑出的识别模型 */
    enum class Script { CHINESE, JAPANESE, KOREAN, LATIN }

    /**
     * 按源语言挑识别模型（v1.29.0）。
     *
     * 以前图片翻译 / 实时翻译固定用日文模型、拍照翻译固定用中文模型，
     * 源语言选韩文时根本认不出，选中文时日文模型会认错日文里没有的简体字。
     *
     * 源语言是「自动」时没法预知，按场景给默认：
     *  · [autoDefault] = JAPANESE：实时/图片翻译。日文模型同时认假名、汉字、拉丁字母，
     *    覆盖面最广，也是这两条链路此前的行为（老用户的游戏翻译不受影响）。
     *  · [autoDefault] = CHINESE：拍照翻译，沿用它此前的行为。
     * 俄文（西里尔字母）ML Kit 没有模型，退到拉丁模型，能认出的有限。
     */
    fun scriptFor(sourceLang: String, autoDefault: Script = Script.JAPANESE): Script = when (sourceLang) {
        "zh", "zh-TW" -> Script.CHINESE  // 中文模型简繁都认
        "ja" -> Script.JAPANESE
        "ko" -> Script.KOREAN
        "en", "fr", "de", "es", "ru", "vi", "id" -> Script.LATIN
        // 泰文、阿拉伯文 ML Kit 没有模型，只能按场景默认值凑合
        else -> autoDefault
    }

    /** 按源语言识别整张位图（v1.29.0 起各翻译入口统一走这里） */
    suspend fun recognizeFor(
        bitmap: Bitmap,
        sourceLang: String,
        autoDefault: Script = Script.JAPANESE,
        throwOnFailure: Boolean = false
    ): List<Line> {
        val client = when (scriptFor(sourceLang, autoDefault)) {
            Script.CHINESE -> chineseRecognizer
            Script.JAPANESE -> japaneseRecognizer
            Script.KOREAN -> koreanRecognizer
            Script.LATIN -> latinRecognizer
        }
        return process(client, bitmap, throwOnFailure)
    }

    /** 识别失败默认返回空列表（一次失败不该升级成崩溃）；[throwOnFailure] 时把异常交给调用方 */
    private suspend fun process(
        client: TextRecognizer,
        bitmap: Bitmap,
        throwOnFailure: Boolean
    ): List<Line> =
        suspendCancellableCoroutine { cont ->
            val image = runCatching { InputImage.fromBitmap(bitmap, 0) }.getOrNull()
            if (image == null) {
                if (throwOnFailure) cont.resumeWithException(IllegalArgumentException("无法读取 OCR 图片"))
                else cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            client.process(image)
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
                    if (cont.isActive) {
                        if (throwOnFailure) cont.resumeWithException(e)
                        else cont.resume(emptyList())
                    }
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
