package com.hunter.screentranslator.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.hunter.screentranslator.App
import com.hunter.screentranslator.service.ScreenReaderService

/**
 * 自动恢复无障碍服务（v1.29.0）。
 *
 * 问题：App 被「强行停止」（或被 ROM 的一键清理当成强行停止）时，系统会把它的
 * 无障碍服务从 `enabled_accessibility_services` 里**删掉**，于是每次都得进设置重开。
 * App 本身没权限改这个列表 —— 除非用户用 adb 授予一次 WRITE_SECURE_SETTINGS：
 *
 *     adb shell pm grant com.hunter.screentranslator android.permission.WRITE_SECURE_SETTINGS
 *
 * 授予后，每次进程启动（打开 App / 开机）都检查一遍：曾经开过、现在不在列表里、
 * 且不是用户自己关的 → 把本服务写回列表，系统随即重新绑定。
 *
 * 不违背用户意愿：用户在系统设置里手动关掉时，进程还活着，会走到
 * [ScreenReaderService.onUnbind]，那里记下 accessibilityUserDisabled，这里就不再恢复；
 * 用户下次自己开启后（onServiceConnected）标记清除。
 */
object AccessibilityRestore {

    private const val TAG = "ScreenTranslator"

    const val ADB_GRANT_COMMAND =
        "adb shell pm grant com.hunter.screentranslator android.permission.WRITE_SECURE_SETTINGS"

    /** 用户是否已经用 adb 授权 */
    fun canRestore(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * 需要时把无障碍服务写回系统设置。返回 true 表示这次确实恢复了。
     * 任何异常都吞掉：这是锦上添花，失败了也不能影响 App 启动。
     */
    fun restoreIfNeeded(ctx: Context): Boolean = runCatching {
        val prefs = App.prefs
        if (!prefs.accessibilityEverOn || prefs.accessibilityUserDisabled) return false
        if (!canRestore(ctx)) return false

        val me = ComponentName(ctx, ScreenReaderService::class.java)
        val resolver = ctx.contentResolver
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }
        val alreadyOn = entries.any { ComponentName.unflattenFromString(it) == me }
        val masterOn = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (alreadyOn && masterOn) return false

        if (!alreadyOn) {
            val updated = (entries + me.flattenToString()).joinToString(":")
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
        }
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Log.i(TAG, "无障碍服务已被系统关闭，已自动恢复")
        true
    }.getOrElse {
        Log.w(TAG, "自动恢复无障碍失败", it)
        false
    }
}
