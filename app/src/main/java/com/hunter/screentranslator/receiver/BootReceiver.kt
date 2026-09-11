package com.hunter.screentranslator.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import com.hunter.screentranslator.App

/**
 * 开机自检（v1.7.0）：部分国产 ROM 重启后会"忘记"无障碍服务。
 * 若用户曾开启过无障碍但现在没开，发一条通知提醒一键跳转重开，
 * 避免每次翻设置列表找半天。
 *
 * 说明：App 无法自行开启无障碍服务（系统安全限制），只能提醒+直达。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!App.prefs.accessibilityEverOn) return  // 从来没开过，不打扰
        if (isAccessibilityEnabled(context)) return  // 开机自动恢复了，不打扰

        notifyReEnable(context)
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    private fun notifyReEnable(context: Context) {
        val channelId = "boot_reminder"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "无障碍重开提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val openAccessibility = PendingIntent.getActivity(
            context, 0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openBattery = PendingIntent.getActivity(
            context, 1,
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("屏幕翻译需要重新开启无障碍服务")
            .setContentText("系统重启后无障碍被关闭了，点这里一键前往开启")
            .setContentIntent(openAccessibility)
            .addAction(0, "顺便设置忽略电池优化（防再掉）", openBattery)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    companion object {
        private const val NOTIF_ID = 2001
    }
}
