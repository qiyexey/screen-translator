package com.hunter.screentranslator.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.api.HyMtDeviceSupport
import com.hunter.screentranslator.api.HyMtRuntime
import com.hunter.screentranslator.api.LANG_DISPLAY
import com.hunter.screentranslator.api.SOURCE_AUTO
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.databinding.ActivityEngineSettingsBinding
import com.hunter.screentranslator.service.OverlayService
import com.hunter.screentranslator.util.CrashLog
import com.hunter.screentranslator.util.EdgeToEdge
import com.hunter.screentranslator.util.HyMtModelStatus
import com.hunter.screentranslator.util.HyMtModelStore
import com.hunter.screentranslator.util.HyMtProgress
import com.hunter.screentranslator.util.HyMtQuant
import com.hunter.screentranslator.util.HyMtSource
import com.hunter.screentranslator.util.SecretStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v1.12.0 三级页：翻译引擎 · 目标语言 · 密钥。
 *
 * 这一页承担了主页原来最肿的那张卡（890 行）。**11 家引擎的配置区仍全部保留在
 * 布局里**，但沿用原有的显隐机制一次只显示一组 —— 这样切引擎时输入框不会丢内容，
 * 也不必动态建 View。
 */
class EngineSettingsActivity : BaseActivity() {

    private lateinit var b: ActivityEngineSettingsBinding

    /** 线程数下拉的取值（0 = 自动）。显示文案与真实值分开，避免把"自动"塞进 Int */
    private val hyMtThreadValues = listOf(0, 2, 4, 6, 8)
    private val hyMtContextValues = listOf(1024, 2048, 4096)

    /** 导入自备 gguf。gguf 没有标准 MIME，只能放开任意类型（MIME 用通配符） */
    private val hyMtImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { doHyMtImport(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEngineSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.install(this)
        b.topAppBar.setNavigationOnClickListener { finish() }

        // ---- 回显所有引擎的已存配置 ----
        b.etApiKey.setText(App.prefs.apiKey)
        b.etBaseUrl.setText(App.prefs.baseUrl)
        b.etModel.setText(App.prefs.model)
        b.etGoogleKey.setText(App.prefs.googleApiKey)
        b.etMsKey.setText(App.prefs.msApiKey)
        b.etMsRegion.setText(App.prefs.msRegion)
        b.etDeeplKey.setText(App.prefs.deeplApiKey)
        b.etOpenAIKey.setText(App.prefs.openaiApiKey)
        b.etOpenAIBaseUrl.setText(App.prefs.openaiBaseUrl)
        b.etOpenAIModel.setText(App.prefs.openaiModel)
        b.etClaudeKey.setText(App.prefs.claudeApiKey)
        b.etClaudeModel.setText(App.prefs.claudeModel)
        b.etQwenKey.setText(App.prefs.qwenApiKey)
        b.etQwenModel.setText(App.prefs.qwenModel)
        b.etGLMKey.setText(App.prefs.glmApiKey)
        b.etGLMModel.setText(App.prefs.glmModel)
        b.etDoubaoKey.setText(App.prefs.doubaoApiKey)
        b.etDoubaoModel.setText(App.prefs.doubaoModel)
        b.etBaiduAppId.setText(App.prefs.baiduAppId)
        b.etBaiduKey.setText(App.prefs.baiduKey)
        b.etCaiyunToken.setText(App.prefs.caiyunToken)

        // ---- 引擎下拉 ----
        val engines = TranslationEngine.entries.toList()
        b.spinnerEngine.setSimpleItems(engines.map { it.displayName }.toTypedArray())
        val currentEngine = TranslationEngine.fromKey(App.prefs.engine)
        setSel(b.spinnerEngine, engines.indexOf(currentEngine))
        b.spinnerEngine.setOnItemClickListener { _, _, pos, _ ->
            applyEngineVisibility(engines[pos])
        }
        applyEngineVisibility(currentEngine)

        // ---- 源语言（v1.20.0）----
        // 列表 = "自动识别（推荐）" + LANG_DISPLAY 的 8 种语言，与 TtsSettingsActivity
        // 的「原文语言」下拉完全同构（那里也是 auto + 8 语言）。
        // 用 srcCodes 与 srcLabels 两个等长列表分离"存什么"与"显示什么" ——
        // 直接把 label 当值存，将来改文案就会把用户的旧设置读成无效值。
        val srcCodes = listOf(SOURCE_AUTO) + LANG_DISPLAY.keys.toList()
        val srcLabels = listOf(getString(R.string.engine_settings_t50)) +
            LANG_DISPLAY.map { "${it.value} (${it.key})" }
        b.spinnerSource.setSimpleItems(srcLabels.toTypedArray())
        setSel(b.spinnerSource, srcCodes.indexOf(App.prefs.sourceLang).coerceAtLeast(0))

        // ---- 目标语言 ----
        val langCodes = LANG_DISPLAY.keys.toList()
        b.spinnerTarget.setSimpleItems(langCodes.map { "${LANG_DISPLAY[it]} ($it)" }.toTypedArray())
        setSel(b.spinnerTarget, langCodes.indexOf(App.prefs.targetLang).coerceAtLeast(0))

        b.tvApiKeyHelp.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com")))
        }

        // ---- 只保存本页负责的字段（各子页各自保存）----
        b.btnSave.setOnClickListener {
            saveEngineConfigs()
            App.prefs.engine = engines[selPos(b.spinnerEngine)].key
            App.prefs.sourceLang = srcCodes[selPos(b.spinnerSource)]
            App.prefs.targetLang = langCodes[selPos(b.spinnerTarget)]
            toast(getString(R.string.common_t10))
        }

        b.btnTestTranslate.setOnClickListener { testTranslate() }

        // ---- v1.17.0 本地大模型区 ----
        setupHyMtUi()
    }

    // ================= v1.17.0 本地大模型（腾讯 Hy-MT2-1.8B）=================

    private fun setupHyMtUi() {
        b.spinnerHyMtQuant.setSimpleItems(HyMtQuant.entries.map { it.displayName }.toTypedArray())
        b.spinnerHyMtSource.setSimpleItems(HyMtSource.entries.map { it.displayName }.toTypedArray())
        b.spinnerHyMtThreads.setSimpleItems(hyMtThreadValues.map { if (it == 0) "自动（推荐）" else "$it" }.toTypedArray())
        b.spinnerHyMtContext.setSimpleItems(hyMtContextValues.map { if (it == 2048) "2048（推荐）" else "$it" }.toTypedArray())

        setSel(b.spinnerHyMtQuant, HyMtQuant.entries.indexOf(HyMtQuant.fromId(App.prefs.hymtQuant)).coerceAtLeast(0))
        setSel(b.spinnerHyMtSource, HyMtSource.entries.indexOf(HyMtSource.fromId(App.prefs.hymtSource)).coerceAtLeast(0))
        setSel(b.spinnerHyMtThreads, hyMtThreadValues.indexOf(App.prefs.hymtThreads).coerceAtLeast(0))
        setSel(b.spinnerHyMtContext, hyMtContextValues.indexOf(App.prefs.hymtContext).coerceAtLeast(0))

        // 换量化档立即落盘：下载按钮、模型状态、运行时加载都读这个值，
        // 只在「保存」时写会让用户点了下载却下到上一个档。
        b.spinnerHyMtQuant.setOnItemClickListener { _, _, pos, _ ->
            App.prefs.hymtQuant = HyMtQuant.entries[pos].id
                        refreshHyMtUi()
        }
        b.spinnerHyMtSource.setOnItemClickListener { _, _, pos, _ ->
            App.prefs.hymtSource = HyMtSource.entries[pos].id
        }
        b.spinnerHyMtThreads.setOnItemClickListener { _, _, pos, _ ->
            App.prefs.hymtThreads = hyMtThreadValues[pos]
        }
        b.spinnerHyMtContext.setOnItemClickListener { _, _, pos, _ ->
            App.prefs.hymtContext = hyMtContextValues[pos]
        }

        b.btnHyMtDownload.setOnClickListener { downloadHyMt() }
        b.btnHyMtImport.setOnClickListener { hyMtImportLauncher.launch(arrayOf("*/*")) }
        b.btnHyMtPreload.setOnClickListener { preloadHyMt() }
        b.btnHyMtUnload.setOnClickListener {
            lifecycleScope.launch {
                HyMtRuntime.unload()
                toast(getString(R.string.engine_settings_t41))
                refreshHyMtUi()
            }
        }
        b.btnHyMtDelete.setOnClickListener { confirmDeleteHyMt() }
        b.btnHyMtBench.setOnClickListener { runHyMtBench() }
        b.btnHyMtCopyDiag.setOnClickListener {
            val text = buildHyMtDiagnostics()
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("屏幕翻译诊断", text))
            toast(getString(R.string.engine_settings_t46))
        }

        // 下载在 App 级作用域里跑（退出本页不中断），所以这里的进度条要
        // 靠轮询文件状态来跟上 —— 重新进页面时也能自动接上。
        lifecycleScope.launch {
            while (isActive) {
                if (HyMtModelStore.activeDownload?.isActive == true) refreshHyMtUi()
                delay(1000)
            }
        }
        refreshHyMtUi()
    }

    /**
     * 诊断信息。
     *
     * 存在的理由很具体：本机没有设备控制授权时，开发者**装不了也点不了**这个 App，
     * 排查只能靠用户口述。把"守卫依据的指令集 / 线程与上下文 / 进程内存 / 上次延迟 /
     * 模型文件状态"一次打全并允许一键复制，用户粘贴一段就够了 —— 不用来回猜、也不用截屏。
     */
    private fun buildHyMtDiagnostics(): String {
        val q = currentHyMtQuant()
        val st = HyMtModelStore.status(q)
        val fileLine = when (st) {
            is HyMtModelStatus.Ready -> "已就绪 · ${fmtBytes(st.file.length())} · 大小与官方一致"
            is HyMtModelStatus.Partial -> "下载中 ${st.bytes * 100 / st.expected}%"
            is HyMtModelStatus.SizeMismatch -> "大小异常 ${fmtBytes(st.bytes)}（应为 ${fmtBytes(st.expected)}）"
            HyMtModelStatus.Missing -> "未下载"
        }
        return buildString {
            append("【屏幕翻译 v1.18.0 诊断】\n")
            append("设备：").append(HyMtDeviceSupport.summary).append('\n')
            HyMtDeviceSupport.reasonIfUnsupported?.let { append("守卫：").append(it).append('\n') }
            append("量化档：").append(q.displayName).append('\n')
            append("模型文件：").append(fileLine).append('\n')
            append("运行时：").append(HyMtRuntime.statusLine()).append('\n')
            HyMtRuntime.rssMb()?.let { append("进程内存：RSS ").append(it).append(" MB\n") }
            HyMtRuntime.lastLatencySummary()?.let { append(it).append('\n') }
            HyMtRuntime.lastBenchSummary?.let { append(it).append('\n') }
            append("模型目录占用：").append(fmtBytes(HyMtModelStore.usedBytes()))
            append(buildDiagExtras())
        }
    }

    /**
     * 诊断信息的"发布工程"补充段（v1.18.0）。
     *
     * 原来这段只讲本地大模型。但用户反馈里有一类问题与翻译质量无关，且恰好是
     * 本次密钥加密改造**引入的新失败模式**，必须能被诊断信息看见，否则只能靠猜：
     *
     * - **密钥存储是否降级成明文**：极少数定制 ROM 的 AndroidKeyStore 不可用，
     *   此时会退回明文写入。这是"如实说明"而不是静默失败 —— 用户应当知道自己
     *   的密钥当前没有加密。
     * - **本机有没有崩溃记录**：CrashLog 只写本地、不联网、不上报，
     *   所以必须由用户主动导出。没有这一段，那份记录永远不会被看到。
     */
    private fun buildDiagExtras(): String = buildString {
        append("\n\n【密钥与崩溃（v1.18.0）】\n")
        append("密钥存储：")
        if (SecretStore.unavailable) {
            append("⚠️ 已降级为明文（AndroidKeyStore 不可用）")
            SecretStore.lastError?.let { append(" — ").append(it) }
        } else {
            append("AndroidKeyStore AES-GCM 正常")
        }
        append('\n')
        append("本地崩溃记录：").append(CrashLog.count()).append(" 条（仅存本机，未上报）")
        CrashLog.recentReport()?.let { append("\n\n【最近一次崩溃】\n").append(it) }
    }

    /**
     * 5 句基准。
     *
     * 为什么要有这个按钮：开发机没有设备控制权限时，App 装没装、跑多快、发热如何
     * 都只能靠用户口述；而"一句两句话的体感"没有可比性。跑一组固定句子、
     * 把每句毫秒数写进诊断信息，用户粘一段就等价于替我做了一次基准测试。
     *
     * 注意首句包含模型加载（冷启动几秒），所以单独标出来、且不计入平均。
     */
    private fun runHyMtBench() {
        val samples = listOf(
            "Hello, how are you?",
            "Battery low. Please connect the charger.",
            "Are you sure you want to delete this file?",
            "昨日の会議は中止になりました。",
            "설정에서 알림을 끌 수 있습니다.",
        )
        b.btnHyMtBench.isEnabled = false
        lifecycleScope.launch {
            val outs = ArrayList<String>()
            val times = ArrayList<Long>()
            val translator = TranslatorFactory.current()
            samples.forEachIndexed { i, src ->
                b.btnHyMtBench.text = "基准中 ${i + 1}/${samples.size}…"
                val t0 = android.os.SystemClock.elapsedRealtime()
                val r = translator.translate(src, App.prefs.targetLang, App.prefs.sourceLang)
                val dt = android.os.SystemClock.elapsedRealtime() - t0
                val out = r.getOrNull()
                if (out != null) {
                    times.add(dt)
                    outs.add("• ${dt} ms${
                        if (i == 0) "（首句，含模型加载）" else ""
                    }｜$src → $out")
                } else {
                    outs.add("• 失败｜$src → ${r.exceptionOrNull()?.message}")
                }
            }
            b.btnHyMtBench.isEnabled = true
            b.btnHyMtBench.text = getString(R.string.engine_settings_btn_hy_mt_bench)

            val warm = times.drop(1)
            val avg = if (warm.isNotEmpty()) warm.sum() / warm.size else 0
            val summary = buildString {
                append("5 句基准：")
                if (times.isNotEmpty()) {
                    append("首句 ").append(times.first()).append(" ms")
                    if (warm.isNotEmpty()) {
                        append("，其余平均 ").append(avg).append(" ms")
                        append("（最快 ").append(warm.min()).append(" / 最慢 ").append(warm.max()).append("）")
                    }
                } else {
                    append("全部失败")
                }
            }
            HyMtRuntime.lastBenchSummary = summary
            refreshHyMtUi()
            AlertDialog.Builder(this@EngineSettingsActivity)
                .setTitle("📊 本地引擎基准")
                .setMessage(summary + "\n\n" + outs.joinToString("\n") +
                        "\n\n（结果已写入「复制诊断信息」）")
                .setPositiveButton("好的", null)
                .show()
        }
    }

    private fun currentHyMtQuant(): HyMtQuant =
        HyMtQuant.entries.getOrElse(selPos(b.spinnerHyMtQuant)) { HyMtQuant.Q4_K_M }

    private fun currentHyMtSource(): HyMtSource =
        HyMtSource.entries.getOrElse(selPos(b.spinnerHyMtSource)) { HyMtSource.MODELSCOPE }

    private fun refreshHyMtUi() {
        // 先过设备能力守卫：不支持就让整页不可操作并说明原因。
        // 让用户下完 1.13GB 再发现用不了，是这个流程里最糟的体验。
        HyMtDeviceSupport.reasonIfUnsupported?.let { why ->
            b.tvHyMtStatus.text = "⚠️ 本机不支持本地模型：$why"
            b.progressHyMt.visibility = View.GONE
            listOf(
                b.btnHyMtDownload, b.btnHyMtImport, b.btnHyMtPreload,
                b.btnHyMtUnload, b.btnHyMtDelete,
            ).forEach { it.isEnabled = false }
            return
        }
        val q = currentHyMtQuant()
        val st = HyMtModelStore.status(q)
        val downloading = HyMtModelStore.activeDownload?.isActive == true
        val bar = b.progressHyMt

        b.tvHyMtStatus.text = when (st) {
            is HyMtModelStatus.Ready -> buildString {
                append("模型状态：已就绪（${fmtBytes(st.file.length())}）")
                append("\n运行时：").append(HyMtRuntime.statusLine())
                // 实测延迟/内存由运行时回报 —— 用户装机后念这一行给我即可，不必截屏
                HyMtRuntime.lastLatencySummary()?.let { append("\n").append(it) }
                HyMtRuntime.rssMb()?.let { append("\n进程内存：RSS ").append(it).append(" MB") }
            }
            is HyMtModelStatus.Partial -> {
                val pct = (st.bytes * 100 / st.expected).toInt()
                bar.visibility = View.VISIBLE
                bar.progress = pct
                "模型状态：已下载 $pct%（${fmtBytes(st.bytes)} / ${fmtBytes(st.expected)}）—— ${
                    if (downloading) "正在下载…" else "点「继续下载」可断点续传"
                }"
            }
            is HyMtModelStatus.SizeMismatch ->
                "模型状态：文件大小不对（${fmtBytes(st.bytes)}，应为 ${fmtBytes(st.expected)}）。" +
                        "多半是上次没下完就退出了，建议「删除文件」后重下"
            HyMtModelStatus.Missing -> {
                bar.visibility = View.GONE
                "模型状态：未下载（需要 ${fmtBytes(q.sizeBytes)} 磁盘空间）\n" +
                        "模型目录已占用 ${fmtBytes(HyMtModelStore.usedBytes())}"
            }
        }
        if (st !is HyMtModelStatus.Partial) bar.visibility = View.GONE

        b.btnHyMtDownload.isEnabled = !downloading
        b.btnHyMtDownload.text = when {
            downloading -> "下载中…（可退出本页，后台继续）"
            st is HyMtModelStatus.Partial -> "继续下载（断点续传）"
            st is HyMtModelStatus.Ready -> "重新下载（会覆盖）"
            else -> "下载模型（${fmtBytes(q.sizeBytes)}）"
        }
        b.btnHyMtImport.isEnabled = !downloading
        b.btnHyMtPreload.isEnabled = st is HyMtModelStatus.Ready && !HyMtRuntime.isLoaded
        b.btnHyMtUnload.isEnabled = HyMtRuntime.isLoaded
        b.btnHyMtDelete.isEnabled = st !is HyMtModelStatus.Missing
    }

    private fun downloadHyMt() {
        val q = currentHyMtQuant()
        val src = currentHyMtSource()
        val free = App.appContext.filesDir.usableSpace
        if (free in 1 until q.sizeBytes) {
            toast("存储空间不足：需要 ${fmtBytes(q.sizeBytes)}，可用 ${fmtBytes(free)}")
            return
        }
        HyMtModelStore.startDownload(q, src, HyMtProgress { phase, done, total ->
            runOnUiThread {
                b.progressHyMt.visibility = View.VISIBLE
                if (phase == HyMtProgress.Phase.VERIFYING) {
                    b.progressHyMt.isIndeterminate = true
                    b.tvHyMtStatus.text = getString(R.string.engine_settings_t39)
                } else {
                    b.progressHyMt.isIndeterminate = false
                    b.progressHyMt.progress = if (total > 0) (done * 100 / total).toInt() else 0
                    b.tvHyMtStatus.text =
                        "下载中 ${fmtBytes(done)} / ${fmtBytes(total)}（${done * 100 / total.coerceAtLeast(1)}%）"
                }
            }
        }).invokeOnCompletion {
            // 注意：download() 把失败包进了 Result，所以 Job **永远是正常结束**，
            // 这里拿不到异常。成功与否只能回查文件状态 —— 否则下载失败也会弹
            // "模型已就绪"，用户点测试翻译才发现模型根本不在。
            runOnUiThread {
                b.progressHyMt.isIndeterminate = false
                val ok = HyMtModelStore.status(q) is HyMtModelStatus.Ready
                refreshHyMtUi()
                toast(
                    if (ok) "模型已就绪，可以点「测试翻译」验证"
                    else "下载未完成（网络中断？）—— 再点一次「继续下载」可断点续传"
                )
            }
        }
        refreshHyMtUi()
        toast("已开始下载（${src.displayName}），可退出本页")
    }

    private fun doHyMtImport(uri: Uri) {
        val q = currentHyMtQuant()
        toast(getString(R.string.engine_settings_t44))
        lifecycleScope.launch {
            val r = HyMtModelStore.importFrom(
                quant = q,
                open = { contentResolver.openInputStream(uri) },
            )
            r.fold(
                onSuccess = { f ->
                    val sizeOk = f.length() == q.sizeBytes
                    refreshHyMtUi()
                    AlertDialog.Builder(this@EngineSettingsActivity)
                        .setTitle("✅ 导入完成")
                        .setMessage(
                            "文件名：${f.name}\n大小：${fmtBytes(f.length())}" +
                                    if (!sizeOk) {
                                        "\n\n注意：大小与「${q.displayName}」官方文件不一致。" +
                                                "如果你导入的是别的量化档或自训模型，属正常；" +
                                                "但请在试译后用「测试翻译」确认能正常出译文。"
                                    } else ""
                        )
                        .setPositiveButton("好的", null)
                        .show()
                },
                onFailure = { e ->
                    refreshHyMtUi()
                    toast("导入失败：${e.message}")
                }
            )
        }
    }

    private fun preloadHyMt() {
        b.btnHyMtPreload.isEnabled = false
        b.tvHyMtStatus.text = getString(R.string.engine_settings_t43)
        lifecycleScope.launch {
            val r = HyMtRuntime.preload()
            r.fold(
                onSuccess = { toast(getString(R.string.engine_settings_t42)) },
                onFailure = { e -> toast("加载失败：${e.message}") }
            )
            refreshHyMtUi()
        }
    }

    private fun confirmDeleteHyMt() {
        val q = currentHyMtQuant()
        AlertDialog.Builder(this)
            .setTitle("删除模型文件？")
            .setMessage("将删除「${q.displayName}」的模型文件（${fmtBytes(q.sizeBytes)}）。下次要用需要重新下载。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    HyMtRuntime.unload()
                    HyMtModelStore.delete(q)
                    refreshHyMtUi()
                    toast(getString(R.string.engine_settings_t40))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format("%.0f MB", b / 1048576.0)
        else -> "${b / 1024} KB"
    }


    /** 根据所选引擎显示/隐藏对应配置区 */
    private fun applyEngineVisibility(engine: TranslationEngine) {
        val v = View.VISIBLE
        val g = View.GONE
        b.layoutDeepSeek.visibility = if (engine == TranslationEngine.DEEPSEEK) v else g
        b.layoutOpenAI.visibility = if (engine == TranslationEngine.OPENAI) v else g
        b.layoutClaude.visibility = if (engine == TranslationEngine.CLAUDE) v else g
        b.layoutQwen.visibility = if (engine == TranslationEngine.QWEN) v else g
        b.layoutGLM.visibility = if (engine == TranslationEngine.GLM) v else g
        b.layoutDoubao.visibility = if (engine == TranslationEngine.DOUBAO) v else g
        b.layoutGoogle.visibility = if (engine == TranslationEngine.GOOGLE) v else g
        b.layoutMicrosoft.visibility = if (engine == TranslationEngine.MICROSOFT) v else g
        b.layoutDeepl.visibility = if (engine == TranslationEngine.DEEPL) v else g
        b.layoutBaidu.visibility = if (engine == TranslationEngine.BAIDU) v else g
        b.layoutCaiyun.visibility = if (engine == TranslationEngine.CAIYUN) v else g
        b.layoutBingWeb.visibility = if (engine == TranslationEngine.BING_WEB) v else g
        b.layoutHyMtLocal.visibility = if (engine == TranslationEngine.HYMT_LOCAL) v else g
        // v1.22.0：必应网页的"免费 / 仅文字 / 随时可能失效"三段说明原本写在
        // displayName 里，把下拉框和首页胶囊撑爆了；现在名字只写「必应网页版」，
        // 这三段改由说明行承载 —— 它独占一行、宽度不受限，能写完整句子。
        b.tvEngineNote.visibility = if (engine == TranslationEngine.BING_WEB) v else g
        if (engine == TranslationEngine.HYMT_LOCAL) refreshHyMtUi()
    }

    /**
     * 把所有引擎的输入框内容写入偏好。
     *
     * 注意：ASR（语音识别）的三个字段**不在这里** —— 它们属于
     * [AsrSettingsActivity]。「各子页只写自己那一摊」是这次拆分的约定，
     * 避免某一页的保存把别的页正在编辑的内容一起覆盖掉。
     */
    private fun saveEngineConfigs() {
        App.prefs.apiKey = b.etApiKey.text.toString().trim()
        App.prefs.baseUrl = b.etBaseUrl.text.toString().trim()
            .ifBlank { "https://api.deepseek.com" }
        App.prefs.model = b.etModel.text.toString().trim()
            .ifBlank { "deepseek-chat" }
        App.prefs.googleApiKey = b.etGoogleKey.text.toString().trim()
        App.prefs.msApiKey = b.etMsKey.text.toString().trim()
        App.prefs.msRegion = b.etMsRegion.text.toString().trim()
        App.prefs.deeplApiKey = b.etDeeplKey.text.toString().trim()
        App.prefs.openaiApiKey = b.etOpenAIKey.text.toString().trim()
        App.prefs.openaiBaseUrl = b.etOpenAIBaseUrl.text.toString().trim()
            .ifBlank { "https://api.openai.com/v1" }
        App.prefs.openaiModel = b.etOpenAIModel.text.toString().trim()
            .ifBlank { "gpt-4o-mini" }
        App.prefs.claudeApiKey = b.etClaudeKey.text.toString().trim()
        App.prefs.claudeModel = b.etClaudeModel.text.toString().trim()
            .ifBlank { "claude-3-5-haiku-20241022" }
        App.prefs.qwenApiKey = b.etQwenKey.text.toString().trim()
        App.prefs.qwenModel = b.etQwenModel.text.toString().trim()
            .ifBlank { "qwen-plus" }
        App.prefs.glmApiKey = b.etGLMKey.text.toString().trim()
        App.prefs.glmModel = b.etGLMModel.text.toString().trim()
            .ifBlank { "glm-4-flash" }
        App.prefs.doubaoApiKey = b.etDoubaoKey.text.toString().trim()
        App.prefs.doubaoModel = b.etDoubaoModel.text.toString().trim()
        App.prefs.baiduAppId = b.etBaiduAppId.text.toString().trim()
        App.prefs.baiduKey = b.etBaiduKey.text.toString().trim()
        App.prefs.caiyunToken = b.etCaiyunToken.text.toString().trim()
        // v1.17.0 本地模型：这些值在 spinner 切换时已即时落盘（下载/加载都依赖它们），
        // 这里再写一次是为了让「保存」按钮的语义保持一致（本页的所有字段都随保存落盘）。
        App.prefs.hymtQuant = currentHyMtQuant().id
        App.prefs.hymtSource = currentHyMtSource().id
        App.prefs.hymtThreads = hyMtThreadValues.getOrElse(selPos(b.spinnerHyMtThreads)) { 0 }
        App.prefs.hymtContext = hyMtContextValues.getOrElse(selPos(b.spinnerHyMtContext)) { 2048 }
    }

    /** 真实调用一次当前引擎的 API 验证配置 */
    private fun testTranslate() {
        // 先落盘当前输入框内容（含所有引擎），否则测的是旧 Key
        saveEngineConfigs()

        val engine = TranslationEngine.fromKey(App.prefs.engine)
        val engineName = engine.displayName
        val readiness = App.prefs.readiness(engine)
        if (readiness is com.hunter.screentranslator.api.EngineReadiness.NotReady) {
            toast(readiness.reason)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast(getString(R.string.engine_settings_t47))
        }

        b.btnTestTranslate.isEnabled = false
        b.btnTestTranslate.text = getString(R.string.engine_settings_t45)

        lifecycleScope.launch {
            // v1.20.0：这句测试文案是英文，所以源语言**刻意传 auto 而不是 App.prefs.sourceLang**。
            // 用户若把源语言设成"日语"，用它去测英文样例会得到一个明显错误的结果，
            // 从而误判"这个引擎坏了"。测试翻译的目的是验证密钥/网络连通性，
            // 就该用与源语言无关的固定输入。
            val result = TranslatorFactory.current(useCache = false).translate(
                "Hello! This is a translation test.",
                App.prefs.targetLang,
                SOURCE_AUTO
            )
            b.btnTestTranslate.isEnabled = true
            b.btnTestTranslate.text = getString(R.string.engine_settings_btn_test_translate)

            result.fold(
                onSuccess = { translated ->
                    OverlayService.update("Hello! This is a translation test.", translated)
                    // 本地引擎没有"密钥/网络"，有意义的是耗时；云端引擎保持原文案
                    val extra = if (engine == TranslationEngine.HYMT_LOCAL) {
                        HyMtRuntime.lastLatencySummary()?.let { "$it\n（首次翻译包含模型加载时间）" }
                            ?: "本地推理完成。"
                    } else if (engine == TranslationEngine.BING_WEB) {
                        "必应网页端可访问，本次没有使用缓存。"
                    } else {
                        "密钥和网络均正常。"
                    }
                    AlertDialog.Builder(this@EngineSettingsActivity)
                        .setTitle("✅ 测试成功（$engineName）")
                        .setMessage("翻译结果：$translated\n\n$extra")
                        .setPositiveButton("好的", null)
                        .show()
                },
                onFailure = { e ->
                    val guidance = if (engine == TranslationEngine.BING_WEB) {
                        "必应网页版无需密钥。请检查网络连接；若持续失败，网页接口可能已变更。"
                    } else {
                        "请检查：\n1. 密钥是否正确\n2. 账户额度/余额\n3. 手机能否访问该服务"
                    }
                    AlertDialog.Builder(this@EngineSettingsActivity)
                        .setTitle("❌ 测试失败（$engineName）")
                        .setMessage(
                            "${e.message}\n\n$guidance"
                        )
                        .setPositiveButton("好的", null)
                        .show()
                }
            )
        }
    }
}
