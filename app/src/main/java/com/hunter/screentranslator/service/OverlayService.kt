package com.hunter.screentranslator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.hunter.screentranslator.App
import com.hunter.screentranslator.overlay.OverlayBallView
import com.hunter.screentranslator.overlay.OverlayView
import com.hunter.screentranslator.overlay.RegionSelectView
import com.hunter.screentranslator.util.Speaker

/**
 * 悬浮窗服务：前台保活 + 管理翻译面板（OverlayView）、悬浮球（OverlayBallView）
 * 和框选遮罩（RegionSelectView）。
 * v1.1.0 悬浮球：拖动松手 → ScreenReaderService.translateAt(球心坐标)
 * v1.5.0 双击悬浮球进入框选翻译：RegionSelectView 拖出矩形 → translateInRegion(rect)
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: OverlayView? = null
    private var ballView: OverlayBallView? = null
    private var regionSelectView: RegionSelectView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        startForegroundCompat()
        addPanel()
        if (App.prefs.floatingBall) addBall()
        Log.i(TAG, "悬浮窗服务已启动（悬浮球: ${App.prefs.floatingBall}）")
    }

    private fun addPanel() {
        if (overlayView != null) return
        runCatching {
            overlayView = OverlayView(this).also { v ->
                v.attachToWindow(windowManager)
                // 静默设计：面板初始隐藏，有翻译结果才出现（25 秒后自动隐藏）
                // 平时屏幕上只有悬浮球，不会挂着旧内容
                v.visibility = View.GONE
            }
        }.onFailure { Log.e(TAG, "翻译面板添加失败: $it") }
    }

    private fun addBall() {
        if (ballView != null) return
        runCatching {
            ballView = OverlayBallView(
                this,
                onDrop = { x, y ->
                    Log.d(TAG, "悬浮球落在 ($x, $y)")
                    // 确保面板可见再翻译（带自动隐藏计时）
                    overlayView?.showWithAutoHide()
                    val svc = ScreenReaderService.instance
                    if (svc == null) {
                        update("⚠️ 无障碍服务未开启", "请到 App 里开启无障碍服务后再用悬浮球")
                    } else {
                        svc.translateAt(x, y)
                    }
                },
                onBallClick = {
                    // 单击球：切换翻译面板显隐（显示时带 25 秒自动隐藏）
                    overlayView?.toggleVisible()
                },
                onBallDoubleClick = {
                    // 双击球：进入框选翻译模式
                    startRegionSelect()
                },
                onBallLongPress = {
                    // 长按球（v1.6.0）：打开输入翻译界面
                    runCatching {
                        startActivity(
                            android.content.Intent(this, com.hunter.screentranslator.ui.TranslateInputActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                onBallTripleClick = {
                    // 三击球（v1.8.0）：打开图片翻译（内部会按需申请截图授权）
                    runCatching {
                        startActivity(
                            android.content.Intent(this, com.hunter.screentranslator.ui.ImageTranslateActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { Log.e(TAG, "打开图片翻译失败: $it") }
                }
            ).also { it.attachToWindow(windowManager) }
        }.onFailure { Log.e(TAG, "悬浮球添加失败: $it") }
    }

    private fun removeBall() {
        ballView?.detachFromWindow(windowManager)
        ballView = null
    }

    // ============================ 框选翻译（v1.5.0） ============================

    /** 双击悬浮球进入：全屏遮罩，拖出矩形翻译，单击取消 */
    private fun startRegionSelect() {
        if (regionSelectView != null) return
        runCatching {
            // 先收起面板，避免面板挡住要框选的内容
            overlayView?.visibility = View.GONE
            regionSelectView = RegionSelectView(
                this,
                onRegionSelected = { rect -> onRegionPicked(rect) },
                onDismiss = { endRegionSelect() }
            ).also { it.attachToWindow(windowManager) }
            Log.d(TAG, "进入框选模式")
        }.onFailure {
            Log.e(TAG, "框选遮罩添加失败: $it")
            regionSelectView = null
        }
    }

    private fun onRegionPicked(rect: Rect) {
        endRegionSelect()
        overlayView?.showWithAutoHide()
        val svc = ScreenReaderService.instance
        if (svc == null) {
            update("⚠️ 无障碍服务未开启", "请到 App 里开启无障碍服务后再用框选翻译")
        } else {
            svc.translateInRegion(rect)
        }
    }

    private fun endRegionSelect() {
        regionSelectView?.detachFromWindow(windowManager)
        regionSelectView = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> overlayView?.visibility = View.GONE
            ACTION_SHOW -> overlayView?.visibility = View.VISIBLE
            ACTION_BALL_SHOW -> addBall()
            ACTION_BALL_HIDE -> removeBall()
            ACTION_BALL_STYLE -> {
                // v1.5.0 悬浮球样式 + v1.5.1 面板透明度，一起刷新
                ballView?.updateStyle()
                overlayView?.applyPanelStyle()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // v1.8.0：服务销毁时停掉朗读，避免译文读一半服务没了
        runCatching { Speaker.stop() }
        endRegionSelect()
        removeBall()
        overlayView?.detachFromWindow(windowManager)
        overlayView = null
        instance = null
        Log.w(TAG, "悬浮窗服务已销毁")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val channelId = "overlay_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "翻译悬浮窗", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持翻译悬浮窗运行" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("屏幕翻译运行中")
            .setContentText("悬浮窗翻译已开启")
            .setSmallIcon(android.R.drawable.ic_menu_more)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "ScreenTranslator"
        private const val NOTIF_ID = 1001
        const val ACTION_HIDE = "com.hunter.screentranslator.HIDE"
        const val ACTION_SHOW = "com.hunter.screentranslator.SHOW"
        const val ACTION_STOP = "com.hunter.screentranslator.STOP"
        const val ACTION_BALL_SHOW = "com.hunter.screentranslator.BALL_SHOW"
        const val ACTION_BALL_HIDE = "com.hunter.screentranslator.BALL_HIDE"
        const val ACTION_BALL_STYLE = "com.hunter.screentranslator.BALL_STYLE"

        @Volatile
        private var instance: OverlayService? = null

        /** 更新翻译面板内容 */
        fun update(source: String, translated: String) {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "OverlayService 未运行，结果被丢弃")
                return
            }
            svc.overlayView?.post {
                svc.overlayView?.updateContent(source, translated)
            } ?: Log.w(TAG, "翻译面板不存在")
        }

        fun start(ctx: android.content.Context) {
            val intent = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /** 切换悬浮球显示状态（配置保存时调用） */
        fun setBallEnabled(ctx: android.content.Context, enabled: Boolean) {
            val action = if (enabled) ACTION_BALL_SHOW else ACTION_BALL_HIDE
            val intent = Intent(ctx, OverlayService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /** 悬浮球样式 / 面板透明度变化后刷新（v1.5.0，v1.5.1 扩展到面板） */
        fun refreshBallStyle(ctx: android.content.Context) {
            val intent = Intent(ctx, OverlayService::class.java).setAction(ACTION_BALL_STYLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: android.content.Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
