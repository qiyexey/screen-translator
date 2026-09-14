package com.hunter.screentranslator.util

import android.graphics.Rect
import android.util.DisplayMetrics

/**
 * 翻译区域（ROI）的序列化与坐标换算（v1.15.0）。
 *
 * 存成 "left,top,right,bottom" 的屏幕绝对坐标字符串（见 [Prefs.liveRoi]）。
 * 这里集中处理三件事，避免各调用点各写一份解析：
 *   1. 字符串 ⇄ [Rect]
 *   2. 屏幕坐标 → 取帧坐标（取帧是**降采样**过的，比例不是 1:1）
 *   3. 屏幕旋转 / 分辨率变化后的**有效性校验**
 *
 * 关于第 3 点：框选保存的是绝对像素坐标，旋转屏幕或换分辨率（折叠屏展开、
 * 外接显示器）后，同一个矩形可能变成"贴在角落的一条缝"甚至越界。
 * 这种矩形拿去取帧只会得到一条糊边，翻译质量无从谈起——所以宁可判定为失效，
 * 让用户重框，也不要拿错位的区域去烧额度。
 */
object Roi {

    /** 最小可用边长（屏幕像素）：比这更小的框基本都是误触或旋转后失效 */
    private const val MIN_SIDE_PX = 24

    fun format(r: Rect): String = "${r.left},${r.top},${r.right},${r.bottom}"

    /** 解析失败返回 null（宁可当作"还没框"，也不要猜一个矩形出来） */
    fun parse(s: String?): Rect? {
        if (s.isNullOrBlank()) return null
        val parts = s.split(',')
        if (parts.size != 4) return null
        val v = parts.map { it.trim().toIntOrNull() ?: return null }
        val r = Rect(v[0], v[1], v[2], v[3])
        if (r.width() < MIN_SIDE_PX || r.height() < MIN_SIDE_PX) return null
        return r
    }

    /**
     * 该矩形在**当前屏幕**下是否仍然可用。
     *
     * 允许略微越界（框选时手指可能滑出边缘），但要求绝大部分面积落在屏内；
     * 旋转后排在最上/最下的框会在另一方向上大幅越界，会被这里挡下来。
     */
    fun isValidOnScreen(r: Rect?, dm: DisplayMetrics): Boolean {
        if (r == null) return false
        if (r.width() < MIN_SIDE_PX || r.height() < MIN_SIDE_PX) return false
        val visibleW = minOf(r.right, dm.widthPixels) - maxOf(r.left, 0)
        val visibleH = minOf(r.bottom, dm.heightPixels) - maxOf(r.top, 0)
        if (visibleW <= 0 || visibleH <= 0) return false
        val visibleArea = visibleW.toLong() * visibleH
        val totalArea = r.width().toLong() * r.height()
        return visibleArea * 10 >= totalArea * 9   // 至少 90% 在屏内
    }

    /**
     * 屏幕坐标矩形 → 取帧位图坐标。
     *
     * 取帧用的是降采样的 VirtualDisplay（见 [FrameGrabber]），所以这里要按
     * `frameWidth / screenWidth` 缩放并把结果钳进位图范围——不钳的话
     * [android.graphics.Bitmap.createBitmap] 会直接抛 IllegalArgumentException，
     * 而那是从轮询线程里抛出来的，会把整个服务打崩。
     */
    fun toFrameCoords(screenRoi: Rect, frameW: Int, frameH: Int, screenW: Int, screenH: Int): Rect? {
        if (frameW <= 0 || frameH <= 0 || screenW <= 0 || screenH <= 0) return null
        val sx = frameW.toFloat() / screenW
        val sy = frameH.toFloat() / screenH
        val l = (screenRoi.left * sx).toInt().coerceIn(0, frameW - 1)
        val t = (screenRoi.top * sy).toInt().coerceIn(0, frameH - 1)
        val r = (screenRoi.right * sx).toInt().coerceIn(l + 1, frameW)
        val b = (screenRoi.bottom * sy).toInt().coerceIn(t + 1, frameH)
        if (r - l < 2 || b - t < 2) return null
        return Rect(l, t, r, b)
    }
}
