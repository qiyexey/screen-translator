package com.hunter.screentranslator.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.slider.Slider
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.databinding.ActivityLiveTranslateBinding
import com.hunter.screentranslator.service.LiveTranslateService
import com.hunter.screentranslator.util.EdgeToEdge
import com.hunter.screentranslator.util.LiveOverlayMode
import com.hunter.screentranslator.util.Roi

/**
 * 实时屏幕翻译控制页（v1.15.0）。
 *
 * 只做三件事：**选区域**、**调节奏**、**开关**。
 * 播放期间真正要用的按钮（暂停 / 框选 / 停止）都挂在**通知栏**上——
 * 因为那时候用户在看游戏，切回 App 本身就是打断；而通知栏在游戏上方一拉就有。
 */
class LiveTranslateActivity : BaseActivity() {

    private lateinit var b: ActivityLiveTranslateBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLiveTranslateBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.install(this)

        b.topAppBar.setNavigationOnClickListener { finish() }
        b.btnEngineSettings.setOnClickListener {
            startActivity(Intent(this, EngineSettingsActivity::class.java))
        }

        setupSliders()
        setupSwitch()

        b.btnStart.setOnClickListener { start() }
        b.btnStop.setOnClickListener {
            stopService(Intent(this, LiveTranslateService::class.java))
            toast(getString(R.string.live_translate_t20))
            refresh()
        }
        b.btnPause.setOnClickListener {
            if (!LiveTranslateService.isRunning()) return@setOnClickListener
            LiveTranslateService.sendAction(
                this,
                if (pausedLocal) LiveTranslateService.ACTION_RESUME
                else LiveTranslateService.ACTION_PAUSE
            )
            pausedLocal = !pausedLocal
            // 服务端改状态是异步的，这里立刻回读会读到旧值；先按本地状态画，
            // 回到前台时 onResume → refresh() 会以服务端为准纠正过来。
            refresh()
        }
        b.btnPickRoi.setOnClickListener { pickRoi() }
        // v1.15.15：本机日文 OCR 自检 —— 决定"免费链路"可不可行
        b.btnOcrTest.setOnClickListener {
            if (!LiveTranslateService.isRunning()) {
                toast(getString(R.string.live_translate_t23))
                return@setOnClickListener
            }
            LiveTranslateService.sendAction(this, LiveTranslateService.ACTION_OCR_TEST)
            moveTaskToBack(true)
        }
        // v1.15.9：临时拖动模式 —— 拖完自动恢复"不吃触摸"，不会长期挡住游戏操作
        b.btnDrag.setOnClickListener {
            if (!LiveTranslateService.isRunning()) {
                toast(getString(R.string.live_translate_t23))
                return@setOnClickListener
            }
            LiveTranslateService.sendAction(this, LiveTranslateService.ACTION_DRAG)
            moveTaskToBack(true)
        }
    }

    /** 本页自己记的暂停态。只用于按钮文案，真实状态以服务里为准（onResume 会纠正） */
    private var pausedLocal = false

    override fun onResume() {
        super.onResume()
        refresh()
        refreshLastCrop()
    }

    /**
     * 回显"最近一次实际送出去的图"（v1.15.6）。
     * 这是唯一能把"选区/坐标错"和"模型读错/编造"分开的证据 ——
     * 光看译文和游戏画面对不上，两种可能长得一模一样。
     */
    private fun refreshLastCrop() {
        val f = java.io.File(cacheDir, LiveTranslateService.LAST_CROP_NAME)
        if (!f.exists()) {
            b.ivLastCrop.setImageDrawable(null)
            return
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        if (bmp == null) b.ivLastCrop.setImageDrawable(null)
        else b.ivLastCrop.setImageBitmap(bmp)
    }

    // ==================== 参数 ====================

    /**
     * 滑杆用 0..N 的整数位置映射到真实取值。
     *
     * 滑杆只有 0..N 的整数档位，而间隔是毫秒（步进 100）、阈值是 2..50 ——
     * 直接把真实值当 valueTo 会让间隔滑杆从 0 到 5000，前面 90% 的行程都在 500ms
     * 以内，手感很差。所以统一用"档位 → 值"的映射。
     *
     * v1.19.0：SeekBar → M3 Slider。Slider 的 value 越界抛异常（SeekBar 只静默
     * 钳制），因此每处初始化都补了 coerceIn(0f, valueTo)。
     */
    private fun setupSliders() {
        b.seekInterval.valueTo = (INTERVAL_STEPS).toFloat()
        b.seekInterval.value = (msToStep(App.prefs.liveIntervalMs)).toFloat().coerceIn(0f, 46f)
        b.tvIntervalValue.text = "${App.prefs.liveIntervalMs}ms"
        b.seekInterval.addOnChangeListener(object : SimpleSliderListener() {
            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                val progress = value.toInt()
                val ms = stepToMs(progress)
                b.tvIntervalValue.text = "${ms}ms"
                if (fromUser) App.prefs.liveIntervalMs = ms
            }
        })

        b.seekDiff.valueTo = (DIFF_MAX - DIFF_MIN).toFloat()
        b.seekDiff.value = (App.prefs.liveDiffThreshold - DIFF_MIN).toFloat().coerceIn(0f, 19f)
        b.tvDiffValue.text = "${App.prefs.liveDiffThreshold}"
        b.seekDiff.addOnChangeListener(object : SimpleSliderListener() {
            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                val progress = value.toInt()
                val v = progress + DIFF_MIN
                b.tvDiffValue.text = "$v"
                if (fromUser) App.prefs.liveDiffThreshold = v
            }
        })

        // 译文面板外观（v1.15.19）
        b.seekAlpha.valueTo = (((1f - 0.15f) * 100).toInt()).toFloat()  // 15% ~ 100%
        b.seekAlpha.value = (((App.prefs.liveOverlayAlpha - 0.15f) * 100).toInt()).toFloat().coerceIn(0f, 85f)
        b.tvAlphaValue.text = "${(App.prefs.liveOverlayAlpha * 100).toInt()}%"
        b.seekAlpha.addOnChangeListener(object : SimpleSliderListener() {
            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                val progress = value.toInt()
                val v = 0.15f + progress / 100f
                b.tvAlphaValue.text = "${(v * 100).toInt()}%"
                if (fromUser) {
                    App.prefs.liveOverlayAlpha = v
                    LiveTranslateService.sendAction(this@LiveTranslateActivity, LiveTranslateService.ACTION_REPOSITION)
                }
            }
        })

        // 译文面板尺寸（0 = 自动/跟随选区）
        fun fmt(v: Int, unit: String) = if (v == 0) "自动" else "$v$unit"
        b.seekPanelW.valueTo = (100).toFloat()
        b.seekPanelW.value = (App.prefs.livePanelWPercent).toFloat().coerceIn(0f, 100f)
        b.tvPanelWValue.text = fmt(App.prefs.livePanelWPercent, "%")
        b.seekPanelW.addOnChangeListener(object : SimpleSliderListener() {
            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                val progress = value.toInt()
                b.tvPanelWValue.text = fmt(progress, "%")
                if (fromUser) {
                    App.prefs.livePanelWPercent = progress
                    LiveTranslateService.sendAction(this@LiveTranslateActivity, LiveTranslateService.ACTION_REPOSITION)
                }
            }
        })

        b.seekPanelH.valueTo = (40).toFloat()
        b.seekPanelH.value = (App.prefs.livePanelHPercent).toFloat().coerceIn(0f, 40f)
        b.tvPanelHValue.text = fmt(App.prefs.livePanelHPercent, "%")
        b.seekPanelH.addOnChangeListener(object : SimpleSliderListener() {
            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                val progress = value.toInt()
                b.tvPanelHValue.text = fmt(progress, "%")
                if (fromUser) {
                    App.prefs.livePanelHPercent = progress
                    LiveTranslateService.sendAction(this@LiveTranslateActivity, LiveTranslateService.ACTION_REPOSITION)
                }
            }
        })

        b.seekTextScale.valueTo = (((1.8f - 0.6f) * 100).toInt()).toFloat()  // 0.6x ~ 1.8x
        b.seekTextScale.value = (((App.prefs.liveTextScale - 0.6f) * 100).toInt()).toFloat().coerceIn(0f, 120f)
        b.tvTextScaleValue.text = String.format("%.2f×", App.prefs.liveTextScale)
        b.seekTextScale.addOnChangeListener(object : SimpleSliderListener() {
            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                val progress = value.toInt()
                val v = 0.6f + progress / 100f
                b.tvTextScaleValue.text = String.format("%.2f×", v)
                if (fromUser) {
                    App.prefs.liveTextScale = v
                    LiveTranslateService.sendAction(this@LiveTranslateActivity, LiveTranslateService.ACTION_REPOSITION)
                }
            }
        })

        // 位置微调：0 位对应进度 NUDGE_MAX，范围 -NUDGE_MAX .. +NUDGE_MAX
        b.seekNudgeX.valueTo = (NUDGE_MAX * 2).toFloat()
        b.seekNudgeX.value = (App.prefs.liveNudgeX + NUDGE_MAX).toFloat().coerceIn(0f, 160f)
        b.tvNudgeXValue.text = "${App.prefs.liveNudgeX}dp"
        b.seekNudgeX.addOnChangeListener(object : SimpleSliderListener() {
            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                val progress = value.toInt()
                val v = progress - NUDGE_MAX
                b.tvNudgeXValue.text = "${v}dp"
                if (fromUser) {
                    App.prefs.liveNudgeX = v
                    // 立刻让叠层按新偏移就位（正在运行时也能马上看到效果）
                    LiveTranslateService.sendAction(this@LiveTranslateActivity, LiveTranslateService.ACTION_REPOSITION)
                }
            }
        })

        b.seekNudgeY.valueTo = (NUDGE_MAX * 2).toFloat()
        b.seekNudgeY.value = (App.prefs.liveNudgeY + NUDGE_MAX).toFloat().coerceIn(0f, 160f)
        b.tvNudgeYValue.text = "${App.prefs.liveNudgeY}dp"
        b.seekNudgeY.addOnChangeListener(object : SimpleSliderListener() {
            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                val progress = value.toInt()
                val v = progress - NUDGE_MAX
                b.tvNudgeYValue.text = "${v}dp"
                if (fromUser) {
                    App.prefs.liveNudgeY = v
                    LiveTranslateService.sendAction(this@LiveTranslateActivity, LiveTranslateService.ACTION_REPOSITION)
                }
            }
        })
    }

    private fun setupSwitch() {
        // 原位覆盖 = 会闪；默认贴边不遮（不闪）
        b.switchCoverMode.isChecked = App.prefs.liveOverlayMode == LiveOverlayMode.COVER
        b.switchCoverMode.setOnCheckedChangeListener { _, checked ->
            App.prefs.liveOverlayMode = if (checked) LiveOverlayMode.COVER else LiveOverlayMode.EDGE
            // 立刻按新模式重摆叠层
            LiveTranslateService.sendAction(this, LiveTranslateService.ACTION_REPOSITION)
            toast(if (checked) "原位覆盖：译文会压在原文上，代价是周期性闪烁" else "已切回贴边：不遮挡原文，不闪")
        }

        b.switchTouchable.isChecked = App.prefs.liveOverlayTouchable
        b.switchTouchable.setOnCheckedChangeListener { _, checked ->
            App.prefs.liveOverlayTouchable = checked
            toast(if (checked) "叠层可触摸：会拦住那一块的点击" else "叠层不再吃触摸")
        }
    }

    // ==================== 启动 ====================

    private fun start() {
        // v1.15.22：删掉"当前引擎不能读图"的拦截弹窗。
        // 那个提示是 v1.15.16 加的"警告但不拦"，但现在**已经过时**：
        // 引擎读不了图时会**自动改走「本机 OCR + 文本翻译」**（见 LiveTranslateService），
        // 根本不会失败。继续弹窗只会让人以为不能用（用户反馈"一直显示不能用图片翻译"）。
        requestCapture()
    }

    /** 第 2、3 步：悬浮窗权限 + 屏幕捕获授权（两条路径共用） */
    private fun requestCapture() {
        // 2. 悬浮窗权限：没有它叠层根本加不上，会出现"跑了但什么都看不到"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("译文要盖在游戏画面上，必须拿到悬浮窗权限。")
                .setPositiveButton("去授权") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 3. 屏幕捕获授权（每次会话都要，Android 14+ 平台规定）
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("与控制页同生命周期，startActivityForResult 足够，无需 Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            toast(getString(R.string.live_translate_t22))
            return
        }
        val intent = Intent(this, LiveTranslateService::class.java).apply {
            putExtra(LiveTranslateService.EXTRA_RESULT_CODE, resultCode)
            putExtra(LiveTranslateService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        pausedLocal = false
        toast(getString(R.string.live_translate_t21))
        refresh()
    }

    /**
     * 框选：把本页退到后台，让游戏画面露出来，再由服务铺一层全屏遮罩。
     *
     * 之所以要退后台，是因为遮罩是全屏悬浮窗，如果本页还在前台，用户看到的
     * 是自己的设置页，框选出来的是设置页上的区域 —— 毫无意义。
     */
    private fun pickRoi() {
        if (!LiveTranslateService.isRunning()) {
            toast(getString(R.string.live_translate_t23))
            return
        }
        LiveTranslateService.sendAction(this, LiveTranslateService.ACTION_PICK_ROI)
        moveTaskToBack(true)
    }

    // ==================== 状态刷新 ====================

    private fun refresh() {
        val running = LiveTranslateService.isRunning()
        b.tvRunStatus.text = if (running) "✅ 运行中" else "未运行"
        b.btnStart.visibility = if (running) View.GONE else View.VISIBLE
        b.btnPause.visibility = if (running) View.VISIBLE else View.GONE
        b.btnStop.visibility = if (running) View.VISIBLE else View.GONE
        b.btnPause.text = if (pausedLocal) "▶ 继续" else "⏸ 暂停"
        b.btnPickRoi.isEnabled = running

        // v1.15.26：这条状态原来写的是"不支持图片输入，无法使用" —— 已经**过时且误导**。
        // 引擎读不了图时会自动改走「本机 OCR + 文本翻译」，两种引擎都能用，只是链路不同。
        // 现在如实写出各自走哪条链路，并且**都不再用报错色**（都不是错误）。
        val engine = TranslationEngine.fromKey(App.prefs.engine)
        b.tvEngineStatus.text = if (engine.visionCapable) {
            "✅「${engine.displayName}」· 读图模式：画面直接交给模型识别并翻译（按次计费）"
        } else {
            "✅「${engine.displayName}」· 免费模式：本机 OCR 认字 → 只把文字发去翻译（图片不出设备）"
        }
        b.tvEngineStatus.setTextColor(resColor(R.color.md_success))

        // 选区状态：解析不出来 / 在当前屏幕下失效，都要如实说，不能让用户
        // 对着一句"已框选"发呆却永远等不到译文
        val roi = Roi.parse(App.prefs.liveRoi)
        b.tvRoiStatus.text = when {
            roi == null -> "尚未框选（点下面的按钮，或从通知栏点「框选区域」）"
            !Roi.isValidOnScreen(roi, resources.displayMetrics) ->
                "已有选区 ${roi.width()}×${roi.height()}，但当前屏幕下已失效，请重新框选"
            else -> "✅ 已框选 ${roi.width()}×${roi.height()} @ (${roi.left}, ${roi.top})"
        }
    }

    // ==================== 小工具 ====================

    /**
     * Slider 的"只关心 onValueChange"基类。
     *
     * 原来是 SeekBar.OnSeekBarChangeListener（要写满 onProgressChanged /
     * onStartTrackingTouch / onStopTrackingTouch 三个回调，其中两个是空的）；
     * Slider.OnChangeListener 只有一个抽象方法，直接就是想要的最小接口。
     */
    private abstract class SimpleSliderListener : Slider.OnChangeListener

    private fun msToStep(ms: Int): Int =
        ((ms - INTERVAL_MIN_MS) / INTERVAL_STEP_MS).coerceIn(0, INTERVAL_STEPS)

    private fun stepToMs(step: Int): Int =
        (INTERVAL_MIN_MS + step * INTERVAL_STEP_MS).coerceIn(400, 5000)

    companion object {
        private const val REQ_PROJECTION = 20
        private const val INTERVAL_MIN_MS = 400
        private const val INTERVAL_STEP_MS = 100
        private const val INTERVAL_STEPS = 46          // 400 + 46*100 = 5000ms
        /** 与 Prefs 里 coerceIn(1, 20) 保持一致（v1.15.11 重新定标后收窄了范围） */
        private const val DIFF_MIN = 1
        private const val DIFF_MAX = 20
        /** 与 Prefs.NUDGE_LIMIT_DP 保持一致（滑杆 1 格 = 1dp） */
        private const val NUDGE_MAX = com.hunter.screentranslator.util.Prefs.NUDGE_SLIDER_DP
    }
}
