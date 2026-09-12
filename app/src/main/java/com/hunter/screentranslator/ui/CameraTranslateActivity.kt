package com.hunter.screentranslator.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.util.HistoryStore
import com.hunter.screentranslator.util.OcrEngine
import com.hunter.screentranslator.util.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * 拍照翻译。v1.11.0 引入，**v1.14.0 改为「拍哪译哪 · 译文贴原文」**。
 *
 * 流程：CameraX 取景 → 按快门（或**直接点屏幕上某处文字**）→ 端侧 OCR（ML Kit，不联网）
 * → 逐行翻译 → **把每一行的译文贴在它原来所在的位置上**。
 *
 * v1.13.0 及以前的问题：整屏 OCR 出来的文字被拼成一整段发给引擎，译文只出现在底部卡片里，
 * 与原文位置完全脱钩 —— 菜单、路牌、表单这类多行内容里，用户根本对不上"哪句译文对应哪行原文"。
 * 本版把"位置"这一维信息从 OCR 结果里保留下来并一路用到渲染（[OcrEngine.Line.box]）：
 *
 * 1. **点哪译哪**：取景时点屏幕上的一行字 → 立刻拍下并只翻那一行，译文就地出现；
 *    冻结帧上再点其他行，继续就地翻。这是"拍哪里就出现哪里的翻译"的直接实现。
 * 2. **译文盖住原文**：贴片是**不透明**的，宽高都不小于原文框，直接把原文盖掉 ——
 *    和谷歌拍照翻译一样，"看到的就是译文"。背景色按原文框周边亮度取样自适应
 *    （浅底走浅色块 + 深色字，深底反过来），避免白底菜单上糊一块黑。
 *    想核对原文：**按住屏幕**，所有贴片隐藏、露出原文，松手恢复。
 * 3. **快门 = 整屏逐行贴合**：所有识别到的行各自成一次翻译，译文各自盖回原位。
 *    逐行而不是整段，是为了保证**位置对齐不发生错位** —— 整段译文只有一坨文字，
 *    无法可靠地拆回各行（引擎可能合并/重排/改写行结构，拆错就等于把 A 的译文贴到 B 上）。
 *    代价是行数多时请求数多，所以：同一行文字只发一次请求（去重）、并发上限 [MAX_PARALLEL]、
 *    并且复用 v1.10.0 的翻译缓存（[com.hunter.screentranslator.api.CachingTranslator]
 *    按「引擎+端点+模型+目标语言+原文」命中，重复内容零请求零费用）。
 * 3. **需要上下文时**：底部「📄 全文」走一次整段翻译（一次请求、带上下文、译文更连贯），
 *    结果放在原来的底部卡片里 —— 逐行贴合与整段翻译各有所长，都保留，由用户选。
 *
 * 关键实现细节（已核对 CameraX 1.3.4 源码）：
 * - `ImageProxy.toBitmap()` **不会**应用 `rotationDegrees`
 *   （`ImageUtil.createBitmapFromImageProxy` 只做 YUV/JPEG/RGBA 格式转换）。
 *   不手动旋转的话，竖屏拍出来是横的，OCR 会整片失效——见 [bitmapFrom]。
 * - **拍照后立刻冻结画面**（[freezeFrame] 把相机帧画到 [ivFrame]）：预览是活的，
 *   若继续显示实时取景，贴在原位的译文会随着手的抖动一直跑，根本没法读。
 * - 坐标换算：[frameTransform] 用的是与 `PreviewView` 相同的 `FILL_CENTER` 规则
 *   （取 `max` 缩放 + 居中裁切），这样"位图坐标 ↔ 屏幕坐标"与用户在取景框里看到的一致。
 * - **先缩再冻**（[processCapture]）：冻结帧要常驻到用户重拍，所以只保留最长边
 *   ≤ [MAX_OCR_SIDE] 的那一张，相机原图立刻 `recycle()`（12MP 原图 ≈ 48MB）。
 *   冻结帧同时充当 OCR 输入，识别框与贴片因此天然同坐标系，不需要再换算一次。
 * - 位图直接来自相机，与 MediaProjection 无关，因此没有"每次会话都要重新授权"
 *   的限制，也不需要无障碍截图能力。
 */
class CameraTranslateActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "ScreenTranslator"
        const val REQ_CAMERA = 1001

        /** 冻结帧（= 显示图 = 送 OCR 的图）最长边上限：相机出图常有 4000px 级，先缩能省内存 */
        const val MAX_OCR_SIDE = 2000

        /** 逐行翻译的并发上限：太高会被引擎限流（429），太低整屏要等很久 */
        const val MAX_PARALLEL = 4

        /** 点选容差（dp）：没点进文字框时，取这个范围内最近的一行 */
        const val TAP_TOLERANCE_DP = 40

        /**
         * 按下多久算「按住看原文」（ms）。
         * 比 [android.view.ViewConfiguration.getLongPressTimeout]（500ms）短一些：
         * 这是"偷看一眼原文"，手感要跟得上手指，不能等半秒。
         */
        const val PEEK_DELAY_MS = 220L

        /** 取景态提示：三个手势一次说清 */
        const val HINT_LIVE = "点一行字就地翻译 · 按快门译整屏 · 按住屏幕看原文"
    }

    private lateinit var root: FrameLayout

    private lateinit var previewView: PreviewView

    /** 冻结帧：拍照后盖住预览，译文贴片都挂在它上面 */
    private lateinit var ivFrame: ImageView

    /** 全屏手势层：画未译行的细框 + 接收点选（v1.14.0 的「点哪译哪」入口） */
    private lateinit var lineLayer: LineLayer

    /** 译文贴片容器（与冻结帧同坐标系） */
    private lateinit var overlayHost: FrameLayout

    /** 常驻提示/状态（在结果卡片之外，任何时刻都看得见） */
    private lateinit var tvHint: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvResult: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnShutter: MaterialButton
    private lateinit var btnWhole: MaterialButton
    private lateinit var btnRetake: MaterialButton
    private lateinit var resultCard: View

    private var imageCapture: ImageCapture? = null
    private var busy = false
    private var lastTranslated = ""

    /** 冻结帧（已按 rotationDegrees 摆正、未缩放的原图） */
    private var frozen: Bitmap? = null

    /** 识别到的行；`box` 已换算回 [frozen] 的像素坐标系 */
    private var lines: List<OcrEngine.Line> = emptyList()

    /** 行索引 → 译文 */
    private val translations = HashMap<Int, String>()

    /** 行索引 → 贴片 View（重拍/再次翻译时替换或移除） */
    private val chips = HashMap<Int, View>()

    /** 正在翻译的行索引，用于高亮"哪几行在等" */
    private val inFlight = HashSet<Int>()

    /** OCR 拼出的整屏原文，供「📄 全文」与历史记录使用 */
    private var sourceText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            tvHint.text = "需要相机权限才能拍照翻译"
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                tvHint.text = "未授予相机权限。可到 系统设置 → 应用 → 屏幕翻译 → 权限 里开启"
                toast("没有相机权限，无法拍照")
            }
        }
    }

    // ==================== 相机 ====================

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrElse { e ->
                Log.e(TAG, "相机启动失败: $e")
                tvHint.text = "相机启动失败：${e.message}"
                return@addListener
            }
            // v1.14.0：预览与拍照**锁定同一宽高比**。两者宽高比不同时，取景框裁切到的
            // 范围和照片裁切到的范围不一致，"点哪译哪"就会点到这里、翻到旁边那一行。
            // 4:3 与多数传感器的最大画幅一致，裁切最少；个别设备组合不支持时退回默认。
            if (!bindCamera(provider, pinAspectRatio = true)) {
                Log.w(TAG, "4:3 绑定失败，退回默认宽高比")
                bindCamera(provider, pinAspectRatio = false)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 绑定预览 + 拍照。返回是否成功（失败由调用方决定要不要退回默认宽高比） */
    private fun bindCamera(provider: ProcessCameraProvider, pinAspectRatio: Boolean): Boolean =
        runCatching {
            val previewBuilder = Preview.Builder()
            val captureBuilder = ImageCapture.Builder()
                // 低延迟优先：拍照翻译是"对准就拍"，不需要最高画质
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            if (pinAspectRatio) {
                previewBuilder.setTargetAspectRatio(AspectRatio.RATIO_4_3)
                captureBuilder.setTargetAspectRatio(AspectRatio.RATIO_4_3)
            }
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = captureBuilder.build()
            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
            )
            setStatus(HINT_LIVE)
        }.onFailure { e ->
            Log.e(TAG, "相机启动失败: $e")
            tvHint.text = "相机启动失败：${e.message}"
        }.isSuccess

    /**
     * 拍照。[tapX]/[tapY] 不为空表示"用户点着屏幕上的某处拍的"——
     * 出图后只翻译那一点所在的文字（[processCapture]）。
     */
    private fun takePhoto(tapX: Float? = null, tapY: Float? = null) {
        val capture = imageCapture ?: run {
            toast("相机还没就绪")
            return
        }
        if (busy) {
            toast("正在处理上一张，请稍候")
            return
        }
        busy = true
        btnShutter.isEnabled = false
        if (tapX == null) setStatus("拍摄中…")

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = bitmapFrom(image)
                    if (bmp == null) {
                        busy = false
                        btnShutter.isEnabled = true
                        setStatus("照片转换失败，请重试")
                        return
                    }
                    processCapture(bmp, tapX, tapY)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败: ${exception.message}", exception)
                    busy = false
                    btnShutter.isEnabled = true
                    setStatus("拍照失败：${exception.message}")
                }
            }
        )
    }

    /**
     * ImageProxy → 已摆正的 Bitmap。
     *
     * [ImageProxy.toBitmap] 只做格式转换、**不应用旋转**，所以这里必须自己按
     * `imageInfo.rotationDegrees` 转正，否则竖屏拍摄得到的是一张横图。
     * 无论成功失败都要 close()，否则 ImageReader 的缓冲会被耗尽（后续拍照卡死）。
     */
    private fun bitmapFrom(image: ImageProxy): Bitmap? = try {
        val raw = image.toBitmap()
        val deg = image.imageInfo.rotationDegrees
        if (deg == 0) raw else rotate(raw, deg)
    } catch (e: Exception) {
        Log.e(TAG, "ImageProxy → Bitmap 失败: $e")
        null
    } finally {
        runCatching { image.close() }
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out != src) src.recycle()
        return out
    }

    // ==================== 冻结帧 ====================

    /**
     * 把拍到的画面固定到屏幕上（预览继续在底下跑，但被完全盖住）。
     *
     * 为什么不继续看实时预览：译文贴在原文位置上，实时画面一动贴片就跟原文错位，
     * 用户会以为"又贴歪了"。冻结后位置是静止的，可以慢慢读、可以继续点其他行。
     */
    private fun freezeFrame(bmp: Bitmap) {
        val old = frozen
        frozen = bmp
        ivFrame.setImageBitmap(bmp)
        ivFrame.visibility = View.VISIBLE
        if (old != null && old !== bmp) runCatching { old.recycle() }

        clearChips()
        lines = emptyList()
        sourceText = ""
        inFlight.clear()
        lineLayer.resetPeek()
        lineLayer.invalidate()
        resultCard.visibility = View.GONE
        btnWhole.isEnabled = false
    }

    /** 回到取景状态（重新拍/换一处翻译） */
    private fun retake() {
        ivFrame.setImageBitmap(null)
        ivFrame.visibility = View.GONE
        frozen?.let { runCatching { it.recycle() } }
        frozen = null
        clearChips()
        lines = emptyList()
        sourceText = ""
        inFlight.clear()
        lineLayer.resetPeek()
        lineLayer.invalidate()
        resultCard.visibility = View.GONE
        btnWhole.isEnabled = false
        progress.visibility = View.GONE
        setStatus(HINT_LIVE)
    }

    private fun clearChips() {
        overlayHost.removeAllViews()
        overlayHost.visibility = View.VISIBLE
        chips.clear()
        translations.clear()
    }

    // ==================== OCR + 逐行翻译 ====================

    private fun processCapture(bmp: Bitmap, tapX: Float?, tapY: Float?) {
        // **先缩再冻**：相机原图常有 4000px 级（12MP ≈ 48MB ARGB_8888），
        // 而冻结帧要常驻到用户重拍为止。这里只保留"显示 + OCR 共用"的那一张
        // （≤ [MAX_OCR_SIDE]），原图立刻回收 —— 既省内存，又让识别框与贴片天然同坐标系。
        val frame = downscale(bmp, MAX_OCR_SIDE)
        if (frame !== bmp) runCatching { bmp.recycle() }
        freezeFrame(frame)
        progress.visibility = View.VISIBLE
        setStatus(if (tapX == null) "识别文字中（本机完成，不联网）…" else "已对准你点的位置，识别中…")

        lifecycleScope.launch {
            // ML Kit 的 process() 是异步回调式的，包成挂起函数；识别放后台，避免主线程卡顿。
            // 返回值里的 boundingBox 是**输入图坐标系**的（ML Kit 内部缩放会自己换算回来），
            // 输入的就是冻结帧本身，所以框可以直接当屏幕贴片的位置用。
            val scanned = withContext(Dispatchers.Default) {
                OcrEngine.recognize(frame)
            }
            lines = scanned
            sourceText = OcrEngine.toPlainText(scanned)
            progress.visibility = View.GONE
            busy = false
            btnShutter.isEnabled = true
            btnWhole.isEnabled = scanned.isNotEmpty()
            lineLayer.invalidate()

            if (scanned.isEmpty()) {
                setStatus("没识别到文字。试试：靠近一点、避开反光、让文字占满取景框")
                return@launch
            }

            if (tapX != null && tapY != null) {
                // 「点哪译哪」：只翻用户点到的那一行，其余行只画细框、保持干净
                val hit = pickLineAt(tapX, tapY)
                if (hit == null) {
                    setStatus("识别到 ${scanned.size} 行，但你点的位置没文字 —— 点在字上即可就地翻译")
                } else {
                    setStatus("已锁定这一行，翻译中…")
                    startTranslate(listOf(hit), single = true)
                }
            } else {
                setStatus("识别到 ${scanned.size} 行 · 整屏逐行贴合中…")
                startTranslate(scanned.indices.toList(), single = false)
            }
        }
    }

    /**
     * 逐行翻译 [indices] 并把译文贴回各自原位。
     *
     * 三点控制住"行数多 = 请求多"的成本：
     * 1. **同样的文字只发一次请求**（菜单里"￥38"这种重复行很常见），结果分发给所有同文行；
     * 2. 并发上限 [MAX_PARALLEL]，避免被引擎限流；
     * 3. 引擎外面的 [com.hunter.screentranslator.api.CachingTranslator] 按原文命中缓存 ——
     *    重复翻译同一张图/同一句，零请求零费用。
     */
    private fun startTranslate(indices: List<Int>, single: Boolean) {
        val targets = indices.filter { it in lines.indices }
        if (targets.isEmpty()) return
        busy = true
        btnShutter.isEnabled = false
        progress.visibility = View.VISIBLE

        val total = targets.size
        var finished = 0
        var failed = 0
        val progressText: (Int) -> String = {
            if (single) "翻译中…" else "翻译中 $it/$total 行…"
        }
        setStatus(progressText(0))

        lifecycleScope.launch {
            val translator = TranslatorFactory.current()
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
                            lineLayer.invalidate()
                        }
                        val text = lines[group.first()].text.trim()
                        val result = runCatching { translator.translate(text, target) }
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
                            if (!single) setStatus(progressText(finished))
                            lineLayer.invalidate()
                        }
                    }
                }
            }.awaitAll()

            busy = false
            btnShutter.isEnabled = true
            progress.visibility = View.GONE

            if (single) {
                val i = targets.first()
                val out = translations[i]
                setStatus(
                    if (out == null) "这一行翻译失败，可点「📄 全文」整段再试"
                    else "已就地翻译 · 点其他行可继续 · 按住屏幕看原文"
                )
                if (out != null) {
                    addHistory(lines[i].text, out, "📷 拍照翻译（点选）")
                    if (App.prefs.ttsAutoSpeak) {
                        Speaker.speakContent(this@CameraTranslateActivity, lines[i].text, out)
                    }
                }
            } else {
                val ok = targets.count { translations.containsKey(it) }
                setStatus(
                    "已就地翻译 $ok/$total 行" +
                        (if (failed > 0) "（$failed 行失败）" else "") +
                        " · 点某一行可单独重试，按住屏幕看原文"
                )
                // 历史只记一条：逐行译文按阅读顺序拼回去，避免一张图刷出几十条记录
                val src = targets.filter { translations.containsKey(it) }
                    .joinToString("\n") { lines[it].text }
                val dst = targets.filter { translations.containsKey(it) }
                    .joinToString("\n") { translations[it] ?: "" }
                if (dst.isNotBlank()) {
                    addHistory(src, dst, "📷 拍照翻译（贴原文）")
                    if (App.prefs.ttsAutoSpeak) {
                        Speaker.speakContent(this@CameraTranslateActivity, src, dst)
                    }
                }
            }
        }
    }

    /** 整段翻译：一次请求，带上下文。结果放底部卡片（与原 v1.11.0 行为一致） */
    private fun translateWhole() {
        val text = sourceText
        if (text.isBlank()) {
            toast("还没识别到文字")
            return
        }
        if (busy) {
            toast("正在处理，请稍候")
            return
        }
        busy = true
        btnShutter.isEnabled = false
        resultCard.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        tvSource.visibility = View.VISIBLE
        tvSource.text = text
        tvResult.text = ""
        tvStatus.text = "整段翻译中（一次请求，带上下文）…"

        lifecycleScope.launch {
            val engineKey = App.prefs.engine
            val result = withContext(Dispatchers.IO) {
                TranslatorFactory.current().translate(text, App.prefs.targetLang)
            }
            progress.visibility = View.GONE
            busy = false
            btnShutter.isEnabled = true

            result.fold(
                onSuccess = { out ->
                    lastTranslated = out
                    tvResult.text = out
                    tvStatus.text = "整段翻译完成（端侧识别 + $engineKey）"
                    addHistory(text, out, "📷 拍照翻译")
                    if (App.prefs.ttsAutoSpeak && out.isNotBlank()) {
                        Speaker.speakContent(this@CameraTranslateActivity, text, out)
                    }
                },
                onFailure = { e ->
                    tvResult.text = ""
                    tvStatus.text = "翻译失败：${e.message}"
                }
            )
        }
    }

    /**
     * 缩到最长边不超过 [MAX_OCR_SIDE] 再做 OCR。
     * ML Kit 对超大图会自己缩，但先缩能省内存——相机出图常有 4000px 级别。
     */
    private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
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

    // ==================== 位置换算（位图坐标 ↔ 屏幕坐标）====================

    /**
     * 冻结帧在屏幕上的实际显示矩形。
     *
     * 规则与 `PreviewView` 的 `FILL_CENTER` 一致：`scale = max(vw/bw, vh/bh)`，
     * 然后居中（超出的部分被裁掉）。**必须按 max 而不是 min**——用 min（FIT_CENTER）
     * 算出来的贴片位置会整体偏移一圈留白，正是 v1.14.0 要消灭的那种错位。
     */
    private fun frameTransform(): RectF? {
        val bmp = frozen ?: return null
        val vw = root.width.toFloat()
        val vh = root.height.toFloat()
        if (vw <= 0f || vh <= 0f || bmp.width <= 0 || bmp.height <= 0) return null
        val scale = maxOf(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }

    /** 位图坐标 → 屏幕坐标（贴片定位用） */
    private fun bitmapRectToView(r: Rect): RectF? {
        val bmp = frozen ?: return null
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
    private fun viewPointToBitmap(x: Float, y: Float): FloatArray? {
        val bmp = frozen ?: return null
        val f = frameTransform() ?: return null
        val k = f.width() / bmp.width
        if (k <= 0f) return null
        return floatArrayOf((x - f.left) / k, (y - f.top) / k)
    }

    /**
     * 点选判定：优先"包含该点的最小文字框"（点在字上），
     * 否则退化为容差 [TAP_TOLERANCE_DP] 内最近的一行（点偏一点点也能用）。
     */
    private fun pickLineAt(viewX: Float, viewY: Float): Int? {
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
        val bmp = frozen ?: return null
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
     * 把译文**盖在原文上**（与谷歌拍照翻译一致：看到的就是译文，不是"旁边多了个气泡"）：
     * 不透明背景 + 宽高都不小于原文框，背景明暗按原文框周边取样自适应。
     * 译文更长时向右下自然生长（上限是屏幕内边距）。
     */
    private fun showChip(index: Int, translated: String) {
        val box = lines.getOrNull(index)?.box ?: return
        val v = bitmapRectToView(box) ?: return

        chips.remove(index)?.let { overlayHost.removeView(it) }

        val density = resources.displayMetrics.density
        // 原文字高一行的框高 ≈ 字号 × 1.4，反推字号，让译文尽量占满原文框
        val sizeSp = (v.height() / density * 0.72f).coerceIn(9f, 18f)
        val lightBackground = isLightAround(box)
        val bg = if (lightBackground) Color.rgb(250, 250, 250) else Color.rgb(16, 16, 16)
        val fg = if (lightBackground) Color.rgb(16, 16, 16) else Color.rgb(248, 248, 248)

        val chip = TextView(this).apply {
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
            maxWidth = (root.width - v.left - dp(8)).toInt().coerceAtLeast(dp(72))
            isClickable = false
            isFocusable = false
        }

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = v.left.toInt().coerceIn(0, maxOf(0, root.width - dp(72)))
            topMargin = v.top.toInt().coerceIn(0, maxOf(0, root.height - dp(24)))
        }
        overlayHost.addView(chip, lp)
        chips[index] = chip
    }

    /**
     * 原文框周边是浅底还是深底（决定贴片用浅色块还是深色块）。
     *
     * 在框内取 6×3 个点算平均亮度即可：块要盖住这一行，只要跟这行的底色接近就不会突兀。
     * 采样点少是有意的 —— 每多一个点就多一次 `getPixel`，而这是主线程上的渲染路径。
     */
    private fun isLightAround(box: Rect): Boolean {
        val bmp = frozen ?: return false
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

    /** 未译行的细框 + 正在翻译的高亮框（点选入口的可视提示） */
    private inner class LineLayer(ctx: Context) : View(ctx) {

        private val pending = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            color = Color.argb(110, 255, 255, 255)
            isAntiAlias = true
        }
        private val hotStroke = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = Color.argb(235, 120, 200, 255)
            isAntiAlias = true
        }
        private val hotFill = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(60, 120, 200, 255)
        }

        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L

        /** 「按住看原文」是否已经触发（贴片此刻是隐藏的） */
        private var peekActive = false

        /** 长按计时器是否已挂上（避免 DOWN 时重复 post） */
        private var peekScheduled = false

        private val peekRunnable = Runnable { startPeek() }

        init {
            isClickable = true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = SystemClock.uptimeMillis()
                    // 冻结帧上：按住一段时间 = 看原文（松手恢复）。取景时没有译文可藏，不挂计时器。
                    if (frozen != null) {
                        peekScheduled = true
                        postDelayed(peekRunnable, PEEK_DELAY_MS)
                    }
                    // 按下即给个即时反馈，否则"按住"要等 220ms 才看出反应
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 手指移动超过阈值 = 误触/拖动：立刻取消"按住看原文"，避免边滑边闪
                    if (hypot(event.x - downX, event.y - downY) > dp(12)) cancelPeek()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val wasPeeking = peekActive
                    cancelPeek()
                    val moved = hypot(event.x - downX, event.y - downY)
                    // 轻点才算点选；刚刚是在看原文（长按过）就不当作点选
                    if (!wasPeeking && moved < dp(12) && SystemClock.uptimeMillis() - downAt < 900) {
                        onFrameTap(downX, downY)
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
            if (frozen == null || peekActive) return
            peekActive = true
            overlayHost.visibility = View.INVISIBLE
            invalidate()
            setStatus("按住查看原文 · 松开显示译文")
        }

        /** 强制退出"看原文"态（重拍 / 重新拍摄前调用），不改状态文案 */
        fun resetPeek() {
            if (peekScheduled) {
                removeCallbacks(peekRunnable)
                peekScheduled = false
            }
            if (!peekActive) return
            peekActive = false
            overlayHost.visibility = View.VISIBLE
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
            overlayHost.visibility = View.VISIBLE
            invalidate()
            setStatus(
                when {
                    translations.isNotEmpty() -> "已就地翻译 ${translations.size} 行 · 点其他行可继续 · 按住可看原文"
                    lines.isNotEmpty() -> "识别到 ${lines.size} 行 · 点一行即可就地翻译 · 按住可看原文"
                    else -> "点屏幕上的一行字即可就地翻译"
                }
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 看原文时连提示框一起藏掉，画面回归"原图"，方便核对
            if (peekActive || lines.isEmpty()) return
            lines.forEachIndexed { i, line ->
                if (translations.containsKey(i)) return@forEachIndexed
                val v = bitmapRectToView(line.box) ?: return@forEachIndexed
                if (inFlight.contains(i)) {
                    canvas.drawRoundRect(v, dp(4).toFloat(), dp(4).toFloat(), hotFill)
                    canvas.drawRoundRect(v, dp(4).toFloat(), dp(4).toFloat(), hotStroke)
                } else {
                    canvas.drawRoundRect(v, dp(3).toFloat(), dp(3).toFloat(), pending)
                }
            }
        }
    }

    /** 点选：取景时点 = 拍下这一点并只翻那一处；冻结后再点 = 就近翻译那一行 */
    private fun onFrameTap(x: Float, y: Float) {
        if (busy) {
            toast("正在处理，请稍候")
            return
        }
        if (frozen == null) {
            setStatus("已对准你点的位置，拍摄中…")
            takePhoto(x, y)
            return
        }
        val hit = pickLineAt(x, y) ?: run {
            toast("这里没识别到文字，点在文字上试试")
            return
        }
        translations[hit]?.let { done ->
            toast("这一行已是：$done")
            return
        }
        startTranslate(listOf(hit), single = true)
    }

    // ==================== UI ====================

    private fun buildUi(): View {
        root = FrameLayout(this)

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)

        // 冻结帧：与 previewView 同一套填充规则（CENTER_CROP == PreviewView 的 FILL_CENTER：
        // 等比放大到铺满 + 居中裁切），位置才对得上
        ivFrame = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            isClickable = false
        }
        root.addView(ivFrame)

        lineLayer = LineLayer(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(lineLayer)

        overlayHost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = false
        }
        root.addView(overlayHost)

        // ---- 顶栏：返回 + 标题 ----
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }
        topBar.addView(MaterialButton(this).apply {
            text = "‹ 返回"
            textSize = 14f
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        })
        topBar.addView(TextView(this).apply {
            text = "📷 拍照翻译"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, 0, 0)
        })
        root.addView(topBar)

        // ---- 底部：提示 + 快门 + 全文 + 重拍 ----
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { bottomMargin = dp(12) }
        }

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        bottomBar.addView(progress)

        tvHint = TextView(this).apply {
            text = "正在启动相机…"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // 相机画面上明暗不定，加一层阴影保证可读
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(dp(6), 0, dp(6), dp(8))
        }
        bottomBar.addView(tvHint)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnShutter = MaterialButton(this).apply {
            text = "📸 拍摄并翻译整屏"
            textSize = 14f
            setOnClickListener { takePhoto() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        actions.addView(btnShutter)
        btnWhole = MaterialButton(this).apply {
            text = "📄 全文"
            textSize = 13f
            isEnabled = false
            setOnClickListener { translateWhole() }
        }
        actions.addView(btnWhole)
        btnRetake = MaterialButton(this).apply {
            text = "🔄 重拍"
            textSize = 13f
            setOnClickListener { retake() }
        }
        actions.addView(btnRetake)
        bottomBar.addView(actions)
        root.addView(bottomBar)

        // ---- 结果卡片（「📄 全文」整段翻译用；初始隐藏）----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.argb(235, 20, 20, 20))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { bottomMargin = dp(112) }
        }

        tvStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9E9E9E"))
        }
        card.addView(tvStatus)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        tvSource = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#BDBDBD"))
            visibility = View.GONE
        }
        texts.addView(tvSource)

        tvResult = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, 0)
        }
        texts.addView(tvResult)
        scroll.addView(texts)
        card.addView(scroll)

        val cardActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        cardActions.addView(MaterialButton(this).apply {
            text = "🔊 朗读"
            textSize = 13f
            setOnClickListener {
                if (tvSource.text.isNotBlank() || lastTranslated.isNotBlank()) {
                    Speaker.speakContent(
                        this@CameraTranslateActivity,
                        tvSource.text.toString(),
                        lastTranslated
                    )
                } else {
                    toast("还没有可朗读的内容")
                }
            }
        })
        cardActions.addView(MaterialButton(this).apply {
            text = "✕ 收起"
            textSize = 13f
            setOnClickListener { resultCard.visibility = View.GONE }
        })
        card.addView(cardActions)

        resultCard = card
        card.visibility = View.GONE
        root.addView(card)

        return root
    }

    private fun setStatus(msg: String) {
        tvHint.text = msg
    }

    private fun addHistory(source: String, translated: String, mode: String) {
        runCatching {
            HistoryStore.add(
                source = source,
                translated = translated,
                mode = mode,
                targetLang = App.prefs.targetLang,
                engine = App.prefs.engine
            )
        }
    }

    override fun onDestroy() {
        frozen?.let { runCatching { it.recycle() } }
        frozen = null
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
