package com.hunter.screentranslator.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.hunter.screentranslator.App
import com.hunter.screentranslator.databinding.ActivityPermissionBinding
import com.hunter.screentranslator.service.OverlayService

/**
 * v1.12.0 三级页：权限与保活。
 *
 * 三项权限 + 各家 ROM 的白名单指引。这些是"出问题才来"的内容，
 * 但主页的状态卡片会在一项缺失时把人直接带到这里。
 */
class PermissionActivity : BaseActivity() {

    private lateinit var b: ActivityPermissionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }

        b.btnEnableAccessibility.setOnClickListener {
            // Android 13+ 侧载 APK 的无障碍开关是"受限设置"，需要先在应用详情里解锁
            if (Build.VERSION.SDK_INT >= 33 && !isAccessibilityEnabled()) {
                AlertDialog.Builder(this)
                    .setTitle("重要提示")
                    .setMessage(
                        "Android 13 及以上，通过 APK 安装的应用默认无法直接开启无障碍服务。\n\n" +
                                "如果稍后在无障碍页面看到「因安全原因，无法使用此应用」：\n\n" +
                                "请到 系统设置 → 应用 → 屏幕翻译 → 右上角 ⋮ 菜单 → " +
                                "「允许受限设置」，然后再回来开启无障碍。"
                    )
                    .setPositiveButton("知道了，去开启") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        b.btnEnableOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)
            ) {
                val uri = Uri.parse("package:$packageName")
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
            } else {
                OverlayService.start(this)
                toast("悬浮窗已启动")
            }
        }

        // v1.7.0：电池优化白名单（防 ROM 杀后台导致无障碍掉线）
        b.btnBatteryOptimize.setOnClickListener { requestIgnoreBatteryOptimizations() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val ok = resColor("md_success")
        val bad = themeColor(com.google.android.material.R.attr.colorError)
        val warn = resColor("md_warning")

        val enabled = isAccessibilityEnabled()
        b.tvAccessibilityStatus.text = if (enabled) "✅ 已开启" else "❌ 未开启"
        b.tvAccessibilityStatus.setTextColor(if (enabled) ok else bad)
        // 记住用户开启过无障碍（开机被 ROM 关掉时发提醒）
        if (enabled) App.prefs.accessibilityEverOn = true

        val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)
        b.tvOverlayStatus.text = if (overlayOk) "✅ 已授权" else "❌ 未授权"
        b.tvOverlayStatus.setTextColor(if (overlayOk) ok else bad)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        b.tvBatteryStatus.text =
            if (ignoring) "✅ 已忽略电池优化" else "⚠️ 未设置（服务可能被系统杀掉）"
        b.tvBatteryStatus.setTextColor(if (ignoring) ok else warn)
    }

}
