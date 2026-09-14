package com.hunter.screentranslator.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.TranslationEngine

/**
 * v1.13.0 首次引导。
 *
 * **设计取向：这不是"看一遍的介绍"，而是"把权限开好的向导"。**
 * 这个 App 不给无障碍 + 悬浮窗就完全不能用，所以每一步都带一个能直接跳去开权限的按钮，
 * 并在回到本页时**实时回报当前状态**（已开 / 未开）—— 用户不必自己判断"我到底开好了没"。
 *
 * **只自动出现一次**：跳过与走完都会置 `App.prefs.onboardingDone`。
 * 想再看可走 设置 → 关于与用法 →「重新查看引导」。
 */
class OnboardingActivity : BaseActivity() {

    /** 每步要实时检测的状态类型 */
    private enum class Status { NONE, ACCESSIBILITY, OVERLAY, BATTERY, ENGINE_KEY }

    private class Step(
        val emoji: String,
        val title: String,
        val body: String,
        val status: Status = Status.NONE,
        val actionLabel: String? = null,
        val onAction: ((OnboardingActivity) -> Unit)? = null
    )

    private lateinit var tvStep: TextView
    private lateinit var tvEmoji: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvBody: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnAction: MaterialButton
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton

    private var index = 0
    private lateinit var steps: List<Step>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        steps = buildSteps()
        setContentView(buildUi())
        render()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置回来后刷新状态，让用户立刻看到"开好了没有"
        if (::tvStatus.isInitialized) renderStatus()
    }

    // ==================== 步骤定义 ====================

    private fun buildSteps(): List<Step> = listOf(
        Step(
            emoji = "🌐",
            title = "欢迎使用屏幕翻译",
            body = "读取屏幕上的外文，用 AI 翻译给你看。\n\n" +
                    "六种用法：\n" +
                    "⚽ 悬浮球拖到文字上松手\n" +
                    "🔲 双击悬浮球框选整块区域\n" +
                    "⌨️ 输入框里打字\n" +
                    "🎤 对着手机说话\n" +
                    "🖼 截屏后选区域\n" +
                    "📷 拍照：点哪一行就译哪一行\n\n" +
                    "下面用几步把权限开好，之后就能用了。"
        ),
        Step(
            emoji = "👁",
            title = "① 开启无障碍服务",
            body = "这是核心权限 —— 只有它才能读取屏幕上显示的文字，其他翻译方式都建立在它之上。\n\n" +
                    "路径：系统设置 → 无障碍 → 已安装的服务 → 屏幕翻译 → 打开\n\n" +
                    "Android 13 及以上，侧载安装的 App 可能提示「因安全原因无法使用」，" +
                    "需要先到 系统设置 → 应用 → 屏幕翻译 → 右上角 ⋮ → 「允许受限设置」。",
            status = Status.ACCESSIBILITY,
            actionLabel = "去开启无障碍",
            onAction = { act -> act.openAccessibilitySettings() }
        ),
        Step(
            emoji = "⚽",
            title = "② 授予悬浮窗权限",
            body = "悬浮球和翻译结果面板都是以「悬浮窗」形式显示在别的应用上面的。\n\n" +
                    "没有这个权限，翻译照样能跑，但你**看不到任何结果**。",
            status = Status.OVERLAY,
            actionLabel = "去授权悬浮窗",
            onAction = { act ->
                act.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + act.packageName)
                    )
                )
            }
        ),
        Step(
            emoji = "🔋",
            title = "③ 防止服务被杀（推荐）",
            body = "各家 ROM 会在后台清理服务，导致无障碍掉线、悬浮球消失、翻译时好时坏。\n\n" +
                    "把它加入电池优化白名单能显著减少掉线。国产 ROM 往往还需要手动加白名单，" +
                    "具体路径见 设置 → 权限与保活。",
            status = Status.BATTERY,
            actionLabel = "设置忽略电池优化",
            onAction = { act -> act.requestIgnoreBatteryOptimizations() }
        ),
        Step(
            emoji = "🔑",
            title = "④ 配置翻译引擎",
            body = "需要填一家的密钥才能翻译。国内可直连、且带免费额度的：\n\n" +
                    "· 智谱 GLM-4-Flash —— 免费调用\n" +
                    "· 百度翻译 —— 每月 5 万字符\n" +
                    "· 彩云小译 —— 新用户 100 万字\n\n" +
                    "也可以填任意 OpenAI 兼容的中转地址。",
            status = Status.ENGINE_KEY,
            actionLabel = "去设置引擎",
            onAction = { act ->
                act.startActivity(Intent(act, EngineSettingsActivity::class.java))
            }
        ),
        Step(
            emoji = "🎉",
            title = "⑤ 开始使用",
            body = "悬浮球的手势：\n\n" +
                    "⚽ 拖动 → 拖到文字上松手，翻译该处\n" +
                    "👆 单击 → 收起 / 展开结果面板\n" +
                    "🔲 双击 → 框选整块区域翻译\n" +
                    "⌨️ 长按 → 进入输入翻译\n" +
                    "📷 三击 → 拍照翻译（点哪行译哪行）\n\n" +
                    "其余设置都在主页的「⚙️ 设置」里。"
        )
    )

    // ==================== 渲染 ====================

    private fun render() {
        val s = steps[index]
        tvStep.text = "第 ${index + 1} / ${steps.size} 步"
        tvEmoji.text = s.emoji
        tvTitle.text = s.title
        tvBody.text = s.body
        if (s.actionLabel != null && s.onAction != null) {
            btnAction.visibility = View.VISIBLE
            btnAction.text = s.actionLabel
            btnAction.setOnClickListener { s.onAction.invoke(this) }
        } else {
            btnAction.visibility = View.GONE
        }
        btnPrev.isEnabled = index > 0
        btnPrev.alpha = if (index > 0) 1f else 0.4f
        btnNext.text = if (index == steps.size - 1) "开始使用" else "下一步 ›"
        renderStatus()
    }

    private fun renderStatus() {
        val ok = resColor("md_success")
        val bad = themeColor(com.google.android.material.R.attr.colorError)
        val warn = resColor("md_warning")

        when (steps[index].status) {
            Status.ACCESSIBILITY -> {
                val on = isAccessibilityEnabled()
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = if (on) "✅ 无障碍服务已开启" else "❌ 尚未开启"
                tvStatus.setTextColor(if (on) ok else bad)
                if (on) App.prefs.accessibilityEverOn = true
            }
            Status.OVERLAY -> {
                val on = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        Settings.canDrawOverlays(this)
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = if (on) "✅ 悬浮窗权限已授权" else "❌ 尚未授权"
                tvStatus.setTextColor(if (on) ok else bad)
            }
            Status.BATTERY -> {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                val on = pm.isIgnoringBatteryOptimizations(packageName)
                tvStatus.visibility = View.VISIBLE
                tvStatus.text =
                    if (on) "✅ 已忽略电池优化" else "⚠️ 未设置（可选，但强烈建议）"
                tvStatus.setTextColor(if (on) ok else warn)
            }
            Status.ENGINE_KEY -> {
                val filled = anyEngineKeyFilled()
                tvStatus.visibility = View.VISIBLE
                tvStatus.text =
                    if (filled) "✅ 已配置「${TranslationEngine.fromKey(App.prefs.engine).displayName}」"
                    else "❌ 还没有任何引擎配置了密钥"
                tvStatus.setTextColor(if (filled) ok else bad)
            }
            Status.NONE -> tvStatus.visibility = View.GONE
        }
    }

    private fun anyEngineKeyFilled(): Boolean = when (TranslationEngine.fromKey(App.prefs.engine)) {
        TranslationEngine.DEEPSEEK -> App.prefs.apiKey.isNotBlank()
        TranslationEngine.OPENAI -> App.prefs.openaiApiKey.isNotBlank()
        TranslationEngine.CLAUDE -> App.prefs.claudeApiKey.isNotBlank()
        TranslationEngine.QWEN -> App.prefs.qwenApiKey.isNotBlank()
        TranslationEngine.GLM -> App.prefs.glmApiKey.isNotBlank()
        TranslationEngine.DOUBAO -> App.prefs.doubaoApiKey.isNotBlank()
        TranslationEngine.GOOGLE -> App.prefs.googleApiKey.isNotBlank()
        TranslationEngine.MICROSOFT -> App.prefs.msApiKey.isNotBlank()
        TranslationEngine.DEEPL -> App.prefs.deeplApiKey.isNotBlank()
        TranslationEngine.BAIDU -> App.prefs.baiduAppId.isNotBlank()
        TranslationEngine.CAIYUN -> App.prefs.caiyunToken.isNotBlank()
        // 免密钥引擎：引导页里也应算"已可翻译"，否则用户选了它还会被提示去填密钥
        TranslationEngine.BING_WEB -> true
    }

    /** Android 13+ 侧载 APK 的无障碍开关是"受限设置"，先讲清怎么解锁再去 */
    private fun openAccessibilitySettings() {
        val go = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (Build.VERSION.SDK_INT >= 33 && !isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("可能需要在应用详情里解锁")
                .setMessage(
                    "Android 13 及以上，通过 APK 安装的应用默认无法直接开启无障碍。\n\n" +
                            "如果无障碍页面显示「因安全原因，无法使用此应用」：\n\n" +
                            "请先到 系统设置 → 应用 → 屏幕翻译 → 右上角 ⋮ 菜单 → " +
                            "「允许受限设置」，再回来开启。"
                )
                .setPositiveButton("知道了，去开启") { _, _ -> go() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            go()
        }
    }

    private fun goNext() {
        if (index < steps.size - 1) {
            index++
            render()
        } else {
            complete()
        }
    }

    /** 跳过与走完都置位：引导只自动出现一次，不反复打扰 */
    private fun complete() {
        App.prefs.onboardingDone = true
        finish()
    }

    // ==================== UI ====================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            setBackgroundColor(resColor("bg_primary", 0xFFFFFFFF.toInt()))
        }

        tvStep = TextView(this).apply {
            textSize = 12f
            setTextColor(resColor("text_secondary", 0xFF757575.toInt()))
        }
        root.addView(tvStep)

        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(10) }
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(resColor("bg_card", 0xFFFFFFFF.toInt()))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        tvEmoji = TextView(this).apply { textSize = 40f }
        inner.addView(tvEmoji)

        tvTitle = TextView(this).apply {
            textSize = 20f
            setTextColor(resColor("text_primary", 0xFF212121.toInt()))
            setPadding(0, dp(10), 0, 0)
        }
        inner.addView(tvTitle)

        tvBody = TextView(this).apply {
            textSize = 14f
            setTextColor(resColor("text_secondary", 0xFF616161.toInt()))
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(12), 0, 0)
        }
        inner.addView(tvBody)

        tvStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(14), 0, 0)
            visibility = View.GONE
        }
        inner.addView(tvStatus)

        btnAction = MaterialButton(this).apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        inner.addView(btnAction)

        scroll.addView(inner)
        card.addView(scroll)
        root.addView(card)

        // ---- 底部操作栏 ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        val btnSkip = MaterialButton(this).apply {
            text = "跳过"
            textSize = 13f
            setOnClickListener { complete() }
        }
        bar.addView(btnSkip)

        bar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        btnPrev = MaterialButton(this).apply {
            text = "‹ 上一步"
            textSize = 13f
            setOnClickListener {
                if (index > 0) {
                    index--
                    render()
                }
            }
        }
        bar.addView(btnPrev)

        btnNext = MaterialButton(this).apply {
            textSize = 13f
            setOnClickListener { goNext() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        bar.addView(btnNext)

        root.addView(bar)
        return root
    }
}
