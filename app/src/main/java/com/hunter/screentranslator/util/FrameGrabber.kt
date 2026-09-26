package com.hunter.screentranslator.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 常驻取帧器（v1.15.0，实时屏幕翻译用）。
 *
 * 与 [ScreenCapture] 的关系：那个是"抓一帧就释放"的一次性截图，每次都要重新
 * 申请授权；这里是**授权一次、持续取帧**——实时翻译每秒都要帧，不可能每秒弹
 * 一次系统授权框。两者刻意分开而不是合并：一个的生命周期是"一次调用"，
 * 另一个是"整局游戏"，合并只会让两边都别扭。
 *
 * 三个关键设计：
 *
 * 1. **降采样**。整屏 ARGB 每帧是 10MB 级别的拷贝（1440×3200 约 18MB），
 *    而实时翻译只需要文字框那一小块。这里把 VirtualDisplay 建成长边不超过
 *    [MAX_LONG_SIDE] 的尺寸，帧一下子小到 1/3 左右。注意缩放比例**不写死**：
 *    [Roi.toFrameCoords] 用的是位图实际尺寸与屏幕尺寸之比，所以即便某些 ROM
 *    没有按请求尺寸缩放镜像内容，坐标也不会错位。
 *
 * 2. **复用整帧位图**。每帧 new 一个全屏 Bitmap 会让 GC 在这个 1 秒一轮的
 *    循环里持续抖动（一局游戏下来上千次），所以 [fullBmp] 只分配一次、
 *    按需重建（屏幕尺寸变了才重建）。
 *
 * 3. **Image 必须 close()**。不关的话 ImageReader 的缓冲（maxImages=2）很快
 *    耗尽，表现为"图片突然永远取不到"，而且不报错——是很难查的那种故障。
 */
class FrameGrabber(private val ctx: Context) {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var fullBmp: Bitmap? = null

    /** 取帧分辨率（VirtualDisplay 尺寸，通常小于屏幕） */
    var frameWidth = 0; private set
    var frameHeight = 0; private set

    @Volatile private var running = false

    /** 屏幕尺寸按当前真实分辨率读，不缓存 —— 旋转/分屏后要立刻跟上 */
    private fun screenMetrics(): DisplayMetrics {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
    }

    /**
     * 建立常驻取帧会话。调用方负责持有 [MediaProjection] 的生命周期。
     * @return 是否成功（失败时已自行清理，可重试）
     */
    fun start(projection: MediaProjection): Boolean {
        if (running) return true
        stop()   // 清掉可能的残留
        this.projection = projection

        val dm = screenMetrics()
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        if (sw <= 0 || sh <= 0) {
            Log.e(TAG, "屏幕尺寸异常: ${sw}x$sh")
            return false
        }
        val longSide = max(sw, sh)
        val scale = if (longSide > MAX_LONG_SIDE) MAX_LONG_SIDE.toFloat() / longSide else 1f
        val fw = (sw * scale).roundToInt().coerceAtLeast(2)
        val fh = (sh * scale).roundToInt().coerceAtLeast(2)

        return try {
            thread = HandlerThread("st-live-capture").apply { start() }
            handler = Handler(thread!!.looper)
            reader = ImageReader.newInstance(fw, fh, PixelFormat.RGBA_8888, 2)
            display = projection.createVirtualDisplay(
                "st-live-display", fw, fh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, handler
            )
            frameWidth = fw
            frameHeight = fh
            running = true
            Log.i(TAG, "取帧会话已建立: 屏幕 ${sw}x$sh → 帧 ${fw}x$fh")
            true
        } catch (e: Exception) {
            Log.e(TAG, "取帧会话建立失败: $e")
            stop()
            false
        }
    }

    /**
     * 抓当前帧并裁出 [screenRoi] 对应的区域。
     *
     * @param screenRoi 屏幕绝对坐标矩形；null 表示要整帧
     * @return 裁好的位图（调用方负责在不再使用后 recycle）；无可用帧或出错返回 null
     */
    @Synchronized
    fun grabRoi(screenRoi: Rect?): Bitmap? {
        if (!running) return null
        val r = reader ?: return null

        var img: Image? = null
        return try {
            img = r.acquireLatestImage() ?: return null   // 还没有新帧：本轮跳过
            val plane = img.planes.firstOrNull() ?: return null
            val buf = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val fw = frameWidth
            val fh = frameHeight
            val rowPadding = rowStride - pixelStride * fw
            // rowStride 常大于 width*4（对齐填充），必须按 rowStride 算位图宽度，
            // 否则图像会斜切错位（与 ScreenCapture 同一个坑）
            val bmpW = fw + rowPadding / pixelStride

            val reuse = fullBmp
            val full = if (reuse != null && reuse.width == bmpW && reuse.height == fh) {
                reuse
            } else {
                reuse?.recycle()
                Bitmap.createBitmap(bmpW, fh, Bitmap.Config.ARGB_8888).also { fullBmp = it }
            }

            buf.rewind()
            full.copyPixelsFromBuffer(buf)

            if (screenRoi == null) {
                // 整帧：仍要裁掉右侧填充
                if (bmpW != fw) Bitmap.createBitmap(full, 0, 0, fw, fh) else full.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                val dm = screenMetrics()
                val fr = Roi.toFrameCoords(screenRoi, fw, fh, dm.widthPixels, dm.heightPixels)
                if (fr == null) {
                    Log.w(TAG, "ROI 换算失败（可能已越界）: $screenRoi")
                    null
                } else {
                    Bitmap.createBitmap(full, fr.left, fr.top, fr.width(), fr.height())
                }
            }
        } catch (e: Exception) {
            // 单帧失败不能升级成服务崩溃：下一轮接着来就好
            Log.w(TAG, "取帧失败: $e")
            null
        } finally {
            runCatching { img?.close() }
        }
    }

    /** 释放全部资源；可重复调用 */
    @Synchronized
    fun stop() {
        running = false
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        runCatching { thread?.quitSafely() }
        thread = null
        handler = null
        runCatching { fullBmp?.recycle() }
        fullBmp = null
        frameWidth = 0
        frameHeight = 0
        projection = null
    }

    companion object {
        private const val TAG = "ScreenTranslator"

        /**
         * 帧长边上限。1600 与图片翻译上传前的压缩上限一致 —— 既然再高也会被
         * 压掉，取帧阶段就没必要付那份拷贝开销。同时保证 GBA 这类 8px 点阵字
         * 放大到手机屏后仍有足够像素（约 20px 字高）给视觉模型辨认。
         */
        private const val MAX_LONG_SIDE = 1600
    }
}
