package com.hunter.screentranslator.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.util.EdgeToEdge
import com.hunter.screentranslator.util.LineOverlayEngine
import com.hunter.screentranslator.util.OcrEngine
import com.hunter.screentranslator.util.HistoryStore
import com.hunter.screentranslator.util.PeekLineLayer
import com.hunter.screentranslator.util.ScreenCapture
import com.hunter.screentranslator.util.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * v1.8.0 图片翻译。
 *
 * ## v1.25.0：入口从「只能截屏」改成「相册为主、截屏为辅」
 *
 * 原来本页一进来就强制申请 MediaProjection（屏幕录制授权），用户想翻一张
 * **相册里的图片**（照片、截图、别人发的图）时完全没有入口 ——
 * 而"选一张图来翻"恰恰是图片翻译最常用的诉求。
 *
 * 现在改成两个来源并列：
 *
 *   1. **从相册选择**（默认主入口）：走 Android 13+ 的系统照片选择器
 *      （[ActivityResultContracts.PickVisualMedia]）。选它有三个好处 ——
 *      **不需要任何存储权限**（系统代选，App 只拿到用户挑中的那一张）、
 *      界面是用户熟悉的系统相册、且适配 Android 14 的"部分照片访问"。
 *   2. **截取屏幕**：保留原来的 MediaProjection 链路，用于"翻译正在看的页面"。
 *
 * 进页**不再自动弹授权**（那会打断只想选图的人），由用户点按钮决定走哪条。
 *
 * ## v1.25.0：译文默认**原位覆盖**在原文上（两种模式可切换）
 *
 * "在图上拖框 → 译文出现在下面的文本框"这个交互是 v1.8.0 的产物，它有个
 * 很实际的问题：**译文与原文位置完全脱钩**。一张菜单、一块路牌、一个表单，
 * 用户读完下面那坨文字还得回图上找"这是哪一行翻的"，多行内容里基本对不上。
 *
 * 所以默认改成与拍照翻译一致的呈现方式 —— **逐行 OCR → 每行译文盖回它原来的位置**
 * （[LineOverlayEngine] 提供，与拍照翻译共用同一份实现，行为不会漂移）：
 *
 *   - 选图后**自动整屏逐行贴合**，看到的就是译文；
 *   - 点图上某一行 → 只翻那一行（其余行不动）；
 *   - **按住屏幕** → 所有贴片隐藏、露出原文，松手恢复；
 *   - 底部「📄 全文」→ 一次整段翻译（带上下文、译文更连贯），结果在下方的卡片里。
 *
 * 两种模式（[MODE_OVERLAY] / [MODE_REGION]）用顶部开关切换，选择记忆到偏好里：
 *
 *   - **原位覆盖**（默认）：上面那套；
 *   - **框选翻译**：v1.8.0 的原始交互，拖框 → 只翻框内 → 结果在下方文本框。
 *     它在"只想要某一段的译文"时仍然好用，所以保留而不是删掉。
 *
 * 为什么用 OCR 而不是直接交给多模态引擎读图：多模态引擎（VLM）能懂版式/上下文，
 * 识别率更高，但它**只返回一段文字**，没有每行的位置 —— 而"原位覆盖"的全部前提
 * 就是位置。所以本页的两条链路是：
 *
 *   - 原位覆盖模式 → 端侧 OCR（[OcrEngine]）拿到带 `box` 的逐行结果 → 逐行文本翻译；
 *   - 框选/整图模式 → 引擎支持读图就整图交给它（[TranslationEngine.visionCapable]），
 *     不支持就在本机 OCR 兜底（见 S25 的方案说明）。
 */
class ImageTranslateActivity : AppCompatActivity() {

    private lateinit var ivShot: ImageView
    private lateinit var lineLayer: LineLayer
    private lateinit var overlayHost: FrameLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvSource: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnSpeak: Button
    private lateinit var btnFull: Button
    private lateinit var btnPickGallery: Button
    private lateinit var btnCapture: Button
    private lateinit var btnMode: Button
    private lateinit var resultBox: LinearLayout

    private var screenshot: Bitmap? = null
    private var lastTranslated: String = ""

    /** 当前呈现模式：[MODE_OVERLAY] 原位覆盖 / [MODE_REGION] 框选 */
    private var mode = MODE_OVERLAY

    /**
     * 逐行 OCR → 译文贴回原位的那套逻辑（与拍照翻译共用同一份实现）。
     * v1.25.0 新增。
     */
    private lateinit var ovEngine: LineOverlayEngine

    /**
     * 从相册选图（v1.25.0）。
     *
     * 用 [ActivityResultContracts.PickVisualMedia] 而不是老的
     * `ACTION_PICK` / `ACTION_GET_CONTENT`：
     *
     *   - **不需要 READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE 权限** ——
     *     系统选择器直接返回用户挑中的那一个 Uri，App 拿不到其它照片。
     *     这既省掉一次权限申请（以及被拒后的一堆兜底分支），
     *     也符合 Android 14 起"最小授权"的方向。
     *   - 在国产 ROM 上会拉起系统相册（ColorOS 实测正常）。
     *
     * 选图后立刻解码成 Bitmap 并走与截屏完全相同的后续流程。
     */
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            // 用户直接返回没选：不报错，回到"等待选图"状态，让他还能点别的按钮。
            if (screenshot == null) tvStatus.text = getString(R.string.image_translate_t13)
            return@registerForActivityResult
        }
        tvStatus.text = getString(R.string.image_translate_t14)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                // 相册原图可能很大（几千万像素），直接全尺寸解码既慢又可能 OOM；
                // 先读尺寸、按需降采样到长边 [MAX_PICK_SIDE]，识别质量几乎无损
                // 但内存占用降一个数量级。
                decodeSampled(this@ImageTranslateActivity, uri, MAX_PICK_SIDE)
            }
            if (bmp == null) {
                tvStatus.text = getString(R.string.image_translate_t15)
                return@launch
            }
            showBitmap(bmp)
            tvStatus.text = getString(R.string.image_translate_t02)
            // v1.25.0：选完图直接开始逐行贴合，不需要用户再点一次
            if (mode == MODE_OVERLAY) autoTranslateOverlay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        EdgeToEdge.install(this)

        mode = App.prefs.imageTranslateMode

        ovEngine = LineOverlayEngine(object : LineOverlayEngine.Host {
            override val context: Context get() = this@ImageTranslateActivity
            override val overlayHost: FrameLayout get() = this@ImageTranslateActivity.overlayHost
            override val rootView: View get() = ivShot.parent as View

            override fun onSourceReady(text: String) {
                // 原位覆盖模式下不往文本框塞原文（占地方且没必要）；
                // 整段翻译时由 translateWhole() 自己取 sourceText。
            }

            override fun onStatus(text: String) {
                tvStatus.text = text
            }

            override fun onProgress(show: Boolean) {
                progress.visibility = if (show) View.VISIBLE else View.GONE
            }

            override fun onBusy(busy: Boolean) {
                btnPickGallery.isEnabled = !busy
                btnFull.isEnabled = !busy
            }

            override fun onBatchDone(okCount: Int, total: Int, failed: Int) {
                tvStatus.text = getString(R.string.image_translate_t18, okCount, total) +
                    (if (failed > 0) getString(R.string.image_translate_t19, failed) else "") +
                    getString(R.string.image_translate_t20)
                recordHistory(okCount)
            }

            override fun onSingleDone(lineIndex: Int, translated: String?) {
                val line = ovEngine.lines.getOrNull(lineIndex) ?: return
                tvStatus.text =
                    if (translated == null) getString(R.string.image_translate_t21)
                    else getString(R.string.image_translate_t22)
                if (translated != null) {
                    recordHistory(1)
                    if (App.prefs.ttsAutoSpeak) {
                        Speaker.speakContent(this@ImageTranslateActivity, line.text, translated)
                    }
                }
            }
        })
        ovEngine.lineLayerInvalidator = { lineLayer.invalidate() }
        ovEngine.onPeekChanged = { peek ->
            tvStatus.text =
                if (peek) getString(R.string.image_translate_t23)
                else if (ovEngine.translations.isNotEmpty())
                    getString(R.string.image_translate_t22)
                else getString(R.string.image_translate_t24)
        }

        applyMode()
        // v1.25.0：不再自动申请截屏授权 —— 用户可能只想从相册选图。
        // 首次进入先把"两条来源"说清楚，由用户点按钮决定。
        tvStatus.text = getString(R.string.image_translate_t13)
    }

    // ==================== UI（代码构建，避免再往 1213 行的 activity_main.xml 里堆）====================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        // ---- 模式切换（v1.25.0）----
        btnMode = Button(this).apply {
            textSize = 13f
            setOnClickListener { toggleMode() }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(btnMode)

        tvStatus = TextView(this).apply {
            text = getString(R.string.image_translate_t13)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        }
        root.addView(tvStatus)

        // 图片区：ImageView + 手势层（画细框/贴片提示）+ 贴片容器
        val shotWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8) }
        }
        // v1.25.0：**从 FIT_CENTER 改成 CENTER_CROP**。
        //
        // 原位覆盖要求"位图坐标 ↔ 屏幕坐标"的换算规则与 ImageView 实际显示规则
        // 完全一致，否则贴片会整体偏移。FIT_CENTER 会在图片周围留白，
        // 而且留白宽度随图片宽高比变化，换算稍有不慎就贴歪 ——
        // 拍照翻译用的是 CENTER_CROP（铺满 + 居中裁切），这里跟它对齐，
        // 既让两处行为一致，也避免"图片两侧黑边里冒出贴片"这种观感问题。
        //
        // 代价：极端宽高比的图会被裁掉一部分。但相册里的照片/截图基本是
        // 手机比例的，裁切量很小；而"贴歪"是不可接受的。
        ivShot = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        overlayHost = FrameLayout(this).apply { isClickable = false }
        lineLayer = LineLayer(this)
        shotWrap.addView(ivShot, FrameLayout.LayoutParams(-1, -1))
        shotWrap.addView(overlayHost, FrameLayout.LayoutParams(-1, -1))
        shotWrap.addView(lineLayer, FrameLayout.LayoutParams(-1, -1))
        root.addView(shotWrap)

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(progress)

        // 结果区（框选模式 / 「📄 全文」整段翻译用）
        val resultScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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

        // v1.25.0：来源按钮行拆成两行 —— 第一行是"选图/截图"（来源），
        // 第二行是"重翻/朗读"（对已选图片的操作）。
        // 原来三者挤一行，加了"从相册选择"后会变成四个等宽按钮，
        // 窄屏上文字会被截断。
        val srcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        btnPickGallery = Button(this).apply {
            text = getString(R.string.image_translate_btn_gallery)
            setOnClickListener {
                // 不传类型参数 = 只让用户选图片（不含视频），
                // 且优先用系统照片选择器（不需要任何权限）。
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }
        btnCapture = Button(this).apply {
            text = getString(R.string.image_translate_btn_capture)
            setOnClickListener { requestCapture() }
        }
        srcRow.addView(btnPickGallery, LinearLayout.LayoutParams(0, -2, 1f))
        srcRow.addView(btnCapture, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(srcRow)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        btnFull = Button(this).apply {
            text = getString(R.string.image_translate_btn_full)
            setOnClickListener { onFullClick() }
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
        row.addView(btnFull, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btnSpeak, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        return root
    }

    // ==================== 模式切换 ====================

    private fun applyMode() {
        btnMode.text = if (mode == MODE_OVERLAY)
            getString(R.string.image_translate_mode_overlay)
        else
            getString(R.string.image_translate_mode_region)
        btnFull.text = if (mode == MODE_OVERLAY)
            getString(R.string.image_translate_btn_whole)
        else
            getString(R.string.image_translate_btn_full)
        val overlay = mode == MODE_OVERLAY
        // 框选遮罩只在框选模式下工作：原位覆盖模式里点一下是"翻这一行"，
        // 拖动不应该画出一个选框来（那会让人以为还能框选）。
        lineLayer.onTapEnabled = overlay
        overlayHost.visibility = View.VISIBLE
    }

    private fun toggleMode() {
        mode = if (mode == MODE_OVERLAY) MODE_REGION else MODE_OVERLAY
        App.prefs.imageTranslateMode = mode
        ovEngine.clearChips()
        ovEngine.setFrame(null)
        lineLayer.resetPeek()
        lineLayer.invalidate()
        tvResult.text = ""
        tvSource.visibility = View.GONE
        btnSpeak.isEnabled = false
        applyMode()
        tvStatus.text = if (mode == MODE_OVERLAY)
            getString(R.string.image_translate_t25)
        else
            getString(R.string.image_translate_t02)
        // 已经选好图了就按新模式立刻重来一遍，省得用户再点一次
        screenshot?.let {
            if (mode == MODE_OVERLAY) {
                ovEngine.setFrame(it)
                autoTranslateOverlay()
            }
        }
    }

    /**
     * 把一张位图设为当前待翻译图，并重置选区/结果。
     *
     * v1.25.0 抽出来给"相册选图"和"截屏"两条路共用 ——
     * 之前这段逻辑内联在 onActivityResult 里，两条路各写一遍必然会漂移。
     */
    private fun showBitmap(bmp: Bitmap) {
        screenshot = bmp
        ivShot.setImageBitmap(bmp)
        tvResult.text = ""
        tvSource.visibility = View.GONE
        btnSpeak.isEnabled = false
        if (mode == MODE_OVERLAY) {
            ovEngine.clearChips()
            lineLayer.resetPeek()
            lineLayer.invalidate()
        }
    }

    // ==================== 截图授权（按需）====================

    private fun requestCapture() {
        // v1.15.22：不再因为"引擎读不了图"就拦下来。
        // 读不了图时会在下面自动改走「本机 OCR + 文本翻译」——
        // 配免密钥的必应网页端就是一条完全免费的图片翻译链路。
        tvStatus.text = getString(R.string.image_translate_t07)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("startActivityForResult 简单直接，图片翻译无需 Result API 的额外复杂度")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            tvStatus.text = getString(R.string.image_translate_t04)
            return
        }
        tvStatus.text = getString(R.string.image_translate_t06)
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
                tvStatus.text = getString(R.string.image_translate_t03)
                return@launch
            }
            showBitmap(bmp)
            tvStatus.text = getString(R.string.image_translate_t02)
            if (mode == MODE_OVERLAY) autoTranslateOverlay()
        }
    }

    // ==================== 原位覆盖模式（v1.25.0）====================

    /**
     * 把当前图缩到 [LineOverlayEngine.MAX_OCR_SIDE] 以内，整屏 OCR，逐行翻译贴回原位。
     *
     * **先缩再冻**：相册原图常有 4000px 级，OCR 不需要那么大；缩完的这张同时充当
     * "显示图"与"OCR 输入"，识别框与贴片因此天然同坐标系，不需要再换算一次。
     */
    private fun autoTranslateOverlay() {
        val src = screenshot ?: return
        val frame = ovEngine.downscale(src, LineOverlayEngine.MAX_OCR_SIDE)
        // 缩过的这张不回收 src —— src 还在 ImageView 上显示着（截图/相册原图），
        // 回收它会让画面变黑。帧图与显示图分开持有是这里的代价，换来的是
        // 二者坐标系一致（贴片不会错位）。

        ovEngine.setFrame(frame)
        lineLayer.resetPeek()
        lineLayer.invalidate()
        progress.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.image_translate_t16)

        lifecycleScope.launch {
            val scanned = withContext(Dispatchers.Default) { OcrEngine.recognize(frame) }
            ovEngine.setLines(scanned)
            progress.visibility = View.GONE
            lineLayer.invalidate()

            if (scanned.isEmpty()) {
                tvStatus.text = getString(R.string.image_translate_t17)
                return@launch
            }
            tvStatus.text = getString(R.string.image_translate_t26, scanned.size)
            ovEngine.translateLines(scanned.indices.toList(), single = false, scope = lifecycleScope)
        }
    }

    /** 「📄 全文 / 翻译整张图」按钮：两种模式下含义不同 */
    private fun onFullClick() {
        if (mode == MODE_OVERLAY) translateWhole() else screenshot?.let { translateBitmap(it, "整图") }
    }

    /**
     * 原位覆盖模式下的「📄 全文」：把 OCR 出的整屏原文一次性发给引擎。
     * 逐行贴合是"位置对得上"，整段翻译是"上下文连贯"，两者各有所长，都留着。
     */
    private fun translateWhole() {
        val text = ovEngine.sourceText
        if (text.isBlank()) {
            // 还没识别（例如用户没选图 / 识别失败）→ 提示而不是静默失败
            screenshot?.let { translateBitmap(it, "整图") } ?: run {
                toast(getString(R.string.image_translate_t27))
            }
            return
        }
        if (translating) {
            toast(getString(R.string.image_translate_t08))
            return
        }
        translating = true
        progress.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.image_translate_t28)
        resultBox.visibility = View.VISIBLE
        tvSource.visibility = View.GONE
        tvResult.text = ""

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                TranslatorFactory.current().translate(text, App.prefs.targetLang, App.prefs.sourceLang)
            }
            translating = false
            progress.visibility = View.GONE
            result.fold(
                onSuccess = { out ->
                    lastTranslated = out
                    tvResult.text = out
                    btnSpeak.isEnabled = true
                    tvStatus.text = getString(R.string.image_translate_t29)
                    runCatching {
                        HistoryStore.add(
                            source = text,
                            translated = out,
                            mode = "🖼 图片(全文)",
                            targetLang = App.prefs.targetLang,
                            engine = App.prefs.engine
                        )
                    }
                    if (App.prefs.ttsAutoSpeak && out.isNotBlank()) {
                        Speaker.speakContent(this@ImageTranslateActivity, text, out)
                    }
                },
                onFailure = { e ->
                    tvResult.text = ""
                    tvStatus.text = "翻译失败：${e.message}"
                }
            )
        }
    }

    /** 原位覆盖模式下的写历史：逐行译文按阅读顺序拼回一条，避免一张图刷出几十条 */
    private fun recordHistory(okCount: Int) {
        if (okCount <= 0) return
        val src = ovEngine.lines.indices.filter { ovEngine.translations.containsKey(it) }
            .joinToString("\n") { ovEngine.lines[it].text }
        val dst = ovEngine.lines.indices.filter { ovEngine.translations.containsKey(it) }
            .joinToString("\n") { ovEngine.translations[it] ?: "" }
        if (dst.isBlank()) return
        runCatching {
            HistoryStore.add(
                source = src,
                translated = dst,
                mode = "🖼 图片(贴原文)",
                targetLang = App.prefs.targetLang,
                engine = App.prefs.engine
            )
        }
        if (App.prefs.ttsAutoSpeak) {
            Speaker.speakContent(this@ImageTranslateActivity, src, dst)
        }
    }

    // ==================== 框选模式 ====================

    private fun onRegionSelected(rectInBitmap: Rect) {
        val shot = screenshot ?: return
        if (rectInBitmap.width() < 8 || rectInBitmap.height() < 8) {
            toast(getString(R.string.image_translate_t11))
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
            toast(getString(R.string.image_translate_t10))
            return
        }
        translateBitmap(cropped, "框选区域")
    }

    /** 当前引擎每秒可接受的一次图片翻译请求是否进行中 */
    private var translating = false

    private fun translateBitmap(bmp: Bitmap, label: String) {
        if (translating) {
            toast(getString(R.string.image_translate_t08))
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
                tvStatus.text = getString(R.string.image_translate_t01)
                return@launch
            }

            val translator = TranslatorFactory.current()
            val engine = TranslationEngine.fromKey(App.prefs.engine)
            val result = if (engine.visionCapable) {
                translator.translateImage(bytes, "image/jpeg", App.prefs.targetLang, null, App.prefs.sourceLang)
            } else {
                // 引擎只能翻文字 → 在本机把图上的日文认出来，再走文本翻译。
                // 识别不花钱、不联网、图片不出设备；只有认出的文字发给引擎。
                tvStatus.text = getString(R.string.image_translate_t05)
                val lines = runCatching {
                    withContext(Dispatchers.IO) { OcrEngine.recognizeJapanese(bmp) }
                }.getOrElse { emptyList() }
                val src = OcrEngine.toPlainText(lines)
                if (src.isBlank()) {
                    Result.failure(RuntimeException("本机没认出文字（换个引擎，或框得更准些）"))
                } else {
                    tvStatus.text = "识别到 ${lines.size} 行，正在翻译…"
                    translator.translate(src, App.prefs.targetLang, App.prefs.sourceLang)
                }
            }

            translating = false
            progress.visibility = View.GONE
            result.fold(
                onSuccess = { out ->
                    lastTranslated = out
                    resultBox.visibility = View.VISIBLE
                    tvSource.visibility = View.VISIBLE
                    tvSource.text = "（$label，已识别并翻译）"
                    tvResult.text = out
                    btnSpeak.isEnabled = true
                    tvStatus.text = getString(R.string.image_translate_t09)
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
        ovEngine.setFrame(null)
        screenshot?.recycle()
        screenshot = null
        super.onDestroy()
    }

    // ==================== 手势层 ====================

    /**
     * 图片上的手势层。
     *
     * ## 框选模式
     * 拖动画矩形，松手回调 → 只翻框内。
     *
     * ## 原位覆盖模式
     * 轻点 → 翻那一行；按住 → 藏贴片看原文（沿用 [PeekLineLayer] 的手感，
     * 与拍照翻译一致 —— 同一个手势在两个页面里不该有不同行为）。
     *
     * 两种模式的坐标换算都必须在**同一个几何**下做：
     * [displayRect] 用的是 CENTER_CROP（与 [LineOverlayEngine.frameTransform]
     * 完全同一套 `max` 缩放 + 居中裁切），所以画出来的框、贴片、点选判定三者对齐。
     */
    private inner class LineLayer(ctx: Context) : PeekLineLayer(ctx) {

        private var bmpW = 0
        private var bmpH = 0
        private var startX = 0f
        private var startY = 0f
        private var curX = 0f
        private var curY = 0f
        private var dragging = false

        /** 是否允许"轻点翻一行"（原位覆盖模式开，框选模式关） */
        var onTapEnabled = true

        private val paint = android.graphics.Paint().apply {
            color = themeColor(com.google.android.material.R.attr.colorPrimary)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val fill = android.graphics.Paint().apply {
            color = 0x334CAF50
            style = android.graphics.Paint.Style.FILL
        }

        /** 位图在视图中的实际显示矩形（CENTER_CROP：铺满 + 居中裁切，与引擎同一套规则） */
        private fun displayRect(): Rect {
            val vw = width.toFloat()
            val vh = height.toFloat()
            val bw = bmpW
            val bh = bmpH
            if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) {
                return Rect(0, 0, width, height)
            }
            val scale = maxOf(vw / bw, vh / bh)
            val dw = bw * scale
            val dh = bh * scale
            val left = (vw - dw) / 2f
            val top = (vh - dh) / 2f
            return Rect(left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt())
        }

        /** 视图坐标 → 位图坐标（框选模式用；原位覆盖模式的点选走引擎的 pickLineAt） */
        private fun toBitmap(x: Float, y: Float): Pair<Int, Int> {
            val d = displayRect()
            if (d.width() <= 0 || d.height() <= 0 || bmpW <= 0) return 0 to 0
            val bx = ((x - d.left) / d.width() * bmpW).toInt().coerceIn(0, bmpW)
            val by = ((y - d.top) / d.height() * bmpH).toInt().coerceIn(0, bmpH)
            return bx to by
        }

        // ---- PeekLineLayer 契约 ----

        /** 有没有"原文"可看 —— 只在本页处于原位覆盖模式且有帧图时才成立 */
        override fun hasFrame(): Boolean =
            mode == MODE_OVERLAY && ovEngine.frame != null

        override fun onPeekStart() = ovEngine.setPeek(true)

        override fun onPeekEnd() = ovEngine.setPeek(false)

        override fun onTap(x: Float, y: Float) {
            if (mode != MODE_OVERLAY || !onTapEnabled) return
            val hit = ovEngine.pickLineAt(x, y)
            if (hit == null) {
                toast(getString(R.string.image_translate_t30))
                return
            }
            ovEngine.translations[hit]?.let { done ->
                toast("这一行已是：$done")
                return
            }
            ovEngine.translateLines(listOf(hit), single = true, scope = lifecycleScope)
        }

        // ---- 框选实现 ----

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val overlayMode = mode == MODE_OVERLAY
            // 原位覆盖模式：把触摸整个交给基类（轻点翻行 + 按住看原文）
            if (overlayMode) return super.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y
                    curX = startX; curY = startY
                    dragging = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    dragging = true
                    curX = event.x; curY = event.y
                    invalidate()
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
                    dragging = false
                    invalidate()
                }
            }
            return true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val b = screenshot
            if (b != null) {
                bmpW = b.width
                bmpH = b.height
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val b = screenshot ?: return
            bmpW = b.width
            bmpH = b.height

            if (mode == MODE_OVERLAY) {
                // 未译行的细框 + 正在翻译的高亮框（与拍照翻译同一套绘制）
                ovEngine.drawPendingLines(canvas, ovEngine.peekActive)
                return
            }
            if (!dragging) return
            val l = minOf(startX, curX)
            val t = minOf(startY, curY)
            val r = maxOf(startX, curX)
            val btm = maxOf(startY, curY)
            canvas.drawRect(l, t, r, btm, fill)
            canvas.drawRect(l, t, r, btm, paint)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "ScreenTranslator"
        private const val REQ_PROJECTION = 1001

        /** 原位覆盖：译文盖在原文上（默认） */
        const val MODE_OVERLAY = "overlay"

        /** 框选：拖框 → 只翻框内 → 结果在下方文本框（v1.8.0 原始交互） */
        const val MODE_REGION = "region"

        /**
         * 相册图片解码后的长边上限（像素）。
         *
         * v1.25.0：相册里的原图动辄 4000×3000 以上（一张 1200 万像素的
         * ARGB_8888 位图约 48MB），直接解码既慢又极易 OOM。
         *
         * 取 1600 是因为翻译只需要"文字可辨认"：识别引擎对超过这个尺寸的
         * 图片不会再提升准确率，而内存占用能降一个数量级（1600 长边约 4~8MB）。
         * 同时 1600 也远高于屏幕截图的典型宽度（本机 1256），
         * 不会出现"相册图比截图糊"的反差。
         */
        private const val MAX_PICK_SIDE = 1600

        /**
         * 按需降采样解码一个相册 Uri（v1.25.0）。
         *
         * 标准两遍解码：
         *   1. 先只读尺寸（`inJustDecodeBounds = true`），不分配像素内存；
         *   2. 按目标长边算 [BitmapFactory.Options.inSampleSize]（只能是 2 的幂），
         *      再真正解码。
         *
         * 任何一步失败都返回 null，由调用方提示用户 —— 相册 Uri 可能指向
         * 已删除/无权限的文件，这里不抛异常，避免把整个页面带崩。
         */
        private fun decodeSampled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxSide ||
                    bounds.outHeight / (sample * 2) >= maxSide
                ) {
                    sample *= 2
                }

                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                ctx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }
        }.getOrNull()
    }

    /** 从当前主题解析 M3 语义色（这样代码构建的界面也能跟随明暗主题） */
    private fun themeColor(attrRes: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attrRes, tv, true)
        return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId) else tv.data
    }
}
