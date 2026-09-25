package com.hunter.screentranslator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.api.WhisperClient
import com.hunter.screentranslator.overlay.SubtitleOverlayView
import com.hunter.screentranslator.util.WavUtils
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 听视频翻译服务（v1.6.0）：
 * 采集设备音频（内录 Android 10+ / 麦克风）→ 静音切句 → Whisper 转写 → 翻译 → 悬浮字幕。
 *
 * 内录模式：MediaProjection + AudioPlaybackCapture，直接取系统播放的混音，安静准确；
 * 麦克风模式：手机外放视频时用麦克风收音，任何设备可用。
 */
class VideoListenService : Service() {

    private lateinit var windowManager: WindowManager
    private var subtitleView: SubtitleOverlayView? = null
    private var audioRecord: AudioRecord? = null
    private var projection: MediaProjection? = null
    private var captureThread: Thread? = null

    @Volatile private var running = false
    private var internalMode = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 转写+翻译串行锁：保证字幕按音频顺序更新，不乱序 */
    private val processMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (running) return START_STICKY
        internalMode = intent?.getBooleanExtra(EXTRA_INTERNAL, true) ?: true

        startForegroundCompat()

        // 通知点停止 / 字幕条 ✕ → 停服务
        addSubtitle()

        if (internalMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            @Suppress("DEPRECATION")
            val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            if (data == null) {
                subtitleView?.setStatus("未获得屏幕录制授权，已停止")
                stopSelf()
                return START_NOT_STICKY
            }
            runCatching {
                projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                    .getMediaProjection(resultCode, data)
                    ?.also { p ->
                        p.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() {
                                Log.w(TAG, "MediaProjection 被系统停止")
                                stopSelf()
                            }
                        }, null)
                    }
            }.onFailure {
                Log.e(TAG, "MediaProjection 创建失败: $it")
                subtitleView?.setStatus("音频采集授权失败：$it")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        running = true
        startCapture()
        Log.i(TAG, "听视频翻译启动（模式=${if (internalMode) "内录" else "麦克风"}）")
        return START_NOT_STICKY
    }

    private fun addSubtitle() {
        if (subtitleView != null) return
        runCatching {
            subtitleView = SubtitleOverlayView(this, onClose = { stopSelf() }).also {
                it.attachToWindow(windowManager)
                it.setStatus(if (internalMode) "内录模式 · 启动中…" else "麦克风模式 · 启动中…")
            }
        }.onFailure { Log.e(TAG, "字幕条添加失败: $it") }
    }

    // ============================ 音频采集 ============================

    private fun startCapture() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2)  // 至少 1 秒

        val builder = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)

        val useInternal = internalMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (useInternal) {
            val p = projection ?: return
            builder.setAudioPlaybackCaptureConfig(
                AudioPlaybackCaptureConfiguration.Builder(p)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
            )
        } else {
            builder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }

        try {
            audioRecord = builder.build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord 创建失败: $e")
            subtitleView?.setStatus("音频采集创建失败：${e.message}")
            stopSelf()
            return
        }

        val segmenter = AudioSegmenter(SAMPLE_RATE) { pcm -> scope.launch { processSegment(pcm) } }

        captureThread = Thread {
            val record = audioRecord ?: return@Thread
            val chunk = ByteArray(SAMPLE_RATE / 10 * 2)  // 100ms 一块
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n > 0) segmenter.feed(chunk, n)
                    else if (n < 0) {
                        Log.e(TAG, "AudioRecord read 错误 $n，停止采集")
                        break
                    }
                }
                segmenter.flush()
            } catch (e: Exception) {
                Log.e(TAG, "采集线程异常: $e")
            }
        }.apply { start() }
    }

    // ============================ 转写 + 翻译 ============================

    private suspend fun processSegment(pcm: ByteArray) {
        processMutex.withLock {
            if (!running) return@withLock
            val view = subtitleView ?: return@withLock

            val whisper = WhisperClient(App.prefs.asrBaseUrl, App.prefs.asrApiKey, App.prefs.asrModel)
            view.setStatus("正在转写…")

            val wav = withContext(Dispatchers.Default) { WavUtils.pcmToWav(pcm, SAMPLE_RATE, 1) }
            val text = whisper.transcribe(wav).getOrElse { e ->
                Log.e(TAG, "转写失败: $e")
                view.setStatus(if (internalMode) "内录模式 · 转写失败：${e.message?.take(80)}" else "麦克风模式 · 转写失败：${e.message?.take(80)}")
                return@withLock
            }

            if (text.isBlank()) {
                view.setStatus(if (internalMode) "内录模式 · 监听中" else "麦克风模式 · 监听中")
                return@withLock
            }

            Log.i(TAG, "[听视频] 转写：${text.take(60)}")
            view.update(text, "正在翻译…")

            val translated = TranslatorFactory.current().translate(text, App.prefs.targetLang, App.prefs.sourceLang)
                .fold(
                    onSuccess = { it },
                    onFailure = { e ->
                        Log.e(TAG, "翻译失败", e)
                        "翻译失败：${e.message ?: "未知错误"}"
                    }
                )
            view.update(text, translated)
            view.setStatus(if (internalMode) "内录模式 · 监听中" else "麦克风模式 · 监听中")
        }
    }

    // ============================ 前台服务 ============================

    private fun startForegroundCompat() {
        val channelId = "video_listen_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "听视频翻译", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持听视频翻译运行" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("🎧 听视频翻译中")
            .setContentText("正在转写并翻译设备声音，点通知停止")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(android.app.PendingIntent.getService(
                this, 0,
                Intent(this, VideoListenService::class.java).setAction(ACTION_STOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            ))
            .build()

        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && internalMode ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !internalMode ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        running = false
        captureThread?.interrupt()
        captureThread = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { projection?.stop() }
        projection = null
        subtitleView?.detachFromWindow(windowManager)
        subtitleView = null
        scope.cancel()
        instance = null
        Log.i(TAG, "听视频翻译已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenTranslator"
        private const val NOTIF_ID = 1002
        private const val SAMPLE_RATE = 16000

        const val ACTION_STOP = "com.hunter.screentranslator.VIDEO_LISTEN_STOP"
        const val EXTRA_INTERNAL = "internal"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        var instance: VideoListenService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
