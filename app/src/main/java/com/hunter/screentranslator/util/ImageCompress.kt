package com.hunter.screentranslator.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * 上传前的图片压缩（v1.15.0 从 ImageTranslateActivity 抽出来共用）。
 *
 * PNG 截图动辄数 MB，base64 后还要再涨 1/3，很容易撞上请求体上限；
 * JPEG 质量 85 + 最长边 1600 是清晰度与体积的折中（文字仍清晰可辨）。
 *
 * 抽成公共函数是因为实时翻译每秒都要压一次，和图片翻译走的是完全相同的
 * 参数——两处各写一份，将来调质量时必然漏改一处。
 */
object ImageCompress {

    private const val MAX_SIDE = 1600
    private const val QUALITY = 85

    /**
     * 缩放到最长边不超过 [MAX_SIDE] 并转 JPEG。
     * 失败返回 null（由调用方如实告知，不升级成崩溃）。
     */
    fun toJpeg(src: Bitmap): ByteArray? = runCatching {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
        val target = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            src
        }
        ByteArrayOutputStream().use { bos ->
            target.compress(Bitmap.CompressFormat.JPEG, QUALITY, bos)
            if (target !== src) target.recycle()
            bos.toByteArray()
        }
    }.getOrNull()
}
