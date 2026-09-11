package com.hunter.screentranslator.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hunter.screentranslator.App
import com.hunter.screentranslator.service.OverlayService

/**
 * v1.12.0 设置页拆分后，各页共用的工具方法。
 *
 * 拆分前这些都挤在 MainActivity 里（当时的 MainActivity 有 668 行，同时管着
 * 权限状态、引擎密钥、朗读、悬浮球外观、语音识别五件互不相干的事）。抽到基类
 * 避免每个页面各抄一份，也保证"判定无障碍是否开启"这类逻辑只有一处实现。
 */
abstract class BaseActivity : AppCompatActivity() {

    protected fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 本应用的无障碍服务当前是否在运行 */
    protected fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    /** 权限齐全时自动拉起悬浮窗服务（双保险：无障碍服务连接时也会拉起） */
    protected fun maybeStartOverlay() {
        if (!App.prefs.overlayEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        runCatching { OverlayService.start(this) }
    }

    /**
     * 从当前主题解析语义色。
     *
     * 状态文字不能写死颜色：夜间模式下亮绿配深底、纯红配深底都不合 M3 规范，
     * 而本次 M3 改造的目的就是"颜色走角色"。优先按 M3 属性解析，解析不到
     * （如自定义的 md_success 不是 M3 标准属性）时退回按颜色资源名取色。
     */
    protected fun themeColor(attrRes: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attrRes, tv, true)
        return if (tv.resourceId != 0) {
            ContextCompat.getColor(this, tv.resourceId)
        } else {
            tv.data
        }
    }

    /**
     * 按资源名取色（用于 md_success 这类非 M3 标准属性的自定义角色）。
     * 取不到时回退到默认值 —— getIdentifier 返回 0 时直接 getColor 会抛
     * Resources.NotFoundException 把 App 打崩，这里必须兜住。
     */
    protected fun resColor(name: String, fallback: Int = 0xFF006D3B.toInt()): Int {
        val id = resources.getIdentifier(name, "color", packageName)
        return if (id != 0) ContextCompat.getColor(this, id) else fallback
    }

    /**
     * 一键申请忽略电池优化（防 ROM 杀后台导致无障碍掉线）。
     * 不支持直接弹窗的 ROM 退回列表页；两者都失败则如实告知，不假装成功。
     */
    protected fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已设置忽略电池优化 ✅")
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { toast("你的 ROM 不支持自动跳转，请到电池设置里手动添加") }
        }
    }

    protected fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
