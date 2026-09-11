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
import com.hunter.screentranslator.databinding.ActivityMainBinding

/**
 * v1.12.0 主页瘦身。
 *
 * 这一页只回答两个问题：**「用什么」**（六个功能入口）和**「能不能用」**（权限状态）。
 * 所有配置都移到了设置里：
 *   设置 → 翻译引擎与语言 / 翻译触发方式 / 朗读 / 语音识别 / 悬浮球外观 / 权限与保活
 *
 * 改造前这一页是 1423 行布局、74 个控件，其中「翻译引擎与密钥」一张卡就 890 行
 * —— 四类使用频率差一个数量级的东西（选功能 / 配密钥 / 调外观 / 修权限）挤在一起。
 */
class MainActivity : BaseActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // ---- 六个功能入口 ----
        b.btnInputTranslate.setOnClickListener {
            startActivity(Intent(this, TranslateInputActivity::class.java))
        }
        b.btnVoiceTranslate.setOnClickListener {
            startActivity(Intent(this, VoiceTranslateActivity::class.java))
        }
        b.btnVideoListen.setOnClickListener {
            startActivity(Intent(this, VideoListenActivity::class.java))
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
    }

    /** 只显示两个最关键的权限状态：一眼看出这 App 现在能不能干活 */
    private fun updateStatus() {
        val ok = resColor("md_success")
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
}
