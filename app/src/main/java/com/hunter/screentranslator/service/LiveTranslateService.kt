package com.hunter.screentranslator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.api.EngineReadiness
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.overlay.LiveOverlayView
import com.hunter.screentranslator.overlay.RoiPickerView
import com.hunter.screentranslator.ui.LiveTranslateActivity
import com.hunter.screentranslator.util.FrameGrabber
import com.hunter.screentranslator.util.FrameSignature
import com.hunter.screentranslator.util.ImageCompress
import com.hunter.screentranslator.util.LiveOverlayMode
import com.hunter.screentranslator.util.OcrEngine
import com.hunter.screentranslator.util.Roi
import com.hunter.screentranslator.util.Speaker
import com.hunter.screentranslator.util.TranslationRetry
import com.hunter.screentranslator.util.TranslationSession
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 实时屏幕翻译服务（v1.15.0）。
 *
 * 与既有两条路的关系，先说清楚为什么又开一条：
 *   - [ScreenReaderService] 走**无障碍节点文本**。模拟器/游戏把画面画进 Surface，
 *     根本没有文本节点，这条对游戏画面完全无效。
 *   - [ImageTranslateActivity] 走"抓一帧 → 手动框选 → 翻译"。方向对，但每次都要
 *     重新申请授权、手动拖框，是"查字典"的用法，不是"边玩边看"。
 *
 * 本服务把后者变成**常驻闭环**：授权一次，之后按 [Prefs.liveIntervalMs] 持续
 * 取帧、比对、翻译、把译文压回原位。
 *
 * ## 省额度是这个服务的核心工程问题
 *
 * 视觉请求是**按次计费**的，而"每秒一帧"意味着每小时 3600 次请求。所以设了三道闸：
 *
 * 1. **画面没变就不发**（[FrameSignature] + [Prefs.liveDiffThreshold]）：
 *    剧本文本框停着不动时，相邻帧差异通常 < 2，直接跳过。
 * 2. **抖动稳定才发**：检测到变化后不立刻发，而是要求**下一轮**画面与这一轮
 *    连续 [CHANGE_STREAK] 轮都判定为"变了"才认账，确认不是单帧噪声。
 *    （v1.15.2 之前要求"相邻两轮彼此接近"，会被对话框里闪烁的 ▼ 光标永久打断。）
 *    代价是多等一个周期，收益是不把糊成一片的中间帧送去翻译。
 * 3. **指纹记忆**（[memo]）：菜单来回切换、反复出现的同一句提示，指纹命中
 *    就直接复用上次译文，连缓存层都不用碰。
 *
 * 另有**单请求在途**约束：上一发没回来就不再发，网络慢的时候自动退化成低频，
 * 不会把请求堆在队列里越积越多。
 *
 * ## 平台限制（如实告知，不假装能绕）
 *
 * Android 14+ 的 MediaProjection 授权**每次会话都要重新弹窗**，系统不允许长期持有。
 * 也就是说杀掉本服务后想再开，一定会有一次系统授权框——这是平台的，不是实现问题。
 */
class LiveTranslateService : Service() {

    private lateinit var windowManager: WindowManager
    private var grabber: FrameGrabber? = null
    private var overlay: LiveOverlayView? = null
    private var regionSelect: RoiPickerView? = null

    /** 框选期间冻住的那一帧，框选结束后要回收 */
    private var frozenFrame: Bitmap? = null

    /** 正在准备框选遮罩（抓冻结帧 + 等合成器）。这段时间主循环必须让路 */
    @Volatile private var picking = false
    private var projection: MediaProjection? = null

    // Serialize service controls and result commits; expensive capture/encoding stays off the UI thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null

    /** 上一帧 OCR 到的逐行原文 / 对应的逐行译文（用于"只译新增行"） */
    private var lastOcrLines: List<String> = emptyList()
    private var lastTranslatedLines: List<String> = emptyList()

    /** 本机 OCR 连续没认出文字的轮数（只用于日志，不打扰用户） */
    private var ocrEmptyStreak = 0

    /** 拖动模式的自动结束任务 */
    private var dragJob: Job? = null

    private val signature = FrameSignature()

    /** 上一次"认账"的画面指纹（翻译过、或明确判定无需翻译）。只有接受时才更新 */
    private var refSig: IntArray? = null

    /**
     * 连续"判定为变了"的轮数（v1.15.2）。
     *
     * 旧实现要求"相邻两轮彼此接近"才算稳定 —— 结果被对话框里那个**闪烁的 ▼ 光标**
     * 永远打断：光标一亮一灭，两帧永远不接近，条件永远攒不够，译文就永远停在上一段。
     * 改成"相对参照帧连续 N 轮都判定为变"，对闪烁免疫（无论光标亮着还是灭着，
     * 与旧参照帧的差异都很大）。
     */
    private var changeStreak = 0

    /** 连续判定"抓到自己"的轮数；超过上限就放宽检测，避免永久停摆 */
    private var pollutedHits = 0

    /** 主循环累计异常次数（用于如实展示"已自动恢复"而不是静默吞掉） */
    private var loopFailures = 0

    /** 藏叠层后等待的时长；检测到污染会自适应加大，直到能抓到干净画面 */
    @Volatile private var hideWaitMs = CAPTURE_HIDE_MS

    /** 连续失败计数：连续失败时退避，避免对着同一张无法识别的图反复烧请求 */
    private val retry = TranslationRetry()
    private val session = TranslationSession()

    @Volatile private var paused = false
    @Volatile private var translating = false
    @Volatile private var running = false

    private var requestCount = 0

    /** 已经应用过的选区，用来避免每轮都去 updateViewLayout */
    private var appliedRoi: Rect? = null

    /** 上一次推送过的提示文案，用来避免每秒重复 post 同一句话 */
    private var lastNotice: String? = null

    /**
     * 指纹 → 译文 的 LRU（v1.15.0）。
     * accessOrder=true：命中的条目会被移到队尾，淘汰的永远是"最久没用过"的。
     */
    private val memo = object : LinkedHashMap<Int, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>): Boolean =
            size > MEMO_MAX
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        mutableState.value = UiState(Phase.STARTING)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                mutableState.value = UiState(Phase.STOPPING, requestCount)
                session.invalidate()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                paused = true
                session.invalidate()
                overlay?.setStatus("已暂停")
                notifyState()
                return START_STICKY
            }
            ACTION_RESUME -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                paused = false
                // 恢复时把参照指纹清掉：暂停期间画面早就换了好几屏，
                // 拿旧指纹当基准只会立刻误判成"变化"或永远判"没变"
                invalidateTranslation()
                pollutedHits = 0
                overlay?.setStatus("实时翻译 · 待机")
                notifyState()
                return START_STICKY
            }
            ACTION_RETRY -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                invalidateTranslation()
                overlay?.setStatus(if (paused) "已暂停" else "准备重试")
                notifyState()
                return START_STICKY
            }
            ACTION_OCR_TEST -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                runOcrSelfTest()
                return START_STICKY
            }
            ACTION_DRAG -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                enterDragMode()
                return START_STICKY
            }
            ACTION_REPOSITION -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                // 微调改了位置：立刻重摆一次叠层，不用等下一轮 tick
                overlay?.applyStyle()
                overlay?.applyTouchable()
                Roi.parse(App.prefs.liveRoi)?.let {
                    appliedRoi = null
                    overlay?.applyGeometry(displayRect(it), windowManager)
                }
                return START_STICKY
            }
            ACTION_PICK_ROI -> {
                // 这些动作是 startForegroundService 送进来的，若服务其实没在跑，
                // 必须在 5 秒内要么 startForeground 要么 stopSelf —— 否则系统抛
                // ForegroundServiceDidNotStartInTimeException 直接崩。
                if (!running) { stopSelf(); return START_NOT_STICKY }
                startRegionSelect()
                return START_STICKY
            }
        }

        if (running) return START_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            Log.w(TAG, "缺少 MediaProjection 授权数据，无法启动")
            stopSelf()
            return START_NOT_STICKY
        }

        val readiness = App.prefs.readiness()
        if (readiness is EngineReadiness.NotReady) {
            Log.w(TAG, readiness.reason)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        addOverlay()

        // 先起前台服务，再拿 projection。
        // 这个顺序在该 App 的内录功能上已经实测可用，保持一致比"理论上更对"更重要。
        val mp = runCatching {
            (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(resultCode, data)
                ?.also { p ->
                    p.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "MediaProjection 被系统停止")
                            stopSelf()
                        }
                    }, null)
                }
        }.getOrNull()

        if (mp == null) {
            overlay?.showMessage("❌ 屏幕捕获授权失败", "请退出后重新开启实时翻译")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp

        val g = FrameGrabber(this)
        if (!g.start(mp)) {
            overlay?.showMessage("❌ 取帧会话建立失败", "可能是系统限制了屏幕捕获")
            stopSelf()
            return START_NOT_STICKY
        }
        grabber = g

        running = true
        notifyState()
        startLoop()
        val saved = setRoiFromPrefs()
        if (saved != null) {
            overlay?.applyGeometry(displayRect(saved), windowManager)
        } else {
            // 没有可用选区就直接把框选器拉起来。Android 会把通知上的动作折叠起来，
            // 只写一句"点通知里的框选区域"，用户十有八九找不到（实测反馈）。
            Log.i(TAG, "无有效选区，自动进入框选")
            startRegionSelect()
        }
        Log.i(TAG, "实时翻译已启动（间隔 ${App.prefs.liveIntervalMs}ms）")
        return START_STICKY
    }

    // ============================ 叠层 ============================

    private fun addOverlay() {
        if (overlay != null) return
        runCatching {
            overlay = LiveOverlayView(this).also {
                it.attachToWindow(windowManager)
                it.showMessage("实时翻译 · 等待第一帧", "")
            }
        }.onFailure { Log.e(TAG, "叠层添加失败: $it") }
    }

    /**
     * 取当前生效的选区。
     *
     * 旋转 / 分辨率变化后，框选保存的绝对坐标可能已经越界（见 [Roi]）。
     * 这时**不能**硬着头皮去截那块区域——那只会得到一条糊边然后白烧请求。
     * 判定失效就把状态写回叠层，等用户重框。
     */
    private fun screenMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm
    }

    /** dp→px（服务里没有 View 的 dp 工具，自己算一个） */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * **译文叠层实际摆放的矩形**（v1.15.7 与选区解耦）。
     *
     * 这是"不闪"的关键：默认 `edge` 模式下，叠层摆在选区**外侧**
     * （上方空间够就放上方，否则放下方），**根本不遮挡选区**。于是：
     *   · 取帧不需要隐藏叠层  → **不闪烁**（覆盖模式每轮藏 180ms，实测就是闪）
     *   · 也不会截到自己的译文 → "自捕获"这一整类问题直接消失
     *
     * `cover` 模式仍返回选区本身，保留"原位覆盖"的观感，代价是会闪。
     */
    private fun displayRect(roi: Rect): Rect {
        // 先在"不含微调"的原始选区和自动策略下算出基准位置，
        // 再叠加用户微调 —— 这样"拖动 → 反算微调"才有唯一解。
        val placed = autoPlacement(roi)
        val dm = resources.displayMetrics
        val d = dm.density

        // 尺寸：滑杆为 0 时跟随选区（默认，行为不变），否则按屏宽/屏高百分比取
        var w = placed.width()
        var h = placed.height()
        if (App.prefs.livePanelWPercent > 0) w = dm.widthPixels * App.prefs.livePanelWPercent / 100
        if (App.prefs.livePanelHPercent > 0) h = dm.heightPixels * App.prefs.livePanelHPercent / 100

        // 锚在**左上角**：这样"拖动保存"与"摆放"互为逆运算，松手后不会跳。
        // （上一版为滑杆做的"水平居中"在这个前提下会破坏可逆性，已撤回。）
        val left = placed.left + (App.prefs.liveNudgeX * d).toInt()
        val top = placed.top + (App.prefs.liveNudgeY * d).toInt()
        return Rect(left, top, left + w, top + h)
    }

    /** 自动摆放（不含微调）：按选区和当前模式算出基准矩形 */
    private fun autoPlacement(roi: Rect): Rect {
        val base = roi
        if (App.prefs.liveOverlayMode == LiveOverlayMode.COVER) return base
        val dm = screenMetrics()
        val gap = dp(6)
        // 高度不能直接沿用选区高度：译文行数常常比原文多（日文一句话译成中文可能两三行），
        // 沿用就会把译文截断（实测出现过只显示"各显示器"这种半句）。
        // 贴边模式下叠层不影响取帧，所以可以放心给足空间。
        val h = base.height().coerceIn(dp(96), dm.heightPixels / 3)
        // v1.15.8：改成**选余量更大的一侧**，而不是"上方优先"。
        // 原先上方一有位置就用上方，于是对话框在下半屏时，译文正好压在游戏画面上
        // （用户反馈"很容易就盖住游戏画面"）。而模拟器竖屏下游戏画面通常只占上半屏，
        // 下面是一大片黑区：放到下面既不挡画面，也不挡虚拟按键。
        val spaceAbove = base.top
        val spaceBelow = dm.heightPixels - base.bottom
        val top = if (spaceBelow >= spaceAbove) {
            (base.bottom + gap).coerceAtMost((dm.heightPixels - h).coerceAtLeast(0))
        } else {
            (base.top - gap - h).coerceAtLeast(0)
        }
        return Rect(base.left, top, base.right, top + h)
    }

    private fun setRoiFromPrefs(): Rect? {
        val roi = Roi.parse(App.prefs.liveRoi)
        if (roi == null) {
            notice("尚未框选翻译区域", "点通知里的「框选区域」拖一个框")
            return null
        }
        val dm = DisplayMetrics().also {
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
        }
        if (!Roi.isValidOnScreen(roi, dm)) {
            notice("选区已失效（屏幕旋转过？）", "请重新框选翻译区域")
            return null
        }
        lastNotice = null   // 恢复正常后清掉，下次再失效时还能提示
        return roi
    }

    /**
     * 去重推送提示：主循环每秒跑一次，同一句"还没框选"若不去重会每秒 post 一次，
     * 叠层每秒重绘一遍（纯浪费），日得日志也刷屏。
     */
    private fun notice(status: String, text: String = "") {
        val key = "$status|$text"
        if (lastNotice == key) return
        lastNotice = key
        overlay?.showMessage(status, text)
    }

    // ============================ 框选 ============================

    private fun startRegionSelect() {
        if (regionSelect != null || picking) return
        picking = true
        session.invalidate()
        notifyState()
        scope.launch {
            // 从通知栏点「框选区域」时通知栏还开着，立刻抓帧会把通知栏（而且是被
            // 系统脱敏过的黑画面）冻成背景。先等它收起来。
            delay(PICKER_SETTLE_MS)
            // 先把叠层藏掉再抓冻结帧，否则冻下来的背景里会带着我们自己的译文
            val needHide = overlay?.hasContent() == true
            if (needHide) {
                overlay?.setCaptureHidden(true, windowManager)
                delay(CAPTURE_HIDE_MS)
            }
            val frozen = grabber?.grabRoi(null)
            if (needHide) overlay?.setCaptureHidden(false, windowManager)
            withContext(Dispatchers.Main) { showPicker(frozen) }
        }
    }

    private fun showPicker(frozen: Bitmap?) {
        frozenFrame = frozen
        if (regionSelect != null) { picking = false; return }
        runCatching {
            overlay?.showMessage("框选中…", "在画面上拖出文字区域")
            regionSelect = RoiPickerView(
                this,
                frozen,
                onConfirm = { rect ->
                    // 先量框选遮罩自己的视图尺寸。它**必须等于真实屏幕**，
                    // "视图内坐标 == 屏幕坐标"这个前提才成立；不等就是位置偏移的根源。
                    // 把这两个数写进叠层正文，偏差时可直接截图定位（App 自己的日志读不到）。
                    val vw = regionSelect?.width ?: 0
                    val vh = regionSelect?.height ?: 0
                    endRegionSelect()
                    App.prefs.liveRoi = Roi.format(rect)
                    appliedRoi = rect
                    overlay?.applyGeometry(displayRect(rect), windowManager)
                    // 换了选区等于换了内容，参照指纹必须清掉
                    refSig = null
                    changeStreak = 0
                    pollutedHits = 0
                    lastOcrLines = emptyList()
                    lastTranslatedLines = emptyList()
                    val dm = screenMetrics()
                    overlay?.showMessage(
                        "区域已保存",
                        "可继续用「✋ 移动译文框」拖动调整位置与大小"
                    )
                    Log.i(TAG, "翻译区域已保存: $rect 视口=${vw}x$vh 屏=${dm.widthPixels}x${dm.heightPixels}")
                },
                onCancel = {
                    endRegionSelect()
                    overlay?.showMessage("已取消框选", "")
                }
            ).also {
                it.attachToWindow(windowManager)
                it.post { it.logGeometry() }   // 视图尺寸 vs 真实屏幕尺寸，坐标错位时一眼可查
            }
        }.onFailure {
            Log.e(TAG, "框选遮罩添加失败: $it")
            regionSelect = null
        }
        picking = false
        notifyState()
    }

    private fun endRegionSelect() {
        regionSelect?.detachFromWindow(windowManager)
        regionSelect = null
        picking = false
        // 位图还被 ImageView 引用着，必须等窗口移除之后再回收，否则是 use-after-free
        frozenFrame?.recycle()
        frozenFrame = null
        if (running) {
            invalidateTranslation()
            notifyState()
        }
    }

    // ============================ 主循环 ============================

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive && running) {
                try {
                    delay(App.prefs.liveIntervalMs.toLong())
                    if (!running) break
                    if (paused || translating || picking || regionSelect != null) continue
                    tick()
                } catch (e: CancellationException) {
                    throw e          // 取消要正常传播，不能被当成"故障"吞掉
                } catch (e: Throwable) {
                    // v1.15.4：**任何单轮异常都不能让循环死掉。**
                    // 之前 tick() 是光着调的：抛一次异常，整个协程就结束，
                    // 用户看到的是"翻译了第一句之后再也不动"，而且界面上没有任何提示
                    // （自捕获校验里那个 return 也是同类问题的另一面）。
                    loopFailures++
                    if (running && !paused && !picking && regionSelect == null) scheduleRetry()
                    Log.e(TAG, "主循环单轮异常（第 $loopFailures 次），已跳过继续", e)
                    if (loopFailures <= 3) {
                        overlay?.showMessage(
                            "⚠️ 已自动恢复（第 $loopFailures 次）",
                            e.message?.take(48) ?: e.javaClass.simpleName
                        )
                    }
                }
            }
        }
    }

    private suspend fun tick() {
        val currentConfiguration = App.prefs.translationConfiguration()
        if (session.updateConfiguration(currentConfiguration)) {
            invalidateTranslation()
            overlay?.showMessage("配置已更新", "等待重新翻译")
            notifyState()
        }
        if (!retry.canAttempt(SystemClock.elapsedRealtime())) return
        val readiness = App.prefs.readiness()
        if (readiness is EngineReadiness.NotReady) {
            notice("引擎尚未配置", readiness.reason)
            scheduleRetry()
            return
        }
        val epoch = session.generation
        val g = grabber ?: return
        val roi = setRoiFromPrefs() ?: return

        // 选区有效时同步一次窗口位置（旋转后窗口会跟着回正）
        if (appliedRoi != roi) {
            appliedRoi = roi
            overlay?.applyGeometry(displayRect(roi), windowManager)
        }

        // ---- 关键：取帧前把叠层藏掉，否则会截到自己的译文（自捕获回环，见 LiveOverlayView）----
        // 只有"原位覆盖"模式才需要隐藏叠层（贴边模式不遮挡选区 → 不闪；
        // 覆盖模式每轮藏 hideWaitMs，代价就是闪烁，由用户自己选）
        val needHide = App.prefs.liveOverlayMode == LiveOverlayMode.COVER &&
            overlay?.hasContent() == true
        var crop: Bitmap? = null
        try {
            if (needHide) {
                overlay?.setCaptureHidden(true, windowManager)
                delay(hideWaitMs)
            }
            withContext(Dispatchers.Default) { crop = g.grabRoi(roi) }
            val frame = crop ?: return

            // ---- 自捕获校验（v1.15.5 改为**同一轮内当场对比**）----
            //
            // 旧做法：把"叠层可见时的样貌"记在 pollutedSig 里，之后每轮拿新帧去比。
            // 实测**必然误判**：叠层是半透明的，"藏起来"和"露出来"两帧差异本来就小，
            // 于是每一轮都被判成"抓到自己"→ 每轮 return → 连续 8 次后放宽 →
            // 用户看到的就是"只翻译了第一句、之后一直不动"。
            //
            // 现在改成：这一轮**先藏起来抓一帧**（A），**再露出来抓一帧**（B），
            // 当场比 A 与 B：
            //   A 与 B 差别明显 → 说明"藏"确实生效 → A 是干净的游戏画面，可用
            //   A 与 B 几乎一样 → 说明"藏"根本没生效 → A 里就含着我们自己的译文
            // 同一轮的数据自比，不存在"跟过期指纹比"的误判。
            var visibleSig: IntArray? = null
            if (needHide) {
                overlay?.setCaptureHidden(false, windowManager)
                delay(VERIFY_VISIBLE_MS)
                g.grabRoi(roi)?.let {
                    try { visibleSig = signature.of(it) } finally { it.recycle() }
                }
            }

            if (!isRequestCurrent(epoch)) return
            val sig = signature.of(frame)

            if (needHide && visibleSig != null &&
                FrameSignature.diff(sig, visibleSig) < POLLUTED_MAX
            ) {
                pollutedHits++
                if (pollutedHits > POLLUTED_MAX_HITS) {
                    // 连续多轮都判定"藏了等于没藏"。继续挡下去就是**永久停摆**，
                    // 所以放宽并如实告知（可见、可调），而不是一动不动。
                    pollutedHits = 0
                    Log.w(TAG, "隐藏叠层无效，放宽自捕获检测")
                    notice(
                        "⚠️ 隐藏叠层无效",
                        "译文会盖住原文导致看不到新内容；请把翻译区域挪开叠层位置"
                    )
                } else {
                    if (hideWaitMs < HIDE_WAIT_MAX) {
                        hideWaitMs = (hideWaitMs + HIDE_WAIT_STEP).coerceAtMost(HIDE_WAIT_MAX)
                        Log.w(TAG, "隐藏叠层未生效，等待时间调整为 ${hideWaitMs}ms")
                    }
                    return
                }
            } else {
                pollutedHits = 0
            }

            val isFirst = refSig == null
            val d = if (isFirst) -1 else FrameSignature.diff(sig, refSig)   // 仍在用于判定，只是不再显示
            // v1.15.28：调试用的 Δ/阈值/行数读数已移除（正式版不再显示内部指标）。
            // 那套读数是排查"译文不更新"时加的临时观测手段，问题定位完就该撤掉。
            val changed = isFirst || d >= App.prefs.liveDiffThreshold

            if (!changed) {
                changeStreak = 0
                return
            }

            // 连续两轮判定"变了"才认账。看的是"相对旧参照帧仍在变"，而不是
            // "相邻两帧彼此接近" —— 后者会被闪烁光标永远打断（见 changeStreak 注释）。
            changeStreak++
            if (!isFirst && changeStreak < CHANGE_STREAK) return

            changeStreak = 0

            val hash = FrameSignature.hash(sig)
            memo[hash]?.let {
                refSig = sig
                retry.reset()
                // A cached image may not correspond to the OCR prefix kept from the previous screen.
                lastOcrLines = emptyList()
                lastTranslatedLines = emptyList()
                overlay?.showResult(it, fromCache = true)
                notifyState()
                return
            }

            when (translate(frame, hash, epoch)) {
                Attempt.ACCEPTED -> {
                    if (isRequestCurrent(epoch)) {
                        refSig = sig
                        retry.reset()
                    }
                }
                Attempt.FAILED -> if (isRequestCurrent(epoch)) scheduleRetry()
                Attempt.STALE -> Unit
            }
            notifyState()
        } finally {
            if (needHide) overlay?.setCaptureHidden(false, windowManager)
            crop?.recycle()
        }
    }

    private enum class Attempt { ACCEPTED, FAILED, STALE }

    private fun invalidateTranslation() {
        session.invalidate()
        refSig = null
        changeStreak = 0
        memo.clear()
        lastOcrLines = emptyList()
        lastTranslatedLines = emptyList()
        retry.reset()
        lastNotice = null
    }

    private fun isRequestCurrent(epoch: Long): Boolean = running && !paused && !picking && regionSelect == null &&
        session.isCurrent(epoch, App.prefs.translationConfiguration())

    private fun scheduleRetry() {
        refSig = null
        changeStreak = 0
        val wait = retry.failed(SystemClock.elapsedRealtime())
        overlay?.setStatus("翻译失败，${wait / 1000} 秒后重试")
        notifyState()
    }

    /**
     * 把裁好的画面交给视觉模型，并把结果写到叠层上。
     *
     * 用显式分支而不是 `Result.fold { }`：fold 的返回类型 R 由两个 lambda 一起推断，
     * 而 lambda 的"最后一个表达式"会被当成返回值 —— 里面只要有一个不带 else 的 if，
     * 就会报 "'if' must have both main and 'else' branches if used as an expression"。
     */
    /**
     * **本机 OCR + 文本翻译**（v1.15.17）。
     *
     * 这是"免费链路"：识别在本机做（ML Kit 日文模型，不花钱、不联网、不传图），
     * 只把**认出来的文字**交给翻译引擎。所以配一个免费的文本引擎（必应网页端）
     * 就能整条链路零费用；而且因为拿到的是**逐行文本**，"哪一行变了"是字符串比较，
     * 比按图像切条判断可靠得多。
     *
     * 成败取决于 ML Kit 认不认游戏那种点阵假名 —— 认不出时这里会**如实说**
     * 「本机 OCR 没认出文字」，而不是让用户对着沉默的叠层猜。
     */
    /**
     * **本机 OCR + 文本翻译 + 只译新增行**（v1.15.19）。
     *
     * 上一版是"整框都重译"。但 OCR 返回的是**逐行文本**，所以"哪一行变了"
     * 就是字符串比较 —— 比按图像切条判断干净得多，也顺便白捡一个优化：
     * **光标闪烁、画面轻微变化但文字没变时，一次请求都不发。**
     *
     * 与用户的实测一致，这个游戏只有两种变化方式：
     *   · **整框换**（新一页对白）：行文本几乎全变 → 整框重译
     *   · **追加**（同一页里新行出现）：前缀行不变 → **只译新增的那几行**
     *
     * 安全护栏：模型返回的行数必须与送出的行数**一一对应**，否则**退回整框重译**。
     * 错位（把 A 行译文贴到 B 行）比重复翻译糟糕得多，宁可多花一次请求。
     */
    private suspend fun translateByOnDeviceOcr(crop: Bitmap, hash: Int, epoch: Long): Attempt {
        overlay?.showTranslating()
        val lines = OcrEngine.recognizeJapanese(crop, throwOnFailure = true)
        if (!isRequestCurrent(epoch)) return Attempt.STALE
        val newLines = lines.map { it.text.trim() }.filter { it.isNotEmpty() }

        if (newLines.isEmpty()) {
            // 空闲不报警（见 v1.15.18）：只记日志，不动界面，保留上一段译文
            ocrEmptyStreak++
            if (ocrEmptyStreak == OCR_EMPTY_LOG_EVERY) {
                Log.i(TAG, "本机 OCR 连续 $ocrEmptyStreak 轮未认出文字（空闲或认不出）")
                ocrEmptyStreak = 0
            }
            overlay?.setStatus("实时翻译 · 未识别到文字")
            return Attempt.ACCEPTED
        }
        ocrEmptyStreak = 0

        // ---- 与上一帧的逐行文本比较，决定要重译哪一段 ----
        val keep = commonPrefix(lastOcrLines, newLines)
        var from = if (keep > 0 && keep <= lastTranslatedLines.size) keep else 0
        // 文字和上一帧完全一样（画面变了但字没变，例如闪烁光标）→ 不发请求
        if (keep == newLines.size && keep == lastOcrLines.size) {
            Log.i(TAG, "文字未变，跳过请求")
            val text = lastTranslatedLines.joinToString("\n")
            memo[hash] = text
            overlay?.showResult(text, fromCache = true)
            return Attempt.ACCEPTED
        }
        if (from >= newLines.size) from = 0
        val pending = newLines.subList(from, newLines.size)
        Log.i(TAG, "OCR ${newLines.size} 行，沿用前 $from 行，需翻译 ${pending.size} 行")

        val src = pending.joinToString("\n")
        val result = TranslatorFactory.current().translate(src, App.prefs.targetLang, App.prefs.sourceLang)
        requestCount++
        notifyState()
        if (!isRequestCurrent(epoch)) return Attempt.STALE

        val out = result.getOrNull()
        if (out.isNullOrBlank()) {
            val e = result.exceptionOrNull()
            Log.e(TAG, "本机OCR 后翻译失败", e)
            overlay?.showMessage("❌ 翻译失败", e?.message?.take(60) ?: "未知错误")
            return Attempt.FAILED
        }

        val got = out.trim().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (got.size == pending.size) {
            // 行数一一对应 → 拼接：保留未变行的旧译文，只替换新行
            val composed = lastTranslatedLines.take(from) + got
            lastOcrLines = newLines
            lastTranslatedLines = composed
            val text = composed.joinToString("\n")
            memo[hash] = text
            overlay?.showResult(text)
            Log.i(TAG, "[本机OCR] 第 $requestCount 次（${pending.size} 行）：${text.take(60)}")
            if (App.prefs.ttsAutoSpeak) {
                runCatching { Speaker.speakContent(this, src, text) }
            }
        } else {
            // 行数对不上：模型可能合并或拆分了行。为避免错位，退回整框重译。
            Log.w(TAG, "行数不匹配（送出 ${pending.size} 行，返回 ${got.size} 行）→ 退回整框重译")
            val all = TranslatorFactory.current()
                .translate(newLines.joinToString("\n"), App.prefs.targetLang, App.prefs.sourceLang)
            requestCount++
            notifyState()
            if (!isRequestCurrent(epoch)) return Attempt.STALE
            val allOut = all.getOrNull()
            if (allOut.isNullOrBlank()) {
                overlay?.showMessage("❌ 翻译失败", all.exceptionOrNull()?.message?.take(60) ?: "未知错误")
                return Attempt.FAILED
            }
            val translated = allOut.trim().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            // Only reuse a prefix when there is a proven one-to-one line mapping.
            lastOcrLines = if (translated.size == newLines.size) newLines else emptyList()
            lastTranslatedLines = if (translated.size == newLines.size) translated else emptyList()
            memo[hash] = allOut
            overlay?.showResult(allOut)
        }
        return Attempt.ACCEPTED
    }

    /**
     * 两个行列表从头开始"相同"的行数（**模糊比对**）。
     *
     * v1.15.20 的关键修正：原来要求字符串**完全相等**，实测必然失效 ——
     * 同一行在相邻两帧的 OCR 结果几乎不可能逐字节一致：点阵字本身有抗锯齿与
     * 扫描线差异，▼ 光标挪一下、压缩噪声抖一点，某个字就会读成别的字。
     * 只要第 1 行差一个字，`keep` 就等于 0 → **整框重译**，
     * 用户看到的就是"新行出现时旧行还是重新翻译"。
     *
     * 现在用"最长公共子序列占较长串的比例 ≥ [LINE_SAME_RATIO]"判定同一行，
     * 容忍个别错字与识别抖动。
     */
    private fun commonPrefix(a: List<String>, b: List<String>): Int {
        var i = 0
        while (i < a.size && i < b.size && sameLine(a[i], b[i])) i++
        return i
    }

    /** 两行是否"同一行"（容忍 OCR 的个别错字） */
    private fun sameLine(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val longer = if (a.length >= b.length) a else b
        val shorter = if (a.length >= b.length) b else a
        // 长度差太多直接判不同 —— 否则"短行恰好是长行的前缀"会被误当成同一行
        if (longer.length > shorter.length * 2 + 3) return false
        return lcsLength(a, b).toDouble() / longer.length >= LINE_SAME_RATIO
    }

    /** 最长公共子序列长度（行很短，二维 DP 的滚动数组足够） */
    private fun lcsLength(a: String, b: String): Int {
        val dp = IntArray(b.length + 1)
        for (i in 1..a.length) {
            var prev = 0
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev + 1 else maxOf(dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private suspend fun translate(crop: Bitmap, hash: Int, epoch: Long): Attempt {
        if (translating) return Attempt.STALE
        translating = true
        try {
            // v1.15.17：引擎不支持视觉时**自动改走本机 OCR**。
            // 之前这里一律送图，于是配纯文本引擎（例如免密钥的必应网页端）时
            // 每一轮都必然失败，用户看到的就是「当前引擎不支持图片输入」。
            if (!TranslationEngine.fromKey(App.prefs.engine).visionCapable) {
                return translateByOnDeviceOcr(crop, hash, epoch)
            }
            val bytes = withContext(Dispatchers.Default) { ImageCompress.toJpeg(crop) }
            if (!isRequestCurrent(epoch)) return Attempt.STALE
            if (bytes == null) {
                overlay?.showMessage("❌ 图片编码失败", "")
                return Attempt.FAILED
            }
            overlay?.showTranslating()

            // 把"实际送出去的这一块"落盘。这是**唯一能区分两种故障**的证据：
            //   · 图里确实是对话框正文、译文却对不上 → 模型编造（提示词问题）
            //   · 图里根本不是那段文字（含状态栏/位置偏了）→ 选区坐标问题
            // 没有它，只能靠猜。控制页可回看这张图。
            withContext(Dispatchers.IO) {
                runCatching {
                    java.io.File(cacheDir, LAST_CROP_NAME).writeBytes(bytes)
                }.onFailure { Log.w(TAG, "落盘最近一次取图失败: $it") }
            }
            if (!isRequestCurrent(epoch)) return Attempt.STALE

            val result = TranslatorFactory.current(useCache = false)
                .translateImage(bytes, "image/jpeg", App.prefs.targetLang, GAME_HINT, App.prefs.sourceLang)

            requestCount++
            notifyState()
            if (!isRequestCurrent(epoch)) return Attempt.STALE

            val out = result.getOrNull()
            if (out != null) {
                val clean = out.trim()
                if (clean.isBlank()) {
                    notice("实时翻译 · 未识别到文字", "")
                } else {
                    memo[hash] = clean
                    overlay?.showResult(clean)
                    Log.i(TAG, "[实时] 第 $requestCount 次：${clean.take(60)}")
                    // 与既有入口一致：可选自动朗读译文
                    if (App.prefs.ttsAutoSpeak) {
                        runCatching { Speaker.speakContent(this, "", clean) }
                    }
                }
                return Attempt.ACCEPTED
            } else {
                val e = result.exceptionOrNull()
                Log.e(TAG, "实时翻译失败", e)
                overlay?.showMessage("❌ 翻译失败", e?.message?.take(60) ?: "未知错误")
                return Attempt.FAILED
            }
        } finally {
            translating = false
        }
    }

    // ============================ OCR 自检（v1.15.15） ============================

    /**
     * 本机日文 OCR 自检：抓一帧选区 → 用 ML Kit 日文模型识别 → **把识别到的原文原样显示出来**。
     *
     * 为什么需要它："端侧 OCR + 免费文本翻译"这条路能不能走通，
     * 完全取决于 **ML Kit 认不认 GBA 那种 8×8 点阵假名**。
     * 视觉模型能读不等于 ML Kit 能读（完全不同的技术），所以必须先实测，
     * 而不是写完一整条链路再发现第一步就认不出。
     *
     * 自检后会**暂停**主循环，否则下一轮翻译会把结果覆盖掉。
     */
    private fun runOcrSelfTest() {
        paused = true
        session.invalidate()
        notifyState()
        val epoch = session.generation
        scope.launch {
            while (translating) delay(50)
            if (!running || !paused || epoch != session.generation) return@launch
            val roi = Roi.parse(App.prefs.liveRoi)
            if (roi == null) {
                notice("尚未框选翻译区域", "请先框选，再自检")
                return@launch
            }
            val g = grabber ?: return@launch
            overlay?.showMessage("🔍 OCR 自检中…", "本机识别，不走网络")

            val needHide = App.prefs.liveOverlayMode == LiveOverlayMode.COVER &&
                overlay?.hasContent() == true
            if (needHide) {
                overlay?.setCaptureHidden(true, windowManager)
                delay(hideWaitMs)
            }
            if (!running || !paused || epoch != session.generation) {
                if (needHide) overlay?.setCaptureHidden(false, windowManager)
                return@launch
            }
            val bmp = g.grabRoi(roi)
            if (needHide) overlay?.setCaptureHidden(false, windowManager)
            if (bmp == null) {
                overlay?.showMessage("❌ 取帧失败", "稍后重试")
                return@launch
            }
            val lines = try {
                OcrEngine.recognizeJapanese(bmp)
            } catch (e: Exception) {
                Log.e(TAG, "OCR 自检异常", e)
                emptyList()
            } finally {
                bmp.recycle()
            }

            if (!running || !paused || epoch != session.generation) return@launch

            // 自检结果要留得住：暂停主循环，否则下一轮翻译立刻覆盖
            paused = true
            notifyState()
            val body = OcrEngine.toPlainText(lines).ifBlank { "（一个字都没认出来）" }
            overlay?.showMessage("🔍 本机认出 ${lines.size} 行（已暂停）：", body)
            Log.i(TAG, "OCR 自检：${lines.size} 行 / ${body.take(160)}")
        }
    }

    // ============================ 拖动摆放（v1.15.9） ============================

    /**
     * 进入临时拖动模式：叠层变成可拖，拖到合适位置松手即保存。
     *
     * 保存方式是**反算微调**：拿松手时窗口的绝对坐标，减去"自动摆放"的基准坐标，
     * 差值就是用户想要的偏移。这样做的好处是——将来自动策略再改进（比如为不同
     * 模拟器调整默认摆放），用户之前拖出来的**相对**位置仍然有效。
     */
    private fun enterDragMode() {
        val roi = Roi.parse(App.prefs.liveRoi)
        if (roi == null) {
            notice("尚未框选翻译区域", "请先框选，再拖动")
            return
        }
        val v = overlay ?: return
        v.showMessage("✋ 拖动我", "拖到不挡画面的位置，松手保存（${DRAG_TIMEOUT_MS / 1000} 秒后自动结束）")
        v.setDraggable(true, windowManager) { x, y, w, h ->
            saveLayoutFromDrag(roi, x, y, w, h)
            v.setDraggable(false, windowManager)
            v.showMessage("已保存", "位置与大小都记下了（可再次拖动调整）")
        }
        dragJob?.cancel()
        dragJob = scope.launch {
            delay(DRAG_TIMEOUT_MS)
            withContext(Dispatchers.Main) {
                v.setDraggable(false, windowManager)
                v.showMessage("已结束拖动", "可在控制页用 X / Y 微调继续调整")
            }
        }
    }

    /**
     * 把"拖到的位置与大小"换算成可保存的偏好（v1.15.28）。
     *
     * 位置存成相对"自动摆放"基准的偏移（dp），大小存成**屏宽/屏高百分比** ——
     * 这样换设备、换模拟器布局后仍然成立，而不是记死像素。
     * 与 [displayRect] 的换算必须**互为逆运算**，否则松手后下一轮会跳一下。
     */
    private fun saveLayoutFromDrag(roi: Rect, x: Int, y: Int, w: Int, h: Int) {
        val auto = autoPlacement(roi)
        val dm = resources.displayMetrics
        val d = dm.density
        App.prefs.liveNudgeX = ((x - auto.left) / d).toInt()
        App.prefs.liveNudgeY = ((y - auto.top) / d).toInt()
        App.prefs.livePanelWPercent = (w * 100 / dm.widthPixels).coerceIn(0, 100)
        App.prefs.livePanelHPercent = (h * 100 / dm.heightPixels).coerceIn(0, 40)
        Log.i(TAG, "拖动保存：偏移 ${App.prefs.liveNudgeX},${App.prefs.liveNudgeY}dp " +
            "尺寸 ${App.prefs.livePanelWPercent}%×${App.prefs.livePanelHPercent}%")
    }

    // ============================ 前台服务 ============================

    private fun startForegroundCompat() {
        val channelId = "live_translate_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "实时屏幕翻译", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持实时屏幕翻译运行" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "live_translate_service"
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, LiveTranslateActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LiveTranslateService::class.java)
                .setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pickIntent = PendingIntent.getService(
            this, 2,
            Intent(this, LiveTranslateService::class.java).setAction(ACTION_PICK_ROI),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dragIntent = PendingIntent.getService(
            this, 4,
            Intent(this, LiveTranslateService::class.java).setAction(ACTION_DRAG),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 3,
            Intent(this, LiveTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val state = when {
            paused -> "已暂停"
            picking || regionSelect != null -> "框选中…"
            retry.failures > 0 -> "等待重试"
            else -> "运行中"
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("🖼 实时屏幕翻译 · $state")
            .setContentText("已翻译 $requestCount 次 · 下拉展开可「移动译文框 / 重新框选 / 暂停」")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            // 顺序有讲究：折叠状态下只显示前几个动作。
            // "移动译文框"是摆放阶段最常用的，放前面；"停止"放最后。
            .addAction(0, "✋ 移动译文框", dragIntent)
            .addAction(0, if (paused) "▶ 继续" else "⏸ 暂停", pauseIntent)
            .addAction(0, "🔲 框选区域", pickIntent)
            .addAction(0, "⏹ 停止", stopIntent)
            .build()
    }

    /** 计数 / 暂停状态变化后刷新通知 */
    private fun notifyState() {
        if (!running || state.value.phase == Phase.STOPPING) return
        mutableState.value = UiState(
            when {
                paused -> Phase.PAUSED
                picking || regionSelect != null -> Phase.PICKING
                retry.failures > 0 -> Phase.RETRYING
                else -> Phase.RUNNING
            },
            requestCount
        )
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        running = false
        session.invalidate()
        mutableState.value = UiState(Phase.STOPPING, requestCount)
        loopJob?.cancel()
        endRegionSelect()
        grabber?.stop()
        grabber = null
        // projection 由本服务持有，销毁时必须 stop —— 否则屏幕捕获会一直挂着
        // 通知栏图标与耗电（系统会认为"还有人在录屏"）
        runCatching { projection?.stop() }
        projection = null
        overlay?.detachFromWindow(windowManager)
        overlay = null
        scope.cancel()
        instance = null
        mutableState.value = UiState(Phase.STOPPED)
        Log.i(TAG, "实时翻译已停止（本次共 $requestCount 次请求）")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    enum class Phase { STOPPED, STARTING, RUNNING, PAUSED, PICKING, RETRYING, STOPPING }
    data class UiState(val phase: Phase, val requests: Int = 0)

    companion object {
        private const val TAG = "ScreenTranslator"
        private const val NOTIF_ID = 1003

        /** 指纹记忆条数：够覆盖"菜单来回切 + 常见提示语"就够，不必很大 */
        private const val MEMO_MAX = 40

        /**
         * 连续判定"变了"多少轮才认账（v1.15.2）。
         * 2 既能挡掉单帧噪声，又不会被对话框里闪烁的 ▼ 光标卡死。
         */
        private const val CHANGE_STREAK = 2

        /** 抓到的帧与"叠层可见样貌"差异小于它，就认为抓到了自己 */
        private const val POLLUTED_MAX = 4

        /** 连续这么多轮判定"抓到自己"就放弃该判据（否则永久停摆） */
        private const val POLLUTED_MAX_HITS = 8

        /** 每次发现抓到自己就把等待时间加这么多，上限 HIDE_WAIT_MAX */
        private const val HIDE_WAIT_STEP = 120L
        private const val HIDE_WAIT_MAX = 800L

        /** 记录污染指纹前等待叠层把新译文画上去 */
        private const val POLLUTED_SAMPLE_DELAY_MS = 220L

        /**
         * 取帧前隐藏叠层的**初始**等待时长（v1.15.2 从 50ms 提到 180ms）。
         *
         * 50ms（3 帧）在 ColorOS 上实测**不够** —— 合成器还没把 alpha=0 落下去，
         * 抓到的仍是我们自己的译文。180ms 约 11 帧，肉眼依然无感（一次只占
         * 取帧间隔的很小一部分），但足够稳。发现污染还会在 [hideWaitMs] 上自适应加大。
         */
        private const val CAPTURE_HIDE_MS = 180L

        /** 触发框选后先等通知栏/切换动画结束，再去抓冻结帧 */
        private const val PICKER_SETTLE_MS = 700L

        /**
         * 同轮自捕获校验时，"露出来"再抓一帧前的等待。
         * 要等叠层把新译文画上去，否则拿到的还是上一帧。
         */
        private const val VERIFY_VISIBLE_MS = 160L

        /**
         * 游戏画面专用的提示词补充（v1.15.0）。
         *
         * 与"手机截图"的差别很实在：这里是**低分辨率点阵字 + 可能的扫描线/滤镜**，
         * 而且文本框空间有限。不说清楚的话，模型容易输出大段解释或者在几个
         * 糊字上直接摆烂写"无法识别"。
         */
        /**
         * 游戏画面专用的提示词补充。
         *
         * v1.15.6 重写：旧版写着"对模糊字形要结合上下文推断，**不要轻易输出无法识别**"
         * —— 实测这句在鼓励编造：点阵字一模糊，模型就顺着画面里的状态条
         * （体力 / タフ / やる気）**编出一句像模像样的棒球术语**。
         * 用户看到的典型症状就是"译文写着体力提升了50，但游戏内容根本不是这个"。
         *
         * 现在把优先级反过来：**读不清宁可留空，也绝不推测**；并明确告知
         * 状态条不是对白。宁可偶尔漏译一句，也不能给出一句自信的假话。
         */
        private const val GAME_HINT =
            "这是一张**电子游戏画面**的局部截图（常见于模拟器里的复古掌机游戏）。" +
                "画面里的字往往是低分辨率点阵字体，可能有抗锯齿、扫描线或滤镜干扰。请严格遵守：\n" +
                "1. **只翻译你确实看得清的字**。看不清的部分宁可留空，绝对不要根据画面里" +
                "出现的数字、图标、状态条去「推测」出一句听起来合理的话。\n" +
                "2. 游戏界面常有状态条（体力、等级、局数、月份等）。**这些不是对白，" +
                "不要把它们凑成一句话**；只翻译文字框 / 对话框里的正文。\n" +
                "3. 译文要短，尽量能塞进原来的文字框；不要保留日文原文，不要加括号注释。\n" +
                "4. **不要整行省略**：文字框里有几行就输出几行，逐行对应。" +
                "某一行里有几个字看不清，可以只译看得清的部分，或者该行输出「（本行无法辨认）」" +
                "—— 但**不要因为看不清就整行跳过**。漏掉一整行比译得糙严重得多。\n" +
                "5. 如果整张图里没有你能确认读出的文字，只输出：没有识别到文字"

        const val ACTION_STOP = "com.hunter.screentranslator.LIVE_STOP"
        const val ACTION_PAUSE = "com.hunter.screentranslator.LIVE_PAUSE"
        const val ACTION_RESUME = "com.hunter.screentranslator.LIVE_RESUME"
        const val ACTION_RETRY = "com.hunter.screentranslator.LIVE_RETRY"
        const val ACTION_PICK_ROI = "com.hunter.screentranslator.LIVE_PICK_ROI"
        const val ACTION_REPOSITION = "com.hunter.screentranslator.LIVE_REPOSITION"
        const val ACTION_DRAG = "com.hunter.screentranslator.LIVE_DRAG"
        const val ACTION_OCR_TEST = "com.hunter.screentranslator.LIVE_OCR_TEST"

        /** 判定"两行是同一行"的相似度门槛（最长公共子序列 / 较长串长度） */
        private const val LINE_SAME_RATIO = 0.7

        /** 本机 OCR 连续空结果达到这个轮数就在日志里记一次（不影响界面） */
        private const val OCR_EMPTY_LOG_EVERY = 30

        /** 拖动模式的自动结束时长：拖完就恢复"不吃触摸"，不会长期挡住游戏操作 */
        private const val DRAG_TIMEOUT_MS = 20000L

        /** 最近一次实际送出去的那块图（控制页回看用） */
        const val LAST_CROP_NAME = "live-last-crop.jpg"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        var instance: LiveTranslateService? = null
            private set

        private val mutableState = MutableStateFlow(UiState(Phase.STOPPED))
        val state = mutableState.asStateFlow()

        fun isRunning(): Boolean = instance?.running == true

        fun startCapture(ctx: Context, resultCode: Int, data: Intent) {
            if (state.value.phase != Phase.STOPPED) return
            mutableState.value = UiState(Phase.STARTING)
            try {
                ctx.startForegroundService(Intent(ctx, LiveTranslateService::class.java).apply {
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, data)
                })
            } catch (e: RuntimeException) {
                mutableState.value = UiState(Phase.STOPPED)
                throw e
            }
        }

        fun stop(ctx: Context) {
            mutableState.value = UiState(Phase.STOPPING, state.value.requests)
            instance?.session?.invalidate()
            if (!ctx.stopService(Intent(ctx, LiveTranslateService::class.java))) {
                mutableState.value = UiState(Phase.STOPPED)
            }
        }

        /** 发送一个动作给正在运行的服务（用于控制页的暂停/框选按钮） */
        fun sendAction(ctx: Context, action: String) {
            if (!isRunning() || state.value.phase == Phase.STOPPING) return
            val intent = Intent(ctx, LiveTranslateService::class.java).setAction(action)
            ctx.startService(intent)
        }
    }
}
