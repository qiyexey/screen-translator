package com.hunter.screentranslator.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * v1.8.0 屏幕截图（图片翻译用）。
 *
 * 设计：**按需授权、抓一帧、立刻释放**。
 * MediaProjection 是敏感能力，常驻会持续耗电并在通知栏挂图标；这里由调用方在
 * 用户点击「图片翻译」时才申请授权，拿到一帧后立即 stop()，不常驻任何后台服务。
 *
 * ⚠️ 平台限制（如实告知，无法绕过）：
 * Android 14 (API 34) 起，MediaProjection 授权是**每次会话都要重新弹窗**的，
 * 系统不允许应用长期持有。因此每次使用图片翻译都会看到一次系统授权框。
 */
object ScreenCapture {

    private const val TAG = "ScreenTranslator"

    /**
     * 从已授权的 MediaProjection 抓取一帧截图。
     *
     * @param projection 由调用方通过 MediaProjectionManager.getMediaProjection 创建
     * @return 截图 Bitmap；失败返回 null
     *
     * 调用方**必须**在拿到结果后调用 projection.stop() 释放（本函数也会在内部
     * 释放 VirtualDisplay / ImageReader，但不 stop projection 本身，因为投影的
     * 生命周期由持有者管理）。
     */
    @SuppressLint("WrongConstant")
    fun captureOnce(ctx: Context, projection: MediaProjection): Bitmap? {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        if (width <= 0 || height <= 0) {
            Log.e(TAG, "屏幕尺寸异常: ${width}x$height")
            return null
        }

        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        val thread = HandlerThread("st-capture").apply { start() }
        val handler = Handler(thread.looper)

        return try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            // 用固定尺寸的 ImageReader + 虚拟显示器镜像真实屏幕
            display = projection.createVirtualDisplay(
                "st-capture-display",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )

            // 等一帧就绪（最多 2 秒）。用 acquireLatestImage 轮询而非 acquireNextImage，
            // 避免拿到创建初期的空白帧。
            var image: Image? = null
            val deadline = System.currentTimeMillis() + CAPTURE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                image = reader.acquireLatestImage()
                if (image != null) break
                Thread.sleep(40)
            }
            val img = image
            if (img == null) {
                Log.e(TAG, "抓帧超时（${CAPTURE_TIMEOUT_MS}ms 内没有可用帧）")
                null
            } else {
                try {
                    val plane = img.planes.firstOrNull()
                    if (plane == null) {
                        Log.e(TAG, "图像 plane 为空")
                        null
                    } else {
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        // rowStride 通常大于 width*pixelStride（对齐填充），必须按 rowStride
                        // 计算位图宽度，否则图像会斜切错位。
                        val rowPadding = rowStride - pixelStride * width
                        val bmpWidth = width + rowPadding / pixelStride
                        val bmp = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(buffer)

                        // 裁掉右侧填充，得到与屏幕等宽的真实图像
                        val cropped = if (bmpWidth != width) {
                            Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
                        } else {
                            bmp
                        }
                        Log.i(TAG, "截图成功: ${cropped.width}x${cropped.height}")
                        cropped
                    }
                } finally {
                    // Image 必须关闭，否则 ImageReader 的缓冲会被耗尽
                    runCatching { img.close() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "截图失败: $e")
            null
        } finally {
            runCatching { display?.release() }
            runCatching { reader?.close() }
            runCatching { thread.quitSafely() }
        }
    }

    private const val CAPTURE_TIMEOUT_MS = 2000L
}
