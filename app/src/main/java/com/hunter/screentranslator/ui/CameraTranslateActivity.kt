package com.hunter.screentranslator.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.Gravity
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
import com.hunter.screentranslator.R
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.util.EdgeToEdge
import com.hunter.screentranslator.util.HistoryStore
import com.hunter.screentranslator.util.OcrEngine
import com.hunter.screentranslator.util.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *    代价是行数多时请求数多，所以：同一行文字只发一次请求（去重）、并发上限 [com.hunter.screentranslator.util.LineOverlayEngine.MAX_PARALLEL]、
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
 *   ≤ [com.hunter.screentranslator.util.LineOverlayEngine.MAX_OCR_SIDE] 的那一张，相机原图立刻 `recycle()`（12MP 原图 ≈ 48MB）。
 *   冻结帧同时充当 OCR 输入，识别框与贴片因此天然同坐标系，不需要再换算一次。
 * - 位图直接来自相机，与 MediaProjection 无关，因此没有"每次会话都要重新授权"
 *   的限制，也不需要无障碍截图能力。
 */
class CameraTranslateActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "ScreenTranslator"
        const val REQ_CAMERA = 1001

        // v1.25.0：MAX_OCR_SIDE / MAX_PARALLEL / TAP_TOLERANCE_DP / PEEK_DELAY_MS
        // 四个常量都随实现迁到了共享引擎
        // [com.hunter.screentranslator.util.LineOverlayEngine]（图片翻译也用同一份）。
        // 留在这里只会变成"改了引擎的参数但拍照翻译没跟上"的隐患。

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

    /**
     * v1.25.0：逐行 OCR → 译文贴回原位的那套逻辑抽成了共享引擎
     * （[com.hunter.screentranslator.util.LineOverlayEngine]），图片翻译也用同一份。
     * 这里保留原有的拍照/取景/点选拍照代码，只是把几何换算、贴片渲染、
     * 逐行翻译调度三块委托给引擎 —— 行为与 v1.24.0 完全一致。
     */
    private lateinit var ovEngine: com.hunter.screentranslator.util.LineOverlayEngine

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
    private var lastTranslated = ""

    /** 冻结帧（已按 rotationDegrees 摆正、未缩放的原图）—— 与引擎共用同一张 */
    private val frozen: Bitmap? get() = ovEngine.frame

    /** 识别到的行；`box` 已换算回 [frozen] 的像素坐标系 */
    private val lines: List<OcrEngine.Line> get() = ovEngine.lines

    /** 行索引 → 译文 */
    private val translations: HashMap<Int, String> get() = ovEngine.translations

    /** 正在翻译的行索引，用于高亮"哪几行在等" */
    private val inFlight: HashSet<Int> get() = ovEngine.inFlight

    /** OCR 拼出的整屏原文，供「📄 全文」与历史记录使用 */
    private val sourceText: String get() = ovEngine.sourceText

    /** 是否有请求在飞（引擎与按钮状态共用） */
    private val busy: Boolean get() = ovEngine.busy

    /**
     * 「📄 全文」整段翻译是否进行中。
     *
     * 与 [busy] 分开：整段翻译走的是独立的一条请求，不经过 [ovEngine]，
     * 但同样要禁用快门并显进度条。v1.25.0 之前这两件事共用一个 `busy` 字段，
     * 抽引擎后必须拆开，否则会退化成"整段翻译时引擎以为自己在忙"。
     */
    private var wholeBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        EdgeToEdge.install(this)

        ovEngine = com.hunter.screentranslator.util.LineOverlayEngine(object :
            com.hunter.screentranslator.util.LineOverlayEngine.Host {
            override val context: Context get() = this@CameraTranslateActivity
            override val overlayHost: FrameLayout get() = this@CameraTranslateActivity.overlayHost
            override val rootView: View get() = root
            override fun onSourceReady(text: String) { /* 拍照翻译的 sourceText 由 startTranslate 直接用 */ }
            override fun onStatus(text: String) = setStatus(text)
            override fun onProgress(show: Boolean) {
                progress.visibility = if (show) View.VISIBLE else View.GONE
            }
            override fun onBusy(busy: Boolean) {
                btnShutter.isEnabled = !busy
            }
            override fun onBatchDone(okCount: Int, total: Int, failed: Int) {
                setStatus(
                    "已就地翻译 $okCount/$total 行" +
                        (if (failed > 0) "（$failed 行失败）" else "") +
                        " · 点某一行可单独重试，按住屏幕看原文"
                )
                val src = lines.indices.filter { translations.containsKey(it) }
                    .joinToString("\n") { lines[it].text }
                val dst = lines.indices.filter { translations.containsKey(it) }
                    .joinToString("\n") { translations[it] ?: "" }
                if (dst.isNotBlank()) {
                    addHistory(src, dst, "📷 拍照翻译（贴原文）")
                    if (App.prefs.ttsAutoSpeak) {
                        Speaker.speakContent(this@CameraTranslateActivity, src, dst)
                    }
                }
            }
            override fun onSingleDone(lineIndex: Int, translated: String?) {
                val line = lines.getOrNull(lineIndex) ?: return
                setStatus(
                    if (translated == null) "这一行翻译失败，可点「📄 全文」整段再试"
                    else "已就地翻译 · 点其他行可继续 · 按住屏幕看原文"
                )
                if (translated != null) {
                    addHistory(line.text, translated, "📷 拍照翻译（点选）")
                    if (App.prefs.ttsAutoSpeak) {
                        Speaker.speakContent(this@CameraTranslateActivity, line.text, translated)
                    }
                }
            }
        })
        // 引擎需要"让手势层重画"的钩子（画未译行的细框 / 高亮正在翻译的行）
        ovEngine.lineLayerInvalidator = { lineLayer.invalidate() }
        ovEngine.onPeekChanged = { peek ->
            if (peek) setStatus("按住查看原文 · 松开显示译文")
            else setStatus(
                when {
                    translations.isNotEmpty() -> "已就地翻译 ${translations.size} 行 · 点其他行可继续 · 按住可看原文"
                    lines.isNotEmpty() -> "识别到 ${lines.size} 行 · 点一行即可就地翻译 · 按住可看原文"
                    else -> "点屏幕上的一行字即可就地翻译"
                }
            )
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            tvHint.text = getString(R.string.camera_translate_t10)
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
                tvHint.text = getString(R.string.camera_translate_t02)
                toast(getString(R.string.camera_translate_t05))
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
            toast(getString(R.string.camera_translate_t06))
            return
        }
        if (busy) {
            toast(getString(R.string.camera_translate_t03))
            return
        }
        btnShutter.isEnabled = false
        if (tapX == null) setStatus("拍摄中…")

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = bitmapFrom(image)
                    if (bmp == null) {
                        btnShutter.isEnabled = true
                        setStatus("照片转换失败，请重试")
                        return
                    }
                    processCapture(bmp, tapX, tapY)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败: ${exception.message}", exception)
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
        ivFrame.setImageBitmap(bmp)
        ivFrame.visibility = View.VISIBLE
        if (old != null && old !== bmp) runCatching { old.recycle() }

        // 引擎接管新帧：内部会清贴片、清识别结果、清在飞集合
        ovEngine.setFrame(bmp)
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
        ovEngine.setFrame(null)
        lineLayer.resetPeek()
        lineLayer.invalidate()
        resultCard.visibility = View.GONE
        btnWhole.isEnabled = false
        progress.visibility = View.GONE
        setStatus(HINT_LIVE)
    }

    // ==================== OCR + 逐行翻译 ====================

    private fun processCapture(bmp: Bitmap, tapX: Float?, tapY: Float?) {
        // **先缩再冻**：相机原图常有 4000px 级（12MP ≈ 48MB ARGB_8888），
        // 而冻结帧要常驻到用户重拍为止。这里只保留"显示 + OCR 共用"的那一张
        // （≤ [com.hunter.screentranslator.util.LineOverlayEngine.MAX_OCR_SIDE]），原图立刻回收 —— 既省内存，又让识别框与贴片天然同坐标系。
        val frame = ovEngine.downscale(bmp, com.hunter.screentranslator.util.LineOverlayEngine.MAX_OCR_SIDE)
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
            ovEngine.setLines(scanned)
            progress.visibility = View.GONE
            btnWhole.isEnabled = scanned.isNotEmpty()
            lineLayer.invalidate()

            if (scanned.isEmpty()) {
                setStatus("没识别到文字。试试：靠近一点、避开反光、让文字占满取景框")
                return@launch
            }

            if (tapX != null && tapY != null) {
                // 「点哪译哪」：只翻用户点到的那一行，其余行只画细框、保持干净
                val hit = ovEngine.pickLineAt(tapX, tapY)
                if (hit == null) {
                    setStatus("识别到 ${scanned.size} 行，但你点的位置没文字 —— 点在字上即可就地翻译")
                } else {
                    setStatus("已锁定这一行，翻译中…")
                    ovEngine.translateLines(listOf(hit), single = true, scope = lifecycleScope)
                }
            } else {
                setStatus("识别到 ${scanned.size} 行 · 整屏逐行贴合中…")
                ovEngine.translateLines(scanned.indices.toList(), single = false, scope = lifecycleScope)
            }
        }
    }

    /**
     * 逐行翻译 [indices] 并把译文贴回各自原位 —— v1.25.0 起委托给共享引擎
     * （同文去重 / 并发上限 / 缓存命中都在引擎里）。
     */
    private fun startTranslate(indices: List<Int>, single: Boolean) {
        ovEngine.translateLines(indices, single = single, scope = lifecycleScope)
    }

    /** 整段翻译：一次请求，带上下文。结果放底部卡片（与原 v1.11.0 行为一致） */
    private fun translateWhole() {
        val text = sourceText
        if (text.isBlank()) {
            toast(getString(R.string.camera_translate_t08))
            return
        }
        if (busy) {
            toast(getString(R.string.camera_translate_t04))
            return
        }
        /** 整段翻译进行中（与引擎的逐行翻译不冲突：这是独立的一条请求） */
        wholeBusy = true
        btnShutter.isEnabled = false
        resultCard.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        tvSource.visibility = View.VISIBLE
        tvSource.text = text
        tvResult.text = ""
        tvStatus.text = getString(R.string.camera_translate_t01)

        lifecycleScope.launch {
            val engineKey = App.prefs.engine
            val result = withContext(Dispatchers.IO) {
                TranslatorFactory.current().translate(text, App.prefs.targetLang, App.prefs.sourceLang)
            }
            progress.visibility = View.GONE
            wholeBusy = false
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

    /** 点选：取景时点 = 拍下这一点并只翻那一处；冻结后再点 = 就近翻译那一行 */
    private fun onFrameTap(x: Float, y: Float) {
        if (busy) {
            toast(getString(R.string.camera_translate_t04))
            return
        }
        if (frozen == null) {
            setStatus("已对准你点的位置，拍摄中…")
            takePhoto(x, y)
            return
        }
        val hit = ovEngine.pickLineAt(x, y) ?: run {
            toast(getString(R.string.camera_translate_t09))
            return
        }
        translations[hit]?.let { done ->
            toast("这一行已是：$done")
            return
        }
        startTranslate(listOf(hit), single = true)
    }

    /**
     * 未译行的细框 + 正在翻译的高亮框。
     *
     * v1.25.0：长按看原文那套手势抽到了共享基类
     * （[com.hunter.screentranslator.util.PeekLineLayer]），绘制与几何换算委托给
     * [ovEngine]。这里只剩"把两者接起来"的胶水。
     */
    private inner class LineLayer(ctx: Context) :
        com.hunter.screentranslator.util.PeekLineLayer(ctx) {

        override fun hasFrame(): Boolean = frozen != null

        override fun onPeekStart() = ovEngine.setPeek(true)

        override fun onPeekEnd() = ovEngine.setPeek(false)

        override fun onTap(x: Float, y: Float) = onFrameTap(x, y)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            ovEngine.drawPendingLines(canvas, ovEngine.peekActive)
        }
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
                    toast(getString(R.string.camera_translate_t07))
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
        // 引擎只持有引用不持有生命周期，这里清掉避免继续引用已 recycle 的位图
        ovEngine.setFrame(null)
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
