package com.hunter.screentranslator.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.HyMtDeviceSupport
import com.hunter.screentranslator.api.HyMtRuntime
import com.hunter.screentranslator.api.LANG_DISPLAY
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.databinding.ActivityEngineSettingsBinding
import com.hunter.screentranslator.service.OverlayService
import com.hunter.screentranslator.util.HyMtModelStatus
import com.hunter.screentranslator.util.HyMtModelStore
import com.hunter.screentranslator.util.HyMtProgress
import com.hunter.screentranslator.util.HyMtQuant
import com.hunter.screentranslator.util.HyMtSource
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
        b.btnBack.setOnClickListener { finish() }

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
        b.spinnerEngine.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            engines.map { it.displayName }
        )
        val currentEngine = TranslationEngine.fromKey(App.prefs.engine)
        b.spinnerEngine.setSelection(engines.indexOf(currentEngine))
        b.spinnerEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applyEngineVisibility(engines[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        applyEngineVisibility(currentEngine)

        // ---- 目标语言 ----
        val langCodes = LANG_DISPLAY.keys.toList()
        b.spinnerTarget.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            langCodes.map { "${LANG_DISPLAY[it]} ($it)" }
        )
        b.spinnerTarget.setSelection(langCodes.indexOf(App.prefs.targetLang).coerceAtLeast(0))

        b.tvApiKeyHelp.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com")))
        }

        // ---- 只保存本页负责的字段（各子页各自保存）----
        b.btnSave.setOnClickListener {
            saveEngineConfigs()
            App.prefs.engine = engines[b.spinnerEngine.selectedItemPosition].key
            App.prefs.targetLang = langCodes[b.spinnerTarget.selectedItemPosition]
            toast("已保存")
        }

        b.btnTestTranslate.setOnClickListener { testTranslate() }

        // ---- v1.17.0 本地大模型区 ----
        setupHyMtUi()
    }

    // ================= v1.17.0 本地大模型（腾讯 Hy-MT2-1.8B）=================

    private fun setupHyMtUi() {
        b.spinnerHyMtQuant.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            HyMtQuant.entries.map { it.displayName }
        )
        b.spinnerHyMtSource.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            HyMtSource.entries.map { it.displayName }
        )
        b.spinnerHyMtThreads.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            hyMtThreadValues.map { if (it == 0) "自动（推荐）" else "$it" }
        )
        b.spinnerHyMtContext.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            hyMtContextValues.map { if (it == 2048) "2048（推荐）" else "$it" }
        )

        b.spinnerHyMtQuant.setSelection(
            HyMtQuant.entries.indexOf(HyMtQuant.fromId(App.prefs.hymtQuant)).coerceAtLeast(0)
        )
        b.spinnerHyMtSource.setSelection(
            HyMtSource.entries.indexOf(HyMtSource.fromId(App.prefs.hymtSource)).coerceAtLeast(0)
        )
        b.spinnerHyMtThreads.setSelection(
            hyMtThreadValues.indexOf(App.prefs.hymtThreads).coerceAtLeast(0)
        )
        b.spinnerHyMtContext.setSelection(
            hyMtContextValues.indexOf(App.prefs.hymtContext).coerceAtLeast(0)
        )

        // 换量化档立即落盘：下载按钮、模型状态、运行时加载都读这个值，
        // 只在「保存」时写会让用户点了下载却下到上一个档。
        b.spinnerHyMtQuant.onItemSelectedListener = simpleListener { pos ->
            App.prefs.hymtQuant = HyMtQuant.entries[pos].id
            refreshHyMtUi()
        }
        b.spinnerHyMtSource.onItemSelectedListener = simpleListener { pos ->
            App.prefs.hymtSource = HyMtSource.entries[pos].id
        }
        b.spinnerHyMtThreads.onItemSelectedListener = simpleListener { pos ->
            App.prefs.hymtThreads = hyMtThreadValues[pos]
        }
        b.spinnerHyMtContext.onItemSelectedListener = simpleListener { pos ->
            App.prefs.hymtContext = hyMtContextValues[pos]
        }

        b.btnHyMtDownload.setOnClickListener { downloadHyMt() }
        b.btnHyMtImport.setOnClickListener { hyMtImportLauncher.launch(arrayOf("*/*")) }
        b.btnHyMtPreload.setOnClickListener { preloadHyMt() }
        b.btnHyMtUnload.setOnClickListener {
            lifecycleScope.launch {
                HyMtRuntime.unload()
                toast("已卸载模型，内存已释放")
                refreshHyMtUi()
            }
        }
        b.btnHyMtDelete.setOnClickListener { confirmDeleteHyMt() }

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

    private fun simpleListener(onSelected: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelected(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun currentHyMtQuant(): HyMtQuant =
        HyMtQuant.entries.getOrElse(b.spinnerHyMtQuant.selectedItemPosition) { HyMtQuant.Q4_K_M }

    private fun currentHyMtSource(): HyMtSource =
        HyMtSource.entries.getOrElse(b.spinnerHyMtSource.selectedItemPosition) { HyMtSource.MODELSCOPE }

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
                // 实测延迟由运行时回报 —— 用户装机后可以直接念这一行给我，不必截屏
                HyMtRuntime.lastLatencySummary()?.let { append("\n").append(it) }
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
                    b.tvHyMtStatus.text = "下载完成，正在校验 sha256（1GB 约十几秒）…"
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
        toast("正在导入…")
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
        b.tvHyMtStatus.text = "正在加载模型到内存…（首次约几秒）"
        lifecycleScope.launch {
            val r = HyMtRuntime.preload()
            r.fold(
                onSuccess = { toast("模型已加载，可以开始翻译了") },
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
                    toast("已删除")
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
        App.prefs.hymtThreads = hyMtThreadValues.getOrElse(b.spinnerHyMtThreads.selectedItemPosition) { 0 }
        App.prefs.hymtContext = hyMtContextValues.getOrElse(b.spinnerHyMtContext.selectedItemPosition) { 2048 }
    }

    /** 真实调用一次当前引擎的 API 验证配置 */
    private fun testTranslate() {
        // 先落盘当前输入框内容（含所有引擎），否则测的是旧 Key
        saveEngineConfigs()

        val engine = TranslationEngine.fromKey(App.prefs.engine)
        val engineName = engine.displayName
        val keyReady = when (engine) {
            TranslationEngine.DEEPSEEK -> App.prefs.apiKey.isNotBlank()
            TranslationEngine.OPENAI -> App.prefs.openaiApiKey.isNotBlank()
            TranslationEngine.CLAUDE -> App.prefs.claudeApiKey.isNotBlank()
            TranslationEngine.QWEN -> App.prefs.qwenApiKey.isNotBlank()
            TranslationEngine.GLM -> App.prefs.glmApiKey.isNotBlank()
            TranslationEngine.DOUBAO ->
                App.prefs.doubaoApiKey.isNotBlank() && App.prefs.doubaoModel.isNotBlank()
            TranslationEngine.GOOGLE -> App.prefs.googleApiKey.isNotBlank()
            TranslationEngine.MICROSOFT -> App.prefs.msApiKey.isNotBlank()
            TranslationEngine.DEEPL -> App.prefs.deeplApiKey.isNotBlank()
            TranslationEngine.BAIDU ->
                App.prefs.baiduAppId.isNotBlank() && App.prefs.baiduKey.isNotBlank()
            TranslationEngine.CAIYUN -> App.prefs.caiyunToken.isNotBlank()
            // 免密钥引擎：永远算"已配置"
            TranslationEngine.BING_WEB -> true
            // 本地引擎没有密钥，但必须有模型文件 —— 没下完就点测试只会得到
            // 一句"模型没加载"，不如在这里直接说清下一步做什么。
            TranslationEngine.HYMT_LOCAL ->
                HyMtModelStore.status(currentHyMtQuant()) is HyMtModelStatus.Ready
        }
        if (!keyReady) {
            toast(
                if (engine == TranslationEngine.HYMT_LOCAL) "请先下载模型文件（下方「下载模型」）"
                else "请先填写 $engineName 的密钥"
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限（测试结果会显示在悬浮窗）")
        }

        b.btnTestTranslate.isEnabled = false
        b.btnTestTranslate.text = "测试中…"

        lifecycleScope.launch {
            val result = TranslatorFactory.current().translate(
                "Hello! This is a translation test.",
                App.prefs.targetLang
            )
            b.btnTestTranslate.isEnabled = true
            b.btnTestTranslate.text = "测试翻译（真调 API 验证 Key）"

            result.fold(
                onSuccess = { translated ->
                    OverlayService.update("Hello! This is a translation test.", translated)
                    // 本地引擎没有"密钥/网络"，有意义的是耗时；云端引擎保持原文案
                    val extra = if (engine == TranslationEngine.HYMT_LOCAL) {
                        HyMtRuntime.lastLatencySummary()?.let { "$it\n（首次翻译包含模型加载时间）" }
                            ?: "本地推理完成。"
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
                    AlertDialog.Builder(this@EngineSettingsActivity)
                        .setTitle("❌ 测试失败（$engineName）")
                        .setMessage(
                            "${e.message}\n\n请检查：\n1. 密钥是否正确\n2. 账户额度/余额\n" +
                                    "3. 手机能否访问该服务"
                        )
                        .setPositiveButton("好的", null)
                        .show()
                }
            )
        }
    }
}
