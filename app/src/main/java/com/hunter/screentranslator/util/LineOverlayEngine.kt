package com.hunter.screentranslator.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.TranslatorFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * v1.25.0 「逐行 OCR → 译文贴回原文位置」的**共享引擎**。
 *
 * ## 为什么要有这个类
 *
 * 这套逻辑原先只长在 [com.hunter.screentranslator.ui.CameraTranslateActivity] 里
 * （约 400 行）。图片翻译（相册选图 / 截屏）本来就该有同样的能力 ——
 * 用户对"看到的就是译文"的期待与来源无关，只与"图上有原文"有关。
 *
 * 直接复制那 400 行是最省事的做法，也是最坏的做法：
 * 坐标换算、贴片配色、按住看原文、并发去重，任何一处将来改进（例如修某个 ROM
 * 上的偏移）都得改两遍，漏一遍就是两个页面行为不一致，
 * 而且**只有真机上点过某个页面才能发现**。所以抽成共享类，两边各持一份实例。
 *
 * ## 职责边界（刻意收得很窄）
 *
 * 本类**不**碰相机、不碰 MediaProjection、不碰布局 —— 它只负责三件事：
 *
 *   1. 拿一张已摆正的 [Bitmap] + 一组 [OcrEngine.Line]，算出"每行该贴在哪"（[bitmapRectToView]）；
 *   2. 把译文渲染成不透明贴片盖回原位（[showChip]），并按周边亮度自适应配色（[isLightAround]）；
 *   3. 翻译的调度：同文去重 + 并发上限 + 进度回调（[translateLines]）。
 *
 * 宿主只需要提供三个 View（冻结帧 / 手势层 / 贴片容器）+ 一个状态回显，
 * 以及"要不要朗读、要不要写历史"这类**业务决策回调**。
 *
 * ## 与 CameraTranslateActivity 的关系（重要）
 *
 * 这个类**不是**为了把拍照翻译也搬过来而写的 —— 拍照翻译的相机取景/点选拍照/
 * 冻结帧那一整套与"相册里的一张静态图"差别很大，硬合并只会让两边都变难读。
 * 因此本次只抽**纯几何 + 纯渲染 + 翻译调度**这三块，拍照翻译保持原样（已真机验证工作正常）。
 * 这是有意的取舍：**先让图片翻译拿到成熟能力，不去动已经跑通的东西**。
 */
class LineOverlayEngine(
    private val host: Host
) {

    /** 宿主需要提供的东西。用回调而不是继承，避免把 Activity 的层级绑进来。 */
    interface Host {
        val context: Context

        /** 与冻结帧同坐标系的全屏容器，贴片都挂它上面 */
        val overlayHost: FrameLayout

        /** 全屏尺寸（贴片位置钳制用）—— 通常是根布局 */
        val rootView: View

        /** 译文的原文（写历史用） */
        fun onSourceReady(text: String)

        /** 状态文案回显（"翻译中 3/8 行…"这类） */
        fun onStatus(text: String)

        /** 进度条显隐 */
        fun onProgress(show: Boolean)

        /** 整屏逐行翻译完成（成功行数 / 总行数 / 失败行数） */
        fun onBatchDone(okCount: Int, total: Int, failed: Int)

        /** 单行翻译完成（行索引 / 译文；译文为空表示失败） */
        fun onSingleDone(lineIndex: Int, translated: String?)

        /** 是否还有请求在飞（宿主用来禁用按钮） */
        fun onBusy(busy: Boolean)
    }

    companion object {
        private const val TAG = "ScreenTranslator"

        /** 逐行翻译的并发上限：太高会被引擎限流（429），太低整屏要等很久 */
        private const val MAX_PARALLEL = 4

        /** OCR 输入图（= 显示图）最长边上限 */
        const val MAX_OCR_SIDE = 2000
    }

    /** 当前冻结帧。贴片位置、亮度取样、点选判定都以它为准 */
    var frame: Bitmap? = null
        private set

    /** 识别到的行；`box` 与 [frame] 同坐标系 */
    var lines: List<OcrEngine.Line> = emptyList()
        private set

    /** OCR 拼出的整屏原文（供整段翻译与历史使用） */
    var sourceText: String = ""
        private set

    /** 行索引 → 译文 */
    val translations = HashMap<Int, String>()

    /** 行索引 → 贴片 View */
    private val chips = HashMap<Int, View>()

    /** 正在翻译的行索引（高亮"哪几行在等"） */
    val inFlight = HashSet<Int>()

    /** 翻译是否在进行中 */
    var busy = false
        private set

    /**
     * 点选回调：用户在冻结帧上点了一下（屏幕坐标）。
     * 宿主决定要不要翻 —— 引擎只负责把屏幕坐标换成"第几行"。
     */
    var onTapLine: ((Float, Float) -> Unit)? = null

    /**
     * 「按住看原文」的提示回调（进入时 true，退出时 false）。
     * 宿主用来改状态文案。
     */
    var onPeekChanged: ((peek: Boolean) -> Unit)? = null

    // ==================== 位图接管 ====================

    /**
     * 接管一张新图：重置贴片与识别结果，并把 [bmp] 记为当前冻结帧。
     *
     * 调用方负责把这张图设给 ImageView —— 引擎不持有 View 的引用更新职责，
     * 因为"怎么显示"（FIT_CENTER / CENTER_CROP）由宿主决定，
     * 而 [frameTransform] 必须与宿主采用的是**同一套规则**。
     */
    fun setFrame(bmp: Bitmap?) {
        frame = bmp
        clearChips()
        lines = emptyList()
        sourceText = ""
        inFlight.clear()
        invalidateGeometry()
    }

    /** 仅更新识别结果（图不变，只是重新识别了一批行） */
    fun setLines(scanned: List<OcrEngine.Line>) {
        lines = scanned
        sourceText = OcrEngine.toPlainText(scanned)
        host.onSourceReady(sourceText)
        invalidateGeometry()
    }

    /** 清空所有贴片与译文（换图 / 重拍时调） */
    fun clearChips() {
        host.overlayHost.removeAllViews()
        host.overlayHost.visibility = View.VISIBLE
        chips.clear()
        translations.clear()
    }

    /**
     * 通知几何变化（尺寸变化 / 图变化）→ 手势层重绘。
     * 宿主自己的手势 View 需要 invalidate 时，实现 [Host] 时顺手做即可；
     * 这里只负责让引擎内部的换算缓存失效 —— 现在没有缓存，保留钩子是为了
     * 将来加缓存时不必改调用点。
     */
    fun invalidateGeometry() {
        // 贴片是按新旧图各自算的，图换了必须把旧贴片清掉重贴（由 setFrame 负责）。
        // 这里对齐"贴片跟图走"的不变量：图换了但贴片还在 = 必错位。
    }

    // ==================== 几何换算 ====================

    private fun density(): Float = host.context.resources.displayMetrics.density

    private fun dp(v: Int): Int = (v * density()).toInt()

    /**
     * 冻结帧在屏幕上的实际显示矩形。
     *
     * 规则与 `ImageView.ScaleType.CENTER_CROP`（以及 `PreviewView.FILL_CENTER`）一致：
     * `scale = max(vw/bw, vh/bh)`，然后居中（超出的部分被裁掉）。
     *
     * **必须按 max 而不是 min** —— 用 min（FIT_CENTER）算出来的贴片位置会整体
     * 偏移一圈留白，正是"贴歪了"这种反馈的成因。
     *
     * 本方法刻意**不做成 open**：引擎的两个宿主（拍照翻译 / 图片翻译）都统一用
     * CENTER_CROP，规则只有一套才有"改一处、两处同步"。哪天真要让某个宿主用
     * 别的规则，正确做法是把规则本身抽成引擎的构造参数，而不是各自 override ——
     * override 会让"同源"这个不变量重新变得靠人自觉。
     */
    fun frameTransform(): RectF? {
        val bmp = frame ?: return null
        val vw = host.rootView.width.toFloat()
        val vh = host.rootView.height.toFloat()
        if (vw <= 0f || vh <= 0f || bmp.width <= 0 || bmp.height <= 0) return null
        val scale = maxOf(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }

    /** 位图坐标 → 屏幕坐标（贴片定位用） */
    fun bitmapRectToView(r: Rect): RectF? {
        val bmp = frame ?: return null
        val f = frameTransform() ?: return null
        val k = f.width() / bmp.width
        return RectF(
            f.left + r.left * k,
            f.top + r.top * k,
            f.left + r.right * k,
            f.top + r.bottom * k
        )
    }

    /** 屏幕坐标 → 位图坐标（点选判定用） */
    fun viewPointToBitmap(x: Float, y: Float): FloatArray? {
        val bmp = frame ?: return null
        val f = frameTransform() ?: return null
        val k = f.width() / bmp.width
        if (k <= 0f) return null
        return floatArrayOf((x - f.left) / k, (y - f.top) / k)
    }

    /**
     * 点选判定：优先"包含该点的最小文字框"（点在字上），
     * 否则退化为容差 [TAP_TOLERANCE_DP] 内最近的一行（点偏一点点也能用）。
     */
    fun pickLineAt(viewX: Float, viewY: Float): Int? {
        if (lines.isEmpty()) return null
        val p = viewPointToBitmap(viewX, viewY) ?: return null
        val bx = p[0]
        val by = p[1]

        var best = -1
        var bestArea = Long.MAX_VALUE
        lines.forEachIndexed { i, l ->
            val b = l.box
            if (bx >= b.left && bx <= b.right && by >= b.top && by <= b.bottom) {
                val area = b.width().toLong() * b.height().toLong()
                if (area in 1 until bestArea) {
                    bestArea = area
                    best = i
                }
            }
        }
        if (best >= 0) return best

        val f = frameTransform() ?: return null
        val bmp = frame ?: return null
        val k = f.width() / bmp.width
        val tol = if (k > 0f) dp(TAP_TOLERANCE_DP) / k else 0f

        var nearest = -1
        var bestDist = Float.MAX_VALUE
        lines.forEachIndexed { i, l ->
            val dx = maxOf(l.box.left - bx, 0f, bx - l.box.right)
            val dy = maxOf(l.box.top - by, 0f, by - l.box.bottom)
            val d = hypot(dx, dy)
            if (d < bestDist) {
                bestDist = d
                nearest = i
            }
        }
        return if (nearest >= 0 && bestDist <= tol) nearest else null
    }

    // ==================== 译文贴片 ====================

    /**
     * 把译文**盖在原文上**（与谷歌拍照翻译一致：看到的就是译文）。
     * 不透明背景 + 宽高都不小于原文框，背景明暗按原文框周边取样自适应。
     * 译文更长时向右下自然生长（上限是屏幕内边距）。
     */
    fun showChip(index: Int, translated: String) {
        val box = lines.getOrNull(index)?.box ?: return
        val v = bitmapRectToView(box) ?: return
        val ctx = host.context

        chips.remove(index)?.let { host.overlayHost.removeView(it) }

        val density = ctx.resources.displayMetrics.density
        // 原文字高一行的框高 ≈ 字号 × 1.4，反推字号，让译文尽量占满原文框
        val sizeSp = (v.height() / density * 0.72f).coerceIn(9f, 18f)
        val lightBackground = isLightAround(box)
        val bg = if (lightBackground) Color.rgb(250, 250, 250) else Color.rgb(16, 16, 16)
        val fg = if (lightBackground) Color.rgb(16, 16, 16) else Color.rgb(248, 248, 248)

        val chip = TextView(ctx).apply {
            text = translated
            textSize = sizeSp
            setTextColor(fg)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(1), dp(4), dp(1))
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(bg)
                setStroke(
                    dp(1),
                    if (lightBackground) Color.argb(45, 0, 0, 0) else Color.argb(60, 255, 255, 255)
                )
            }
            // 宽高都不小于原文框 —— 这样原文被真正盖住，不是"贴在旁边"
            minWidth = v.width().toInt().coerceAtLeast(dp(20))
            minHeight = v.height().toInt().coerceAtLeast(dp(14))
            val rootW = host.rootView.width
            maxWidth = (rootW - v.left - dp(8)).toInt().coerceAtLeast(dp(72))
            isClickable = false
            isFocusable = false
        }

        val rootH = host.rootView.height
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = v.left.toInt().coerceIn(0, maxOf(0, host.rootView.width - dp(72)))
            topMargin = v.top.toInt().coerceIn(0, maxOf(0, rootH - dp(24)))
        }
        host.overlayHost.addView(chip, lp)
        chips[index] = chip
    }

    /**
     * 原文框周边是浅底还是深底（决定贴片用浅色块还是深色块）。
     *
     * 在框内取 6×3 个点算平均亮度即可：块要盖住这一行，只要跟这行的底色接近就不会突兀。
     * 采样点少是有意的 —— 每多一个点就多一次 `getPixel`，而这是主线程上的渲染路径。
     */
    fun isLightAround(box: Rect): Boolean {
        val bmp = frame ?: return false
        val l = box.left.coerceIn(0, bmp.width - 1)
        val r = box.right.coerceIn(l + 1, bmp.width)
        val t = box.top.coerceIn(0, bmp.height - 1)
        val b = box.bottom.coerceIn(t + 1, bmp.height)
        val cols = 6
        val rows = 3
        var sum = 0.0
        var n = 0
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                val x = (l + (r - l) * i / cols).coerceIn(0, bmp.width - 1)
                val y = (t + (b - t) * j / rows).coerceIn(0, bmp.height - 1)
                val c = runCatching { bmp.getPixel(x, y) }.getOrDefault(Color.BLACK)
                sum += (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
                n++
            }
        }
        return n > 0 && sum / n > 0.55
    }

    /** 未译行的细框 + 正在翻译的高亮框。宿主把它画在自己的手势层里（见 [drawPendingLines]） */
    fun drawPendingLines(canvas: Canvas, peekActive: Boolean) {
        if (peekActive || lines.isEmpty()) return
        lines.forEachIndexed { i, line ->
            if (translations.containsKey(i)) return@forEachIndexed
            val v = bitmapRectToView(line.box) ?: return@forEachIndexed
            if (inFlight.contains(i)) {
                canvas.drawRoundRect(v, dp(4).toFloat(), dp(4).toFloat(), HOT_FILL)
                canvas.drawRoundRect(v, dp(4).toFloat(), dp(4).toFloat(), HOT_STROKE)
            } else {
                canvas.drawRoundRect(v, dp(3).toFloat(), dp(3).toFloat(), PENDING_STROKE)
            }
        }
    }

    // ==================== 翻译调度 ====================

    /**
     * 逐行翻译 [indices] 并把译文贴回各自原位。
     *
     * 三点控制住"行数多 = 请求多"的成本：
     * 1. **同样的文字只发一次请求**（菜单里"￥38"这种重复行很常见），结果分发给所有同文行；
     * 2. 并发上限 [MAX_PARALLEL]，避免被引擎限流；
     * 3. 引擎外面的 [com.hunter.screentranslator.api.CachingTranslator] 按原文命中缓存 ——
     *    重复翻译同一张图/同一句，零请求零费用。
     *
     * [translator] 传 null 时内部取 [TranslatorFactory.current]（宿主有自定义需求可自己传）。
     */
    fun translateLines(
        indices: List<Int>,
        single: Boolean,
        scope: kotlinx.coroutines.CoroutineScope,
        translator: com.hunter.screentranslator.api.Translator? = null
    ) {
        val targets = indices.filter { it in lines.indices }
        if (targets.isEmpty()) return
        busy = true
        host.onBusy(true)
        host.onProgress(true)

        val total = targets.size
        var finished = 0
        var failed = 0
        val progressText: (Int) -> String = {
            if (single) "翻译中…" else "翻译中 $it/$total 行…"
        }
        host.onStatus(progressText(0))

        scope.launch {
            val tr = translator ?: TranslatorFactory.current()
            val target = App.prefs.targetLang

            // 同文行合并成一组：一组 = 一次请求
            val groups = LinkedHashMap<String, MutableList<Int>>()
            for (i in targets) {
                val key = lines[i].text.trim()
                if (key.isEmpty()) continue
                groups.getOrPut(key) { mutableListOf() }.add(i)
            }

            val semaphore = Semaphore(MAX_PARALLEL)
            groups.values.map { group ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        withContext(Dispatchers.Main) {
                            inFlight.addAll(group)
                            onInFlightChanged()
                        }
                        val text = lines[group.first()].text.trim()
                        val result = runCatching { tr.translate(text, target, App.prefs.sourceLang) }
                            .getOrElse { Result.failure(it) }
                        withContext(Dispatchers.Main) {
                            inFlight.removeAll(group.toSet())
                            val out = result.getOrNull()
                            if (!out.isNullOrBlank()) {
                                group.forEach { i ->
                                    translations[i] = out
                                    showChip(i, out)
                                }
                            } else {
                                failed += group.size
                                result.exceptionOrNull()?.let { e ->
                                    Log.w(TAG, "行翻译失败: ${e.message}")
                                }
                            }
                            finished += group.size
                            if (!single) host.onStatus(progressText(finished))
                            onInFlightChanged()
                        }
                    }
                }
            }.awaitAll()

            busy = false
            host.onBusy(false)
            host.onProgress(false)

            if (single) {
                val i = targets.first()
                host.onSingleDone(i, translations[i])
            } else {
                val ok = targets.count { translations.containsKey(it) }
                host.onBatchDone(ok, total, failed)
            }
        }
    }

    /** 让宿主重绘手势层（细框/高亮框） */
    private fun onInFlightChanged() {
        (host.overlayHost.parent as? ViewGroup)?.let { _ -> }
        lineLayerInvalidator?.invoke()
    }

    /**
     * 手势层重绘钩子。宿主（Activity）在实现 [Host] 时注入自己的 `lineLayer.invalidate()`。
     *
     * 用回调而不是让引擎持有那个 View：手势层是宿主自建的内部 View 类
     * （要处理长按看原文），引擎不该知道它长什么样，只需要"让它重画"。
     */
    var lineLayerInvalidator: (() -> Unit)? = null

    /** 当前是否处于"按住看原文"态（宿主的手势层维护，贴片显隐由它控制） */
    var peekActive = false
        private set

    /** 宿主的手势层进入/退出看原文时通知引擎，引擎据此回调 [onPeekChanged] */
    fun setPeek(active: Boolean) {
        if (peekActive == active) return
        peekActive = active
        host.overlayHost.visibility = if (active) View.INVISIBLE else View.VISIBLE
        onPeekChanged?.invoke(active)
        lineLayerInvalidator?.invoke()
    }

    // ==================== 工具 ====================

    /**
     * 缩到最长边不超过 [maxSide] 再做 OCR。
     * ML Kit 对超大图会自己缩，但先缩能省内存 —— 相机/相册出图常有 4000px 级别。
     */
    fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        return runCatching {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }.getOrDefault(src)
    }

    /** 全屏状态下"点了一行"时给引擎用：把屏幕坐标转成行索引并回调宿主 */
    fun handleTap(x: Float, y: Float) {
        onTapLine?.invoke(x, y)
    }
}

/** 未译行的细框（白半透明描边） */
private val PENDING_STROKE = Paint().apply {
    style = Paint.Style.STROKE
    strokeWidth = 1.5f
    color = Color.argb(110, 255, 255, 255)
    isAntiAlias = true
}

/** 正在翻译的行：高亮描边 */
private val HOT_STROKE = Paint().apply {
    style = Paint.Style.STROKE
    strokeWidth = 2.5f
    color = Color.argb(235, 120, 200, 255)
    isAntiAlias = true
}

/** 正在翻译的行：高亮填充 */
private val HOT_FILL = Paint().apply {
    style = Paint.Style.FILL
    color = Color.argb(60, 120, 200, 255)
}

/** 点选容差（dp）：没点进文字框时，取这个范围内最近的一行 */
private const val TAP_TOLERANCE_DP = 40

/**
 * 按下多久算「按住看原文」（ms）。
 * 比 [android.view.ViewConfiguration.getLongPressTimeout]（500ms）短一些：
 * 这是"偷看一眼原文"，手感要跟得上手指，不能等半秒。
 */
const val PEEK_DELAY_MS = 220L

/** 「按住看原文」手势的最小移动容差（dp）：超过即视为拖动/误触 */
const val PEEK_MOVE_TOLERANCE_DP = 12

/** 处理长按看原文的通用手势层基类 —— 拍照翻译与图片翻译共用同一套手感 */
abstract class PeekLineLayer(ctx: Context) : View(ctx) {

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var peekActive = false

    /** 长按计时器是否已挂上（避免 DOWN 时重复 post） */
    private var peekScheduled = false

    private val peekRunnable = Runnable { startPeek() }

    /** 当前是否处于"看原文"态 */
    val isPeeking: Boolean get() = peekActive

    /** 有冻结帧才有"原文"可看 —— 取景时返回 false */
    abstract fun hasFrame(): Boolean

    /** 进入看原文（宿主藏贴片 + 改文案） */
    abstract fun onPeekStart()

    /** 退出看原文（宿主恢复贴片） */
    abstract fun onPeekEnd()

    /** 轻点（非长按）时回调 */
    abstract fun onTap(x: Float, y: Float)

    init {
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val tol = 12 * density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = SystemClock.uptimeMillis()
                // 冻结帧上：按住一段时间 = 看原文（松手恢复）。取景时没有译文可藏，不挂计时器。
                if (hasFrame()) {
                    peekScheduled = true
                    postDelayed(peekRunnable, PEEK_DELAY_MS)
                }
                // 按下即给个即时反馈，否则"按住"要等 220ms 才看出反应
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 手指移动超过阈值 = 误触/拖动：立刻取消"按住看原文"，避免边滑边闪
                if (hypot(event.x - downX, event.y - downY) > tol) cancelPeek()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasPeeking = peekActive
                cancelPeek()
                val moved = hypot(event.x - downX, event.y - downY)
                // 轻点才算点选；刚刚是在看原文（长按过）就不当作点选
                if (!wasPeeking && moved < tol && SystemClock.uptimeMillis() - downAt < 900) {
                    onTap(downX, downY)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPeek()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 进入"看原文"：藏掉所有译文贴片，露出底下的原始画面 */
    private fun startPeek() {
        peekScheduled = false
        if (!hasFrame() || peekActive) return
        peekActive = true
        onPeekStart()
        invalidate()
    }

    /** 强制退出"看原文"态（重拍 / 重新选图前调用），不改状态文案 */
    fun resetPeek() {
        if (peekScheduled) {
            removeCallbacks(peekRunnable)
            peekScheduled = false
        }
        if (!peekActive) return
        peekActive = false
        onPeekEnd()
        invalidate()
    }

    /** 退出"看原文"：恢复贴片。未激活时只清理待触发的计时器 */
    private fun cancelPeek() {
        if (peekScheduled) {
            removeCallbacks(peekRunnable)
            peekScheduled = false
        }
        if (!peekActive) return
        peekActive = false
        onPeekEnd()
        invalidate()
    }
}
