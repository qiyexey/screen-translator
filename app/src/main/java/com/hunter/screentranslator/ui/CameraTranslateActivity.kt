package com.hunter.screentranslator.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.11.0 拍照翻译。
 *
 * 流程：CameraX 取景 → 按快门 → **端侧 OCR**（ML Kit，不联网）→ 走现有文本引擎翻译。
 *
 * **为什么不用多模态模型直译照片**（像 [ImageTranslateActivity] 那样）：
 * 1. 视觉能力是逐模型的，且自定义中转常常没实现——那条路很可能"装上了也用不了"
 * 2. 每次要上传整张照片；OCR 在本机完成，**照片不出设备**
 * 3. 端侧 OCR 免费且快（百毫秒级），只有翻译那一步需要网络和额度
 * 4. 拿到原文文字后，历史记录不再是"原文为空"，「朗读原文」也终于能用
 *
 * 关键实现细节（已核对 CameraX 1.3.4 源码）：
 * - `ImageProxy.toBitmap()` **不会**应用 `rotationDegrees`
 *   （`ImageUtil.createBitmapFromImageProxy` 只做 YUV/JPEG/RGBA 格式转换）。
 *   不手动旋转的话，竖屏拍出来是横的，OCR 会整片失效——见 [bitmapFrom]。
 * - 位图直接来自相机，与 MediaProjection 无关，因此没有"每次会话都要重新授权"
 *   的限制，也不需要无障碍截图能力。
 */
class CameraTranslateActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "ScreenTranslator"
        const val REQ_CAMERA = 1001

        /** 送 OCR 前把图缩到最长边不超过此值：相机出图常有 4000px 级，先缩能省内存 */
        const val MAX_OCR_SIDE = 2000
    }

    private lateinit var previewView: PreviewView

    /** 常驻提示（在结果卡片之外）。卡片初始隐藏，提示放卡片里用户就看不到任何指引 */
    private lateinit var tvHint: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvResult: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnShutter: MaterialButton
    private lateinit var resultCard: View

    private var imageCapture: ImageCapture? = null
    private var busy = false
    private var lastTranslated = ""

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
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    // 低延迟优先：拍照翻译是"对准就拍"，不需要最高画质
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
                tvHint.text = "对准要翻译的文字，按下方按钮拍摄"
            }.onFailure { e ->
                Log.e(TAG, "相机启动失败: $e")
                tvStatus.text = "相机启动失败：${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
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
        tvStatus.text = "拍摄中…"

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = bitmapFrom(image)
                    if (bmp == null) {
                        busy = false
                        btnShutter.isEnabled = true
                        tvStatus.text = "照片转换失败，请重试"
                        return
                    }
                    processCapture(bmp)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败: ${exception.message}", exception)
                    busy = false
                    btnShutter.isEnabled = true
                    tvStatus.text = "拍照失败：${exception.message}"
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

    // ==================== OCR + 翻译 ====================

    private fun processCapture(bmp: Bitmap) {
        resultCard.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        tvSource.text = ""
        tvResult.text = ""
        tvStatus.text = "识别文字中（本机完成，不联网）…"

        lifecycleScope.launch {
            // ML Kit 的 process() 是异步回调式的，包成挂起函数；压缩与识别都放后台，
            // 避免主线程做位图缩放导致掉帧。
            val lines = withContext(Dispatchers.Default) {
                val scaled = downscale(bmp, MAX_OCR_SIDE)
                OcrEngine.recognize(scaled)
            }
            val text = OcrEngine.toPlainText(lines)

            if (text.isBlank()) {
                progress.visibility = View.GONE
                busy = false
                btnShutter.isEnabled = true
                tvStatus.text = "没识别到文字。试试：靠近一点、避开反光、让文字占满取景框"
                return@launch
            }

            tvSource.text = text
            tvStatus.text = "翻译中…"
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
                    tvStatus.text = "翻译完成（端侧识别 + ${App.prefs.engine}）"
                    runCatching {
                        HistoryStore.add(
                            source = text,
                            translated = out,
                            mode = "📷 拍照翻译",
                            targetLang = App.prefs.targetLang,
                            engine = engineKey
                        )
                    }
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

    // ==================== UI ====================

    private fun buildUi(): View {
        val root = FrameLayout(this)

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)

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

        // ---- 底部：快门 ----
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { bottomMargin = dp(24) }
        }
        tvHint = TextView(this).apply {
            text = "正在启动相机…"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // 相机画面上明暗不定，加一层阴影保证可读
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        bottomBar.addView(tvHint)
        btnShutter = MaterialButton(this).apply {
            text = "📸 拍摄并翻译"
            textSize = 15f
            setOnClickListener { takePhoto() }
        }
        bottomBar.addView(btnShutter)
        root.addView(bottomBar)

        // ---- 结果卡片（初始隐藏）----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.argb(235, 20, 20, 20))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { bottomMargin = dp(96) }
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

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        actions.addView(MaterialButton(this).apply {
            text = "🔊 朗读"
            textSize = 13f
            setOnClickListener {
                if (tvSource.text.isNotBlank() || lastTranslated.isNotBlank()) {
                    Speaker.speakContent(this@CameraTranslateActivity, tvSource.text.toString(), lastTranslated)
                } else {
                    toast("还没有可朗读的内容")
                }
            }
        })
        actions.addView(MaterialButton(this).apply {
            text = "🔄 重拍"
            textSize = 13f
            setOnClickListener {
                resultCard.visibility = View.GONE
                tvHint.text = "对准要翻译的文字，按下方按钮拍摄"
            }
        })
        actions.addView(MaterialButton(this).apply {
            text = "✕ 收起"
            textSize = 13f
            setOnClickListener { resultCard.visibility = View.GONE }
        })
        card.addView(actions)

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        card.addView(progress)

        resultCard = card
        card.visibility = View.GONE
        root.addView(card)

        return root
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
