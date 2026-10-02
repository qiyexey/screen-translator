package com.hunter.screentranslator.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.Translator
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.overlay.OverlayView
import com.hunter.screentranslator.overlay.RegionCoverView
import com.hunter.screentranslator.util.HistoryStore
import com.hunter.screentranslator.util.OcrEngine
import com.hunter.screentranslator.util.ScriptDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 无障碍服务。v1.5.0 支持四种翻译模式：
 *
 * 1. 全屏自动翻译：屏幕内容变化 → 抓取整屏文字翻译（autoTranslate 开关）
 * 2. 划词翻译：长按选中文字 → 自动翻译选区（selectionTranslate 开关）
 * 3. 悬浮球定点翻译：球拖到哪松手 → 翻译该位置文字（floatingBall 开关，translateAt()）
 * 4. 框选翻译：双击悬浮球 → 拖出矩形 → 翻译选区内全部文字（translateInRegion()）
 *
 * 另有剪贴板监听兜底（clipboardTranslate 开关，默认关）。
 */
class ScreenReaderService : AccessibilityService() {

    // 每次翻译时从工厂取最新引擎实例，切换引擎立即生效
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 全屏模式
    private var fullscreenJob: Job? = null
    private var lastFullscreenText: String? = null

    // 划词模式
    private var selectionJob: Job? = null
    private var lastSelectionText: String? = null

    // 定点模式。
    // 修复：原为普通 Boolean，存在两个问题 ——
    //   ① check-then-act 非原子，两次快速拖拽可同时通过检查；
    //   ② 标志在 scope.launch 之前置 true，若协程排队期间作用域被取消
    //      （onDestroy → scope.cancel()），协程体根本不执行，finally 不会跑，
    //      标志永远停在 true → 定点翻译永久失效（悬浮球"变砖"），用户无任何提示。
    // 改用 AtomicBoolean.compareAndSet，并把复位放进协程的 finally（协程真正开始后才生效）。
    private val translatingAt = AtomicBoolean(false)

    // 剪贴板
    private var clipboard: ClipboardManager? = null
    private var lastClipText: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
        // v1.29.0：连上了 = 用户想开着（或刚被自动恢复），清掉"用户主动关闭"标记
        App.prefs.accessibilityEverOn = true
        App.prefs.accessibilityUserDisabled = false

        ensureOverlayStarted()
        setupClipboardListener()

        runCatching {
            serviceInfo = serviceInfo?.apply {
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 200
            }
        }.onFailure { Log.w(TAG, "serviceInfo 配置失败: $it") }
    }

    private fun ensureOverlayStarted() {
        if (!App.prefs.overlayEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "悬浮窗权限未授予")
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(Intent(this, OverlayService::class.java))
            } else {
                startService(Intent(this, OverlayService::class.java))
            }
        }.onFailure { Log.e(TAG, "启动悬浮窗服务失败: $it") }
    }

    // ============================ 事件分发 ============================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 自家悬浮面板改文字（"正在翻译…" → 译文）也会产生 WINDOW_CONTENT_CHANGED。
        // 不在这里拦掉的话，它会 cancel 掉正在进行的全屏翻译，而新任务又因
        // lastFullscreenText 相同直接返回 —— 面板永远停在"正在翻译…"。
        if (event.packageName?.toString() == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (App.prefs.autoTranslate && App.prefs.overlayEnabled) {
                    scheduleFullscreenTranslate()
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (App.prefs.selectionTranslate && App.prefs.overlayEnabled) {
                    handleSelectionEvent(event)
                }
            }
        }
    }

    // ============================ 模式1：全屏自动翻译 ============================

    private fun scheduleFullscreenTranslate() {
        fullscreenJob?.cancel()
        fullscreenJob = scope.launch {
            delay(DEBOUNCE_MS)
            doFullscreenTranslate()
        }
    }

    private suspend fun doFullscreenTranslate() {
        val root = activeRoot() ?: return
        val pkg = root.packageName?.toString()
        if (pkg == packageName) return  // 不翻译自己
        // v1.29.0：按 App 过滤。被排除的 App 连"读不到文字"的提示都不出，安安静静
        if (!App.prefs.autoTranslateAllowedIn(pkg)) return

        val collected = StringBuilder()
        collectText(root, collected, 0)
        val text = collected.toString().trim()

        if (text.length < 2) {
            OverlayService.update("⚠️ 当前界面没有读到文字", "该应用可能是画布渲染（视频/游戏类），无障碍读不到")
            return
        }
        if (text == lastFullscreenText) return

        // v1.29.0：已经是目标语言的行不送去翻译；整屏都是目标语言就整次跳过。
        // 中文界面里只有几段英文时，只翻那几段，省额度也省时间。
        val toTranslate = if (App.prefs.skipSameLanguage) {
            ScriptDetector.dropLinesAlreadyIn(text, App.prefs.targetLang)
        } else text
        if (toTranslate.isBlank()) {
            Log.d(TAG, "[全屏] 整屏已是目标语言，跳过（$pkg）")
            lastFullscreenText = text
            return
        }

        Log.i(TAG, "[全屏] 收集 ${text.length} 字符、需翻译 ${toTranslate.length} 字符（$pkg），开始翻译")
        // 翻译跑完（成功或失败）才记账：中途被取消（新事件到来）时会抛
        // CancellationException 跳过这行，下一轮同样的文本还能重新翻；
        // 失败也记账，是为了不对同一段文字反复烧失败请求（与原行为一致）。
        // 全屏是整屏文字拼起来的，不会是"一个单词"，也不该被当成查词
        translateAndShow(toTranslate, "[全屏]", allowDictionary = false)
        lastFullscreenText = text
    }

    // ============================ 模式2：划词翻译 ============================

    private fun handleSelectionEvent(event: AccessibilityEvent) {
        selectionJob?.cancel()
        // 复制一份事件数据（event 对象会被系统回收复用）
        val fromIndex = event.fromIndex
        val toIndex = event.toIndex
        val sourceNode = event.source
        val sourceText: CharSequence? = sourceNode?.text
        val pkg = event.packageName?.toString()

        if (pkg == packageName) return
        if (!App.prefs.autoTranslateAllowedIn(pkg)) return

        // 关键过滤：可编辑节点（输入框/搜索框）的选区事件多为系统自动全选（如点击
        // 搜索框全选旧关键词），不是用户主动划词，直接忽略，避免各页面误触发翻译。
        if (sourceNode?.isEditable == true) {
            Log.d(TAG, "[划词] 忽略可编辑节点的选区事件（输入框自动全选）")
            return
        }

        selectionJob = scope.launch {
            delay(SELECTION_DEBOUNCE_MS)

            // 光标移动（无实际选区）跳过
            if (fromIndex < 0 || toIndex <= fromIndex) return@launch
            val full = sourceText?.toString() ?: return@launch
            if (toIndex > full.length) return@launch

            val selected = full.subSequence(fromIndex, toIndex).toString().trim()
            if (selected.length < 2) return@launch
            if (selected == lastSelectionText) return@launch
            lastSelectionText = selected
            if (App.prefs.skipSameLanguage && ScriptDetector.isAlreadyIn(selected, App.prefs.targetLang)) {
                Log.d(TAG, "[划词] 选中内容已是目标语言，跳过")
                return@launch
            }

            Log.i(TAG, "[划词] 选中 ${selected.length} 字符，开始翻译")
            translateAndShow(selected, "[划词]")
        }
    }

    // ============================ 模式3：悬浮球定点翻译 ============================

    /**
     * 悬浮球松手时调用：翻译屏幕坐标 (x, y) 处的文字。
     *
     * 算法（两轮容错）：
     * 1. 精确命中：DFS 找所有「bounds 包含该点且有文字」的节点，取面积最小者
     * 2. 附近容错：若无精确命中，取距该点最近的文字节点（80dp 内），容错 bounds 偏移/边界毛刺
     * 3. 都失败 → 诊断模式：统计全屏可读字符数，告诉用户是「应用不暴露文字」还是「定位没对准」
     */
    fun translateAt(x: Int, y: Int) {
        // 原子 check-and-set：并发拖拽时只有一个能进来
        if (!translatingAt.compareAndSet(false, true)) return
        Log.i(TAG, "[定点] ($x, $y)")

        val job = scope.launch {
            try {
                val root = activeRoot()
                if (root == null) {
                    OverlayService.update("⚠️ 读不到当前界面", "请确认无障碍服务已开启")
                    return@launch
                }

                val hit = findTextAtPoint(root, x, y)
                if (hit == null) {
                    // 诊断：这个界面到底读不读得到文字？
                    val sb = StringBuilder()
                    collectText(root, sb, 0)
                    val total = sb.length
                    Log.d(TAG, "[定点] 无命中；全屏可读 $total 字符")
                    OverlayService.update(
                        "📍 这个位置没有文字",
                        if (total == 0) {
                            "整个界面都读不到文字：这个应用不向无障碍暴露内容\n\n试试：长按选中文字复制，并开启「复制即翻译」"
                        } else {
                            "全屏能读到 $total 字符，但球心位置没有文字节点\n\n把球对准文字正中间再松手；或直接长按选中文字（划词翻译）"
                        }
                    )
                    return@launch
                }

                val (text, exact) = hit
                Log.i(TAG, "[定点] 命中${if (exact) "" else "（附近容错）"}「${text.take(40)}…」(${text.length} 字符)")
                translateAndShow(text, "[定点]")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 作用域取消，正常路径 —— 复位交给 finally
                throw e
            } catch (e: Exception) {
                // 修复：原来只有 finally 没有 catch，异常会冒泡成未捕获协程异常
                Log.e(TAG, "[定点] 失败", e)
                OverlayService.update("⚠️ 定点翻译失败", e.message ?: "未知错误")
            } finally {
                translatingAt.set(false)
            }
        }

        // 兜底：若协程在真正开始执行前就被取消（作用域已 cancel），finally 不会运行，
        // 这里在取消回调里复位，保证标志不残留。
        job.invokeOnCompletion { if (job.isCancelled) translatingAt.set(false) }
    }

    /**
     * 两轮查找：返回 (文字, 是否精确命中) 或 null。
     * pass A：包含点 → 取面积最小（最精确）
     * pass B：不包含点 → 取 bounds 到该点最近的（80dp 容错半径）
     */
    private fun findTextAtPoint(root: AccessibilityNodeInfo, x: Int, y: Int): Pair<String, Boolean>? {
        var bestExactText: String? = null
        var bestExactArea = Long.MAX_VALUE
        var bestNearText: String? = null
        var bestNearDist = Long.MAX_VALUE
        val rect = Rect()

        fun dfs(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return

            val text = node.text
            if (text != null && text.isNotBlank()) {
                node.getBoundsInScreen(rect)
                if (rect.contains(x, y)) {
                    val area = rect.width().toLong() * rect.height().toLong()
                    if (area < bestExactArea) {
                        bestExactArea = area
                        bestExactText = text.toString().trim()
                    }
                } else {
                    // 该点到矩形的最短距离
                    val dx = when {
                        x < rect.left -> rect.left - x
                        x > rect.right -> x - rect.right
                        else -> 0
                    }
                    val dy = when {
                        y < rect.top -> rect.top - y
                        y > rect.bottom -> y - rect.bottom
                        else -> 0
                    }
                    val dist = dx.toLong() * dx + dy.toLong() * dy
                    if (dist < bestNearDist) {
                        bestNearDist = dist
                        bestNearText = text.toString().trim()
                    }
                }
            }
            for (i in 0 until node.childCount) {
                dfs(node.getChild(i), depth + 1)
            }
        }
        dfs(root, 0)

        // 精确命中优先
        bestExactText?.takeIf { it.length >= 2 }?.let { return it to true }

        // 容错：80dp 半径内的最近文字节点
        val threshold = (80 * resources.displayMetrics.density).toLong()
        if (bestNearDist <= threshold * threshold) {
            bestNearText?.takeIf { it.length >= 2 }?.let { return it to false }
        }
        return null
    }

    // ============================ 模式4：框选翻译（v1.5.0） ============================

    /**
     * 框选翻译：收集屏幕坐标矩形 [region] 内的所有文字节点并翻译。
     *
     * 命中规则：节点 bounds 的中心点落在选区内即命中（比"完全包含"宽松，
     * 比"相交"干净——不会把只蹭到一条边的侧栏文字卷进来）。
     * 排序规则：按 top 升序、left 升序，还原阅读顺序后逐节点换行拼接。
     */
    /** 框选里的一行：文字 + 屏幕坐标（覆盖原文模式按它摆译文） */
    private data class RegionLine(val text: String, val box: Rect)

    /**
     * [cover] = true 时译文逐行盖在原文位置上（v1.29.2「覆盖原文」），否则照旧显示在面板里。
     */
    fun translateInRegion(region: Rect, cover: Boolean = false) {
        Log.i(TAG, "[框选] rect=$region cover=$cover")
        scope.launch {
            val root = activeRoot()
            if (root == null) {
                OverlayService.update("⚠️ 读不到当前界面", "请确认无障碍服务已开启")
                return@launch
            }

            val entries = mutableListOf<RegionLine>()
            val seen = HashSet<String>()  // 父子节点文字重复（WebView 常见）只取一份
            val r = Rect()

            fun dfs(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > MAX_DEPTH) return
                val t = node.text
                if (t != null && t.isNotBlank()) {
                    node.getBoundsInScreen(r)
                    val cx = (r.left + r.right) / 2
                    val cy = (r.top + r.bottom) / 2
                    if (region.contains(cx, cy) && seen.add(t.toString())) {
                        entries.add(RegionLine(t.toString().trim(), Rect(r)))
                    }
                }
                for (i in 0 until node.childCount) {
                    dfs(node.getChild(i), depth + 1)
                }
            }
            dfs(root, 0)

            val viaOcr = entries.isEmpty()
            val canShoot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            if (viaOcr && !canShoot) {
                if (cover) OverlayService.setCaptureHidden(false)
                OverlayService.update(
                    "📍 框选区域内没有文字",
                    "这个区域读不到文字节点，识别图片里的字需要 Android 11 及以上"
                )
                return@launch
            }
            // 覆盖模式要按原文底色选贴片明暗，所以即使读到了节点也截一帧来取样
            val shot = if ((viaOcr || cover) && canShoot) {
                // 先把自家悬浮球/面板藏起来，等一帧画面刷新后再截，免得截到自己
                OverlayService.setCaptureHidden(true)
                delay(CAPTURE_SETTLE_MS)
                captureScreen().also { OverlayService.setCaptureHidden(false, showPanel = !cover) }
            } else null

            try {
                val lines: List<RegionLine> = if (viaOcr) {
                    // v1.29.2：图片里的字、游戏/画布渲染的字没有文字节点 → 截屏裁出选区走本机 OCR
                    Log.d(TAG, "[框选] 选区内没有文字节点，改用截屏 OCR")
                    if (shot == null) {
                        if (cover) OverlayService.setCaptureHidden(false)
                        OverlayService.update("⚠️ 截屏失败", "系统没给出画面。若刚升级过 App，请到系统设置里把无障碍服务关掉再打开一次")
                        return@launch
                    }
                    ocrRegion(shot, region, cover) ?: return@launch
                } else {
                    entries.sortedWith(compareBy({ it.box.top }, { it.box.left }))
                }

                if (cover && OverlayService.coverBegin(lines.map { RegionCoverView.Line(it.box, isLightAt(shot, it.box)) })) {
                    coverTranslate(lines, if (viaOcr) "[框选·覆盖·识图]" else "[框选·覆盖]")
                    return@launch
                }

                // 面板模式（或覆盖层没开起来）：与原来一样整段翻译显示在面板里
                if (cover) OverlayService.setCaptureHidden(false)  // 覆盖模式下面板一直藏着，这里请回来
                val text = lines.joinToString("\n") { it.text }
                Log.i(TAG, "[框选] ${if (viaOcr) "OCR 识别" else "命中"} ${lines.size} 行、${text.length} 字符，开始翻译")
                translateAndShow(text, if (viaOcr) "[框选·识图]" else "[框选]")
            } finally {
                shot?.recycle()
            }
        }
    }

    /**
     * 框选的 OCR 兜底（v1.29.2）：从整屏截图 [shot] 裁出选区 → 本机 OCR，返回屏幕坐标的行；
     * 认不出字时自己报错并返回 null。
     *
     * 截屏用 [AccessibilityService.takeScreenshot]（Android 11+，需 canTakeScreenshot）而不是
     * MediaProjection：不弹录屏授权、不常驻，只在需要时截这一次，图片不出设备。
     */
    private suspend fun ocrRegion(shot: Bitmap, region: Rect, cover: Boolean): List<RegionLine>? {
        val l = region.left.coerceIn(0, shot.width - 1)
        val t = region.top.coerceIn(0, shot.height - 1)
        val r = region.right.coerceIn(l + 1, shot.width)
        val b = region.bottom.coerceIn(t + 1, shot.height)
        val crop = runCatching { Bitmap.createBitmap(shot, l, t, r - l, b - t) }.getOrNull()
        if (crop == null) {
            if (cover) OverlayService.setCaptureHidden(false)
            OverlayService.update("⚠️ 截屏失败", "选区超出了屏幕范围，请重新框选")
            return null
        }

        if (!cover) OverlayService.update("（正在识别图片里的文字…）", "正在翻译…")
        // 与图片翻译页同一套识别（源语言为自动时默认中文模型）
        val lines = try {
            OcrEngine.recognizeFor(crop, App.prefs.sourceLang, OcrEngine.Script.CHINESE)
        } finally {
            if (crop !== shot) crop.recycle()
        }
        if (lines.isEmpty()) {
            Log.d(TAG, "[框选] OCR 也没认出文字")
            if (cover) OverlayService.setCaptureHidden(false)
            OverlayService.update(
                "📍 框选区域内没有文字",
                "截屏识别也没认出文字\n\n试试框得更贴近文字，或在设置里把源语言设成图片上的语言"
            )
            return null
        }
        // OCR 的框是相对裁切图的，平移回屏幕坐标
        return lines.map { RegionLine(it.text.trim(), Rect(it.box).apply { offset(l, t) }) }
    }

    /**
     * 覆盖原文模式的翻译：先整段一次请求（带上下文、省请求），按行拆回；
     * 行数对不上（模型合并/拆分了行）就退回逐行翻译，保证每块译文盖在自己那一行上。
     */
    private suspend fun coverTranslate(lines: List<RegionLine>, mode: String) {
        val translator = TranslatorFactory.current()
        val target = App.prefs.targetLang
        // 一行内的换行压成空格，否则整段拆回时行数必然对不上
        val src = lines.map { it.text.replace('\n', ' ') }
        Log.i(TAG, "$mode ${lines.size} 行，开始翻译")

        val out = arrayOfNulls<String>(src.size)
        val whole = translator.translate(src.joinToString("\n"), target, App.prefs.sourceLang).getOrNull()
        val split = whole?.trim()?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (split != null && split.size == src.size) {
            split.forEachIndexed { i, s -> out[i] = s; OverlayService.coverChip(i, s) }
        } else {
            if (whole != null) Log.w(TAG, "$mode 行数不匹配（送出 ${src.size} 行，返回 ${split?.size} 行）→ 逐行翻译")
            val sem = Semaphore(COVER_PARALLEL)
            coroutineScope {
                src.indices.map { i ->
                    async {
                        sem.withPermit {
                            translator.translate(src[i], target, App.prefs.sourceLang).getOrNull()
                                ?.trim()?.takeIf { it.isNotEmpty() }
                                ?.let { out[i] = it; OverlayService.coverChip(i, it) }
                        }
                    }
                }.awaitAll()
            }
        }

        val ok = out.count { it != null }
        if (ok == 0) {
            OverlayService.coverClose()
            OverlayService.setCaptureHidden(false)
            OverlayService.update(src.joinToString("\n").take(300), "翻译失败：引擎没有返回结果，请检查网络或换个引擎")
            return
        }
        OverlayService.coverStatus(
            (if (ok < src.size) "已翻译 $ok/${src.size} 行" else "已翻译 ${src.size} 行") + " · 点一下关闭 · 按住看原文",
            autoHideMs = COVER_STATUS_MS
        )
        val translated = src.indices.filter { out[it] != null }
        runCatching {
            HistoryStore.add(
                source = translated.joinToString("\n") { src[it] },
                translated = translated.joinToString("\n") { out[it]!! },
                mode = mode,
                targetLang = target,
                engine = App.prefs.engine
            )
        }
        Log.i(TAG, "$mode 完成 $ok/${src.size} 行")
    }

    /** 截图里 [box] 一带是不是浅底（与图片翻译同一套取样：6×3 个点的平均亮度） */
    private fun isLightAt(shot: Bitmap?, box: Rect): Boolean {
        if (shot == null || shot.isRecycled) return false
        val l = box.left.coerceIn(0, shot.width - 1)
        val r = box.right.coerceIn(l + 1, shot.width)
        val t = box.top.coerceIn(0, shot.height - 1)
        val b = box.bottom.coerceIn(t + 1, shot.height)
        var sum = 0.0
        var n = 0
        for (i in 0 until 6) for (j in 0 until 3) {
            val c = shot.getPixel((l + (r - l) * i / 6).coerceIn(0, shot.width - 1), (t + (b - t) * j / 3).coerceIn(0, shot.height - 1))
            sum += (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) +
                0.114 * android.graphics.Color.blue(c)) / 255.0
            n++
        }
        return n > 0 && sum / n > 0.55
    }

    /** 无障碍截屏，转成可读写的软件位图；失败返回 null */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { cont ->
        runCatching {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val bmp = runCatching {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                // 硬件位图不能直接给 createBitmap 裁切 / OCR 读像素，拷一份软件位图
                                ?.let { hw -> hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() } }
                        }.getOrNull()
                        buffer.close()
                        if (cont.isActive) cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "[框选] 无障碍截屏失败 errorCode=$errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }.onFailure {
            Log.w(TAG, "[框选] 无障碍截屏异常: $it")
            if (cont.isActive) cont.resume(null)
        }
    }

    // ============================ 剪贴板监听（兜底） ============================

    private fun setupClipboardListener() {
        if (clipboard != null) return
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        // 修复：原来注册匿名 SAM、注销时又传另一个匿名 SAM 实例，二者 equals 永不相等，
        // 监听器永久驻留系统 ClipboardManager 并持有本 Service 引用 → Service 泄漏。
        // 现在提成字段，注册/注销用同一个实例。
        clipboard?.addPrimaryClipChangedListener(clipListener)
        Log.i(TAG, "剪贴板监听已注册")
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!App.prefs.clipboardTranslate) return@OnPrimaryClipChangedListener
        val cm = clipboard ?: return@OnPrimaryClipChangedListener
        // 注意：Android 10+ 起，后台应用（含无障碍服务）读取剪贴板会被系统限制，
        // primaryClip 通常返回 null。该功能因此可能无效，属平台限制而非缺陷。
        val clip: ClipData? = cm.primaryClip ?: return@OnPrimaryClipChangedListener
        // 面板「复制译文」写进来的，不再翻一遍
        if (clip?.description?.label == OverlayView.CLIP_LABEL) return@OnPrimaryClipChangedListener
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            ?: return@OnPrimaryClipChangedListener
        if (text.length < 2 || text == lastClipText) return@OnPrimaryClipChangedListener
        lastClipText = text
        // v1.29.0：剪贴板事件不带来源 App，用当前前台窗口代替
        if (!App.prefs.autoTranslateAllowedIn(rootInActiveWindow?.packageName?.toString())) {
            return@OnPrimaryClipChangedListener
        }
        if (App.prefs.skipSameLanguage && ScriptDetector.isAlreadyIn(text, App.prefs.targetLang)) {
            Log.d(TAG, "[剪贴板] 已是目标语言，跳过")
            return@OnPrimaryClipChangedListener
        }
        Log.i(TAG, "[剪贴板] ${text.length} 字符，开始翻译")
        scope.launch { translateAndShow(text, "[剪贴板]") }
    }

    // ============================ 公共翻译 ============================

    private suspend fun translateAndShow(text: String, mode: String, allowDictionary: Boolean = true) {
        // v1.29.0：选中的是单个单词时先查词，查到就不再翻译
        if (allowDictionary && WordLookup.tryShow(text, mode)) return
        OverlayService.update(text.take(300), "正在翻译…")
        // 每次取当前选择的引擎，切换引擎立即生效
        val translator = TranslatorFactory.current()
        val result = translator.translate(text, App.prefs.targetLang, App.prefs.sourceLang)
        var succeeded = false
        val translated = result.fold(
            onSuccess = { succeeded = true; it },
            onFailure = { e ->
                Log.e(TAG, "$mode 翻译失败", e)
                "翻译失败：${e.message ?: "未知错误"}"
            }
        )
        OverlayService.update(text.take(300), translated)
        // v1.8.0：只记录成功的翻译。这是全部 8 个翻译入口的唯一汇聚点，
        // 在这里落库即可覆盖无障碍/划词/定点/框选/输入/语音/视频/剪贴板。
        if (succeeded) {
            runCatching {
                HistoryStore.add(
                    source = text,
                    translated = translated,
                    mode = mode,
                    targetLang = App.prefs.targetLang,
                    engine = App.prefs.engine
                )
            }.onFailure { Log.w(TAG, "写入历史失败: $it") }
        }
        Log.i(TAG, "$mode 翻译完成（${translated.length} 字符）")
    }

    // ============================ 工具 ============================

    private fun activeRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        Log.w(TAG, "rootInActiveWindow 为 null，用 windows 兜底")
        return runCatching {
            windows.firstOrNull { w ->
                // 修复：原写法 `w.root?.packageName != packageName` 中 packageName 是
                // CharSequence?，与 String 用 != 比较时类型不等恒为 true —— 过滤完全失效，
                // 兜底路径可能选中自家悬浮窗所在窗口，读到"🌐 屏幕翻译"等自己的文本。
                // 必须显式 toString() 后再比较（同文件 121/148 行就是这个写法）。
                // 同时把 w.root 取出一次复用，避免重复走 Binder。
                val r = w.root
                r != null && w.isActive && r.packageName?.toString() != packageName
            }?.root
        }.getOrNull()
    }

    /** DFS 收集整棵树的文字 */
    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        node.text?.let { t ->
            val s = t.toString().trim()
            if (s.isNotEmpty()) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(s)
            }
        }
        node.contentDescription?.let { d ->
            if (node.text == null) {
                val s = d.toString().trim()
                if (s.isNotEmpty()) {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(s)
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, depth + 1)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "无障碍服务被解绑")
        // v1.29.0：区分"用户在设置里关掉"和"被强行停止"。强行停止时系统也可能先解绑、
        // 紧接着（毫秒级）杀进程；所以不立刻记账，等 3 秒进程还活着才算用户主动关闭。
        // 进程被杀，这条 Runnable 随之消失，自动恢复不受影响。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (instance == null) App.prefs.accessibilityUserDisabled = true
        }, USER_DISABLE_CONFIRM_MS)
        instance = null
        lastFullscreenText = null
        lastSelectionText = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clipboard?.let { cm ->
            // 用同一个 clipListener 实例注销（修复泄漏的关键）
            runCatching { cm.removePrimaryClipChangedListener(clipListener) }
        }
        clipboard = null
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenTranslator"
        private const val DEBOUNCE_MS = 600L
        private const val USER_DISABLE_CONFIRM_MS = 3_000L
        private const val SELECTION_DEBOUNCE_MS = 500L
        private const val MAX_DEPTH = 60

        /** 藏起悬浮球/面板后等多久再截屏（给系统一帧以上的刷新时间） */
        private const val CAPTURE_SETTLE_MS = 120L

        /** 覆盖原文模式退回逐行翻译时的并发上限（与图片翻译的逐行贴合同量级，避免被限流） */
        private const val COVER_PARALLEL = 3

        /** 覆盖层顶部「已翻译 N 行」提示停留多久 */
        private const val COVER_STATUS_MS = 3_000L

        @Volatile
        var instance: ScreenReaderService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
