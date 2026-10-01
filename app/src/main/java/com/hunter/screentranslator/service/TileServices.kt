package com.hunter.screentranslator.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.accessibility.AccessibilityManager
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R

/**
 * 快捷设置磁贴（v1.29.0）：下拉通知栏一键开关，不用打开 App。
 *
 * 两块磁贴共用 [BaseToggleTile]：每次下拉通知栏（onStartListening）按偏好刷新状态，
 * 点一下（onClick）切换偏好并即时生效。缺权限时不假装切换成功，而是直接跳去开权限。
 */
abstract class BaseToggleTile : TileService() {

    /** 当前是否"开着" */
    protected abstract fun isOn(): Boolean

    /** 切换；返回 false 表示没切成（缺权限，已经跳去开权限了） */
    protected abstract fun toggle(): Boolean

    /** 磁贴副标题（Android 10+ 显示在名字下面）；null 用默认 */
    protected open fun subtitle(): String? = null

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        // 锁屏状态下先解锁：这两个开关会让 App 开始读屏，不该在锁屏上就能打开
        if (isLocked) unlockAndRun { if (toggle()) refresh() } else if (toggle()) refresh()
    }

    protected fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (isOn()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle()
        tile.updateTile()
    }

    /** 从磁贴拉起一个界面并收起通知栏（Android 14 起必须走 PendingIntent 版本） */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    protected fun openAndCollapse(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    protected fun canDrawOverlays() = Settings.canDrawOverlays(this)

    protected fun accessibilityOn(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
    }
}

/** 磁贴「悬浮球」：开关悬浮球（同 设置 → 翻译触发方式 → 悬浮球） */
class BallTileService : BaseToggleTile() {

    override fun isOn() = App.prefs.floatingBall && canDrawOverlays()

    override fun subtitle(): String? =
        if (!canDrawOverlays()) getString(R.string.tile_need_overlay) else null

    override fun toggle(): Boolean {
        if (!canDrawOverlays()) {
            openAndCollapse(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return false
        }
        val on = !App.prefs.floatingBall
        App.prefs.floatingBall = on
        // 悬浮窗服务可能还没起来（App 被杀过），先确保它在，再增删悬浮球
        runCatching { if (on) OverlayService.start(this) }
        runCatching { OverlayService.setBallEnabled(this, on) }
        return true
    }
}

/**
 * 磁贴「全屏翻译」：开关全屏自动翻译。
 *
 * 全屏翻译依赖无障碍服务和译文面板：打开时顺带把「显示翻译面板」也打开，
 * 否则开了也看不到译文；无障碍没开时如实在副标题里写出来，并跳去无障碍设置。
 */
class FullscreenTileService : BaseToggleTile() {

    override fun isOn() = App.prefs.autoTranslate && App.prefs.overlayEnabled

    override fun subtitle(): String? =
        if (!accessibilityOn(this)) getString(R.string.tile_need_accessibility) else null

    override fun toggle(): Boolean {
        val on = !isOn()
        App.prefs.autoTranslate = on
        if (on) App.prefs.overlayEnabled = true
        if (on && !accessibilityOn(this)) {
            openAndCollapse(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        return true
    }
}
