package com.hunter.screentranslator.util

import android.graphics.Bitmap

/**
 * 画面相似度指纹（v1.15.0 引入，v1.15.2 重写度量方式）。
 * 实时翻译的省额度闸门：差异小于阈值就当作"没变"，跳过这一轮。
 *
 * ## v1.15.2：为什么必须改（实测踩到的坑）
 *
 * 旧实现是 **16×16 灰度 + 全图平均差**，结果是"模型读得出、但那段文字从来没被送去翻译"：
 *
 * 1. **分辨率太低**。一个对话框选区约 1000×280 像素，压到 16×16 后每格平均了
 *    约 62×17 个源像素 —— 文字笔画被彻底抹平成一片均匀灰。两段完全不同的日文
 *    在 16×16 上几乎是同一个东西。
 * 2. **平均差会被背景稀释**。选区里大片（60%~90%）是不变的背景，文字只占一小块；
 *    取全图平均，文字的差异被摊薄到阈值以下，于是永远判"没变"。
 *
 * 两处叠加 → 译文永远停在第一段。**这不是识别问题，是检测问题。**
 *
 * ## 现在的做法
 *
 * - **32×32**（1024 格）：平均窗口小 4 倍，文字结构留得住。
 * - **不用平均，数"明显变了的格子占比"**：`|Δ| ≥ [CELL_DELTA]` 才计入，
 *   除以总格数 ×100。不变的背景贡献 0，只有真正变的文字区域贡献比例。
 *
 * 为什么这个度量同时还能抗噪：
 *   - 同一句话 + 闪烁的 ▼ 光标 → 只有几个格子变 → 约 0%
 *   - 换成新的一段对白          → 上百个格子变 → 10%~40%
 *   - 画面完全没动 + 轻微抖动    → 0%~1%
 * 所以阈值 8（= 8% 的格子变了）能干净地把"新文本"和"光标在闪"分开。
 */
class FrameSignature {

    /**
     * 采样缓冲。按 ROI 像素数分配一次后复用（900×300 的选区约 1MB，
     * 每轮拷一次可以接受；比逐点 getPixel 快一个数量级）。
     */
    private var pxBuf = IntArray(0)

    /** 指纹输出缓冲（复用；对外返回副本，见 [of] 末尾注释） */
    private val out = IntArray(N * N)

    /**
     * 取指纹（32×32 灰度，0~255）。
     *
     * ## v1.15.10：为什么不再用 Canvas 缩图
     *
     * 原实现是 `Canvas.drawBitmap(src, null, dst32x32, paint)` + `getPixels`。
     * 这条路径**依赖位图的 alpha 与合成语义**：如果取帧得到的帧 alpha=0
     * （部分设备的 MediaProjection 帧就是这样，RGB 有效但 alpha 不置位），
     * SRC_OVER 合成等于**什么都没画**，32×32 缓冲永远是初始的全 0
     * —— 指纹恒为常数，`diff` 恒为 0。
     *
     * 症状极隐蔽：送出去翻译的是**原始 JPEG**（模型照样读得出日文），
     * 而"变化检测"却永远判"没变" → **只翻译第一段，之后再也不更新**。
     * 而且换任何阈值/度量都无效，因为指纹根本没变过。
     *
     * 现在改成**直接按格平均采样**：不经过 Canvas、不依赖 alpha，
     * 每个格子取最多 4×4 个采样点的盒式平均。行为完全由我们自己的代码决定。
     */
    fun of(src: Bitmap): IntArray {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return out.copyOf()
        if (pxBuf.size < w * h) pxBuf = IntArray(w * h)
        src.getPixels(pxBuf, 0, w, 0, 0, w, h)

        for (y in 0 until N) {
            val y0 = y * h / N
            val y1 = ((y + 1) * h / N).coerceAtMost(h).coerceAtLeast(y0 + 1)
            val sy = ((y1 - y0) / 4).coerceAtLeast(1)
            for (x in 0 until N) {
                val x0 = x * w / N
                val x1 = ((x + 1) * w / N).coerceAtMost(w).coerceAtLeast(x0 + 1)
                val sx = ((x1 - x0) / 4).coerceAtLeast(1)
                var sum = 0
                var cnt = 0
                var yy = y0
                while (yy < y1) {
                    val row = yy * w
                    var xx = x0
                    while (xx < x1) {
                        val c = pxBuf[row + xx]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        sum += (r * 77 + g * 150 + b * 29) shr 8
                        cnt++
                        xx += sx
                    }
                    yy += sy
                }
                out[y * N + x] = if (cnt > 0) sum / cnt else 0
            }
        }
        // 必须返回**副本**：out 是本类复用的缓冲，直接返回会让调用方互相别名
        // （refSig 与当前帧变成同一个数组，比对恒为 0）。
        return out.copyOf()
    }

    companion object {
        /** 指纹边长。16 太小（见类注释），32 是"够准"与"够省"的折中 */
        const val N = 32

        /** 单格灰度变化达到这个幅度才算"这一格变了"，用来滤掉抖动与抗锯齿噪声 */
        private const val CELL_DELTA = 12

        /**
         * 两个指纹的差异度，归一化到 **0~100**，语义是"**有多少百分比的格子明显变了**"。
         *
         * 注意这不是"平均差"：平均差会被大片不变的背景稀释，正是 v1.15.2 要修的毛病。
         *
         * 典型取值（**v1.15.10 用真机数据重新定标**）：
         *   - 完全没动 / 只有抖动           → 0
         *   - 同一句话 + 闪烁的 ▼ 光标      → 0（变化格数 < 5，百分比取整就是 0）
         *   - 换成新的一段对白              → 2 ~ 5
         *
         * ⚠️ 注意"换一段对白只有 2~5"这个量级：v1.15.0~1.15.9 我把它估成了 10~40，
         * 于是默认阈值 8 让**每一次真实变化都够不到**，表现就是"只翻译第一段"。
         * 现在 CELL_DELTA 从 24 降到 12，同一场景的 Δ 大致翻倍，与噪声的间距更宽裕。
         */
        fun diff(a: IntArray?, b: IntArray?): Int {
            if (a == null || b == null) return 100   // 没有参照物 → 当作"变了很多"
            var changed = 0
            for (i in a.indices) {
                val d = a[i] - b[i]
                val ad = if (d >= 0) d else -d
                if (ad >= CELL_DELTA) changed++
            }
            return changed * 100 / a.size
        }

        /** 指纹的哈希，用于"这段画面是不是翻译过"的 LRU 判定 */
        fun hash(sig: IntArray): Int {
            var h = 17
            // 先按 8 级量化再混合：容忍 ±8 的灰度漂移，让 A→B→A 这种来回切换能命中缓存
            for (v in sig) {
                h = h * 31 + (v shr 3)
            }
            return h
        }
    }
}
