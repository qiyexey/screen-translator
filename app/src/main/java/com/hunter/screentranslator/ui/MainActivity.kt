package com.hunter.screentranslator.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.api.LANG_DISPLAY
import com.hunter.screentranslator.api.SOURCE_AUTO
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.databinding.ActivityMainBinding
import com.hunter.screentranslator.util.EdgeToEdge

/**
 * v1.12.0 主页瘦身。
 *
 * 这一页回答三个问题：**「翻什么语言」**、**「用什么方式」**、
 * **「现在能不能用」**。语言对、当前引擎、功能入口和关键权限状态都是高频上下文，
 * 必须直接可见；只有 API Key、模型参数、外观细节等低频表单留在专属设置页。
 *
 * 改造前这一页是 1423 行布局、74 个控件，其中「翻译引擎与密钥」一张卡就 890 行；
 * v1.12.0 又矫枉过正，把几乎所有东西都藏进设置。v1.21.0 的边界是：
 * **高频选择前移，低频表单分组，不在主页复制复杂配置。**
 */
class MainActivity : BaseActivity() {

    private lateinit var b: ActivityMainBinding

    /**
     * 语言对那一行（v1.20.0 引入，v1.20.1 改成可下拉）。
     *
     * ## 为什么最终放回主页这一行
     *
     * v1.20.0 我把语言下拉放进了「设置 → 翻译引擎与语言」，理由是"不想破坏
     * Google 翻译那种一行扁平文字的观感"。**这个判断错了** —— 用户的第一反应
     * 就是"自动检测 ⇄ 中文 这里应该能点"，放到三级设置页里等于藏起来了。
     * 语言对是最常用的设置之一，它就该在最显眼的位置。
     *
     * ## 怎么保住观感
     *
     * MaterialAutoCompleteTextView **必须套 TextInputLayout**：它内部需要
     * `findTextInputLayoutAncestor()` 来定位下拉弹窗；裸放时列表不会出现。
     * 外层使用 `DropdownLayout.Borderless`，保留 FilledBox 模式和下拉箭头，
     * 只把背景/描边设为透明（不能用 boxBackgroundMode=none，见 themes.xml）。
     *
     * ## 两份列表必须同源
     *
     * 候选列表由 [srcCodes] / [tgtCodes] 与 [LANG_DISPLAY] **派生**，
     * 不在布局里用 app:simpleItems 写死 —— 否则 XML 数组和 Kotlin 映射表
     * 会变成两份要手工同步的列表，顺序一旦错位，
     * "显示的语言名"与"实际下发的语言代码"就对不上了。
     */
    private fun refreshLangRow() {
        val src = App.prefs.sourceLang
        b.tvSrcLang.setText(
            if (src == SOURCE_AUTO) getString(R.string.main_tv_src_lang)
            else LANG_DISPLAY[src] ?: src,
            false            // false = 不触发过滤，必须传，否则会把候选列表筛成只剩当前项
        )
        b.tvTargetLang.setText(LANG_DISPLAY[App.prefs.targetLang] ?: App.prefs.targetLang, false)
    }

    /**
     * 主页语言下拉的候选列表（v1.20.1）。
     *
     * 源语言多一项"自动检测"（放在首位），目标语言不能是 auto ——
     * "自动识别"没有意义，说不出要把文字翻译成什么。
     */
    private val srcCodes: List<String> by lazy { listOf(SOURCE_AUTO) + LANG_DISPLAY.keys.toList() }

    private fun srcLabels(): Array<String> =
        (listOf(getString(R.string.main_tv_src_lang)) +
                LANG_DISPLAY.values.toList()).toTypedArray()

    private val tgtCodes: List<String> by lazy { LANG_DISPLAY.keys.toList() }

    private fun tgtLabels(): Array<String> = LANG_DISPLAY.values.toTypedArray()

    /**
     * 把两个下拉的"当前选中项"同步成 prefs 里的值（v1.20.1）。
     *
     * 注意 setSel 只移动高亮位置、不改文字；文字由 [refreshLangRow] 负责。
     * 两者必须一起调用 —— 漏掉任何一个都会出现"文字对了但下拉高亮错了"
     * （或反之），而这种不一致只有用户点开下拉时才会发现。
     */
    private fun syncLangSpinners() {
        setSel(b.tvSrcLang, srcCodes.indexOf(App.prefs.sourceLang).coerceAtLeast(0))
        setSel(b.tvTargetLang, tgtCodes.indexOf(App.prefs.targetLang).coerceAtLeast(0))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.install(this)

        // ---- 主要功能入口 ----
        b.btnInputTranslate.setOnClickListener {
            startActivity(Intent(this, TranslateInputActivity::class.java))
        }
        b.btnVoiceTranslate.setOnClickListener {
            startActivity(Intent(this, VoiceTranslateActivity::class.java))
        }
        b.btnImageTranslate.setOnClickListener {
            startActivity(Intent(this, ImageTranslateActivity::class.java))
        }
        b.btnCameraTranslate.setOnClickListener {
            startActivity(Intent(this, CameraTranslateActivity::class.java))
        }
        b.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        // v1.15.0：实时屏幕翻译。与「图片翻译」的区别是"常驻闭环"而不是"抓一帧"——
        // 适合模拟器/游戏这类画面一直在变、但文字反复出现的场景。
        b.btnLiveTranslate.setOnClickListener {
            startActivity(Intent(this, LiveTranslateActivity::class.java))
        }

        // v1.21.0：引擎是翻译行为的核心上下文，不应只能从“设置索引 → 引擎”
        // 两层菜单进入。首页给一个当前值入口，点击直接进配置页。
        b.btnEngineQuick.setOnClickListener {
            startActivity(Intent(this, EngineSettingsActivity::class.java))
        }

        // v1.15.24：语言对那一行（对齐 Google 翻译的标志性布局）
        //
        // v1.20.0：这一行**真正生效**了。
        // 改之前：tvSrcLang 从来没被赋值过（一直显示静态文案"自动检测"），
        // 交换键点下去只是跳进设置页 —— 那一行是纯装饰。
        // 现在源/目标两端都读真实偏好，交换键真的交换，
        // 且同步落盘 + 刷新。用户在主界面直接调整语言对，不必进设置。
        //
        // v1.20.1：两端进一步改成**可下拉**。此前只能在设置页改，
        // 而这一行看起来就像个能点的地方 —— 反馈就是"那里应该能选语言"。
        b.tvSrcLang.setSimpleItems(srcLabels())
        setSel(b.tvSrcLang, srcCodes.indexOf(App.prefs.sourceLang).coerceAtLeast(0))

        b.tvTargetLang.setSimpleItems(tgtLabels())
        setSel(b.tvTargetLang, tgtCodes.indexOf(App.prefs.targetLang).coerceAtLeast(0))

        b.tvSrcLang.setOnItemClickListener { _, _, pos, _ ->
            App.prefs.sourceLang = srcCodes[pos]
            // 源语言改了就刷新一下显示：选了具体语言后要立刻看到名字，
            // 而不是停留在"自动检测"。（setText 的 false 见 refreshLangRow 注释）
            refreshLangRow()
        }
        b.tvTargetLang.setOnItemClickListener { _, _, pos, _ ->
            App.prefs.targetLang = tgtCodes[pos]
            refreshLangRow()
        }

        refreshLangRow()

        b.btnSwapLang.setOnClickListener {
            // 交换键的语义就是"互换"。当目标语言是 auto 时不成立 ——
            // "自动识别"不能当目标语言用，所以先把 auto 换成"英语"这类
            // 具体语言再换，否则会存下一个翻译不出结果的组合。
            val src = App.prefs.sourceLang
            val tgt = App.prefs.targetLang
            App.prefs.sourceLang = tgt
            App.prefs.targetLang = if (src == SOURCE_AUTO) FALLBACK_LANG else src
            // v1.20.1：两个下拉的"当前选中项"也要跟着走。
            // 只改文字不改选中位置的话，下一次点开下拉高亮的仍是交换前那一项，
            // 用户会以为交换没生效。
            syncLangSpinners()
            refreshLangRow()
        }

        // ---- 设置与权限 ----
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.btnPermission.setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }

        // v1.13.0：首次启动自动进引导。
        // 只出现一次 —— 跳过与走完都会置位 onboardingDone；想再看走
        // 设置 → 关于与用法 →「重新查看引导」。
        if (!App.prefs.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // Android 13+ 请求通知权限（前台服务通知 + 开机重开提醒需要）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        // 回到前台时若权限齐全，确保悬浮窗服务在跑
        maybeStartOverlay()

        // v1.20.1：设置页里也能改语言（「翻译引擎与语言」那两个下拉）。
        // 从那里改完返回主页时，这里必须重新同步一次，
        // 否则主页还显示旧的 —— 同一个设置有两个入口，就得有两个出口都刷新。
        if (::b.isInitialized) {
            syncLangSpinners()
            refreshLangRow()
            refreshEngineQuickEntry()
        }
    }

    /**
     * 首页引擎快捷入口显示“当前引擎 · 切换或配置”。
     *
     * 不把引擎下拉直接复制到主页：不同引擎的 Key、Base URL、模型字段差异很大，
     * 强行展开只会把首页重新做成旧版 1423 行巨页。主页只显示上下文和入口，
     * 复杂字段仍留在专属页面，这是“前移高频选择，不复制低频表单”的边界。
     */
    private fun refreshEngineQuickEntry() {
        val engine = TranslationEngine.fromKey(App.prefs.engine)
        b.btnEngineQuick.text = getString(
            R.string.main_engine_value,
            "${engine.displayName}  ·  ${getString(R.string.main_engine_action)}"
        )
    }

    /** 只显示两个最关键的权限状态：一眼看出这 App 现在能不能干活 */
    private fun updateStatus() {
        val ok = resColor(R.color.md_success)
        val bad = themeColor(com.google.android.material.R.attr.colorError)

        val enabled = isAccessibilityEnabled()
        b.tvMainAccessStatus.text = if (enabled) "✅ 已开启" else "❌ 未开启"
        b.tvMainAccessStatus.setTextColor(if (enabled) ok else bad)
        // 记住用户开启过无障碍（开机被 ROM 关掉时发提醒）
        if (enabled) App.prefs.accessibilityEverOn = true

        val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)
        b.tvMainOverlayStatus.text = if (overlayOk) "✅ 已授权" else "❌ 未授权"
        b.tvMainOverlayStatus.setTextColor(if (overlayOk) ok else bad)
    }

    private companion object {
        /**
         * 交换语言时"自动识别"的替身。
         *
         * 目标语言不能是 auto —— 没有"翻译成自动识别"这回事。
         * 源语言是 auto 而用户点了交换时，把目标语言落成英语：
         * 这是绝大多数用户的中转语言，且所有 13 个引擎都支持，
         * 不会出现"换一下语言对就报不支持该语言"的情况。
         */
        const val FALLBACK_LANG = "en"
    }
}
