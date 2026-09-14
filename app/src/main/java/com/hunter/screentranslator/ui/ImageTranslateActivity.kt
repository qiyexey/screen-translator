package com.hunter.screentranslator.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.util.OcrEngine
import com.hunter.screentranslator.util.HistoryStore
import com.hunter.screentranslator.util.ScreenCapture
import com.hunter.screentranslator.util.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * v1.8.0 图片翻译。
 *
 * 流程：申请 MediaProjection 授权（按需，用完立刻释放）→ 抓一帧 →
 * 用户在截图上拖框选区域 → 裁切 → 交给当前引擎的多模态接口翻译 → 显示译文。
 *
 * 为什么不用 OCR：直接把图交给 VLM 一步出译文，识别率更高（能懂版式/上下文），
 * 且不需要引入 Tesseract/ML Kit 等额外依赖或 GMS 环境要求。
 */
class ImageTranslateActivity : AppCompatActivity() {

    private lateinit var ivShot: ImageView
    private lateinit var overlay: RegionOverlay
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvSource: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnSpeak: Button
    private lateinit var btnRetry: Button
    private lateinit var btnFull: Button

    private var screenshot: Bitmap? = null
    private var lastTranslated: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestCapture()
    }

    // ==================== UI（代码构建，避免再往 1213 行的 activity_main.xml 里堆）====================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        tvStatus = TextView(this).apply {
            text = "正在申请屏幕截图权限…"
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 13f
        }
        root.addView(tvStatus)

        // 截图区：ImageView + 一个透明的手势遮罩（画选框）
        val shotWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8) }
        }
        ivShot = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        overlay = RegionOverlay(this)
        shotWrap.addView(ivShot, FrameLayout.LayoutParams(-1, -1))
        shotWrap.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(shotWrap)

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(progress)

        // 结果区
        val resultScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvSource = TextView(this).apply {
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 12f
            visibility = View.GONE
        }
        tvResult = TextView(this).apply {
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            textSize = 15f
        }
        resultBox.addView(tvSource)
        resultBox.addView(tvResult)
        resultScroll.addView(resultBox)
        root.addView(resultScroll)

        // 按钮行
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        btnRetry = Button(this).apply {
            text = "重新截图"
            setOnClickListener { requestCapture() }
        }
        btnFull = Button(this).apply {
            text = "翻译整张图"
            setOnClickListener { screenshot?.let { translateBitmap(it, "整图") } }
        }
        btnSpeak = Button(this).apply {
            text = "🔊 朗读"
            isEnabled = false
            setOnClickListener {
                // v1.9.3：跟随「朗读内容」设置。图片翻译拿不到原文文字
                //（源文字只在图里，没有 OCR），所以 source 传空 ——
                // speakContent 会自动退化为只读译文，不会出现"读半句"。
                if (lastTranslated.isNotBlank()) {
                    Speaker.speakContent(
                        this@ImageTranslateActivity, "", lastTranslated
                    )
                }
            }
        }
        row.addView(btnRetry, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btnFull, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btnSpeak, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        return root
    }

    // ==================== 截图授权（按需）====================

    private fun requestCapture() {
        // v1.15.22：不再因为"引擎读不了图"就拦下来。
        // 读不了图时会在下面自动改走「本机 OCR + 文本翻译」——
        // 配免密钥的必应网页端就是一条完全免费的图片翻译链路。
        tvStatus.text = "正在申请屏幕截图权限…"
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("startActivityForResult 简单直接，图片翻译无需 Result API 的额外复杂度")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            tvStatus.text = "未获得截图授权。图片翻译需要该权限才能读取屏幕内容。"
            return
        }
        tvStatus.text = "正在抓取屏幕…"
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                var mp: MediaProjection? = null
                try {
                    mp = mpm.getMediaProjection(resultCode, data)
                    if (mp == null) {
                        Log.e(TAG, "getMediaProjection 返回 null")
                        null
                    } else {
                        ScreenCapture.captureOnce(this@ImageTranslateActivity, mp)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "截图异常: $e")
                    null
                } finally {
                    // 关键：抓完立刻释放投影，不常驻、不持续耗电
                    runCatching { mp?.stop() }
                }
            }
            if (bmp == null) {
                tvStatus.text = "截图失败。可能是系统限制了截屏，请重试。"
                return@launch
            }
            screenshot = bmp
            ivShot.setImageBitmap(bmp)
            overlay.setBitmapSize(bmp.width, bmp.height)
            tvStatus.text = "在图上拖动框选要翻译的区域，松手即翻译。"
            tvResult.text = ""
            tvSource.visibility = View.GONE
            btnSpeak.isEnabled = false
        }
    }

    // ==================== 翻译 ====================

    private fun onRegionSelected(rectInBitmap: Rect) {
        val shot = screenshot ?: return
        if (rectInBitmap.width() < 8 || rectInBitmap.height() < 8) {
            toast("选区太小，请拖出一个更大的框")
            return
        }
        // 裁切选区（越界钳制，避免 createBitmap 抛异常）
        val l = rectInBitmap.left.coerceIn(0, shot.width - 1)
        val t = rectInBitmap.top.coerceIn(0, shot.height - 1)
        val r = rectInBitmap.right.coerceIn(l + 1, shot.width)
        val b = rectInBitmap.bottom.coerceIn(t + 1, shot.height)
        val cropped = runCatching {
            Bitmap.createBitmap(shot, l, t, r - l, b - t)
        }.getOrNull()
        if (cropped == null) {
            toast("裁切失败，请重试")
            return
        }
        translateBitmap(cropped, "框选区域")
    }

    /** 当前引擎每秒可接受的一次图片翻译请求是否进行中 */
    private var translating = false

    private fun translateBitmap(bmp: Bitmap, label: String) {
        if (translating) {
            toast("正在翻译中，请稍候")
            return
        }
        translating = true
        progress.visibility = View.VISIBLE
        tvStatus.text = "正在翻译（$label）…"

        lifecycleScope.launch {
            // 压缩成 JPEG 再上传：PNG 截图动辄数 MB，base64 后更大，可能超出请求体上限。
            // 质量 85 + 最长边 1600px 是清晰度与体积的折中（文字仍清晰可辨）。
            val bytes = withContext(Dispatchers.IO) { compressForUpload(bmp) }
            if (bytes == null) {
                translating = false
                progress.visibility = View.GONE
                tvStatus.text = "图片编码失败"
                return@launch
            }

            val translator = TranslatorFactory.current()
            val engine = TranslationEngine.fromKey(App.prefs.engine)
            val result = if (engine.visionCapable) {
                translator.translateImage(bytes, "image/jpeg", App.prefs.targetLang)
            } else {
                // 引擎只能翻文字 → 在本机把图上的日文认出来，再走文本翻译。
                // 识别不花钱、不联网、图片不出设备；只有认出的文字发给引擎。
                tvStatus.text = "本机识别中（不走网络）…"
                val lines = runCatching {
                    withContext(Dispatchers.IO) { OcrEngine.recognizeJapanese(bmp) }
                }.getOrElse { emptyList() }
                val src = OcrEngine.toPlainText(lines)
                if (src.isBlank()) {
                    Result.failure(RuntimeException("本机没认出文字（换个引擎，或框得更准些）"))
                } else {
                    tvStatus.text = "识别到 ${lines.size} 行，正在翻译…"
                    translator.translate(src, App.prefs.targetLang)
                }
            }

            translating = false
            progress.visibility = View.GONE
            result.fold(
                onSuccess = { out ->
                    lastTranslated = out
                    tvSource.visibility = View.VISIBLE
                    tvSource.text = "（$label，已识别并翻译）"
                    tvResult.text = out
                    btnSpeak.isEnabled = true
                    tvStatus.text = "翻译完成。可点「🔊 朗读」或重新框选。"
                    // v1.8.0：图片翻译也进历史（原文为空，因为识别文本由模型读出来）
                    runCatching {
                        HistoryStore.add(
                            source = "",
                            translated = out,
                            mode = "🖼 图片($label)",
                            targetLang = App.prefs.targetLang,
                            engine = App.prefs.engine
                        )
                    }
                    if (App.prefs.ttsAutoSpeak && out.isNotBlank()) {
                        // v1.9.3：跟随「朗读内容」设置；图片翻译无原文文字，自动退化为只读译文
                        Speaker.speakContent(this@ImageTranslateActivity, "", out)
                    }
                },
                onFailure = { e ->
                    tvResult.text = ""
                    tvStatus.text = "翻译失败：${e.message}\n\n" +
                        "常见原因：\n" +
                        "1. 当前模型不支持图片（换 gpt-4o / claude-3-5-sonnet / qwen3.7-plus 等）\n" +
                        "2. 走的是自定义中转，中转方未实现多模态\n" +
                        "3. 账户额度不足或网络不通"
                }
            )
        }
    }

    /** 缩放到最长边不超过 1600px 并转 JPEG，控制上传体积 */
    private fun compressForUpload(src: Bitmap): ByteArray? = runCatching {
        val maxSide = 1600
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
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
            target.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            if (target !== src) target.recycle()
            bos.toByteArray()
        }
    }.getOrNull()

    override fun onDestroy() {
        // 投影已在抓帧后立刻 stop（见 onActivityResult），这里无需再处理
        screenshot?.recycle()
        screenshot = null
        super.onDestroy()
    }

    // ==================== 框选遮罩 ====================

    /**
     * 覆盖在 ImageView 上的手势层：拖动画矩形，松手回调。
     * 内部把"视图坐标"换算成"位图坐标"，因为 ImageView 是 FIT_CENTER，
     * 实际显示区域周围有留白，不做换算会选错位置。
     */
    private inner class RegionOverlay(ctx: Context) : View(ctx) {
        private var bmpW = 0
        private var bmpH = 0
        private var startX = 0f
        private var startY = 0f
        private var curX = 0f
        private var curY = 0f
        private var dragging = false

        private val paint = android.graphics.Paint().apply {
            color = themeColor(com.google.android.material.R.attr.colorPrimary)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val fill = android.graphics.Paint().apply {
            color = 0x334CAF50
            style = android.graphics.Paint.Style.FILL
        }

        fun setBitmapSize(w: Int, h: Int) {
            bmpW = w
            bmpH = h
        }

        /** 位图在视图中的实际显示矩形（FIT_CENTER 留白） */
        private fun displayRect(): Rect {
            val vw = width.toFloat()
            val vh = height.toFloat()
            if (bmpW <= 0 || bmpH <= 0 || vw <= 0 || vh <= 0) {
                return Rect(0, 0, width, height)
            }
            val scale = minOf(vw / bmpW, vh / bmpH)
            val dw = bmpW * scale
            val dh = bmpH * scale
            val left = (vw - dw) / 2f
            val top = (vh - dh) / 2f
            return Rect(left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt())
        }

        /** 视图坐标 → 位图坐标 */
        private fun toBitmap(x: Float, y: Float): Pair<Int, Int> {
            val d = displayRect()
            if (d.width() <= 0 || d.height() <= 0 || bmpW <= 0) return 0 to 0
            val bx = ((x - d.left) / d.width() * bmpW).toInt().coerceIn(0, bmpW)
            val by = ((y - d.top) / d.height() * bmpH).toInt().coerceIn(0, bmpH)
            return bx to by
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y
                    curX = startX; curY = startY
                    dragging = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        curX = event.x; curY = event.y
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return true
                    dragging = false
                    invalidate()
                    val (x1, y1) = toBitmap(minOf(startX, curX), minOf(startY, curY))
                    val (x2, y2) = toBitmap(maxOf(startX, curX), maxOf(startY, curY))
                    if (x2 - x1 >= 8 && y2 - y1 >= 8) {
                        onRegionSelected(Rect(x1, y1, x2, y2))
                    }
                    // 画完即清除选框，避免遮挡
                    dragging = false
                    invalidate()
                }
            }
            return true
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (!dragging) return
            val l = minOf(startX, curX)
            val t = minOf(startY, curY)
            val r = maxOf(startX, curX)
            val b = maxOf(startY, curY)
            canvas.drawRect(l, t, r, b, fill)
            canvas.drawRect(l, t, r, b, paint)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "ScreenTranslator"
        private const val REQ_PROJECTION = 1001
    }

    /** 从当前主题解析 M3 语义色（这样代码构建的界面也能跟随明暗主题） */
    private fun themeColor(attrRes: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attrRes, tv, true)
        return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId) else tv.data
    }
}
