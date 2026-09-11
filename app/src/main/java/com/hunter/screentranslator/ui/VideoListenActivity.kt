package com.hunter.screentranslator.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hunter.screentranslator.App
import com.hunter.screentranslator.databinding.ActivityVideoListenBinding
import com.hunter.screentranslator.service.VideoListenService

/**
 * 听视频翻译控制页（v1.6.0）：
 * 选内录/麦克风 → 授权（屏幕录制 / 麦克风）→ 拉起 VideoListenService → finish 看字幕。
 */
class VideoListenActivity : AppCompatActivity() {

    private lateinit var b: ActivityVideoListenBinding
    private var internalMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVideoListenBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }

        // 模式选择（卡片高亮切换）
        // 修复（v1.9.1）：原先写死 #4CAF50 / #3A3A3F，浅色主题下亮绿描边对比不足、
        // 夜间主题下深灰描边又几乎不可见。改为 M3 语义色，两套主题都清晰。
        fun refreshModeUi() {
            val accent = themeColor(com.google.android.material.R.attr.colorPrimary)
            val plain = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
            b.cardInternal.strokeColor = if (internalMode) accent else plain
            b.cardMic.strokeColor = if (!internalMode) accent else plain
        }
        b.cardInternal.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                toast("内录模式需要 Android 10 及以上，请用麦克风模式")
                return@setOnClickListener
            }
            internalMode = true; refreshModeUi()
        }
        b.cardMic.setOnClickListener { internalMode = false; refreshModeUi() }
        refreshModeUi()

        if (VideoListenService.isRunning()) {
            b.btnStart.visibility = android.view.View.GONE
            b.btnStop.visibility = android.view.View.VISIBLE
        }

        b.btnStart.setOnClickListener { start() }
        b.btnStop.setOnClickListener {
            VideoListenService.instance?.let { stopService(Intent(this, VideoListenService::class.java)) }
            toast("已停止")
            finish()
        }
    }

    private fun start() {
        // Whisper Key 检查
        if (App.prefs.asrApiKey.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("需要先配置语音识别 API")
                .setMessage(
                    "听视频翻译依赖 OpenAI 兼容的语音识别接口（Whisper）。\n\n" +
                            "请回主界面 → 「语音识别（听视频用）」填入 API Key。\n\n" +
                            "支持：OpenAI 官方、OpenAI 兼容中转、硅基流动等平台。"
                )
                .setPositiveButton("回去配置") { _, _ -> finish() }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (internalMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 内录：申请屏幕录制授权（AudioPlaybackCapture 复用 MediaProjection 授权）
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
        } else {
            // 麦克风：运行时权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startService(internal = false)
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, VideoListenService::class.java).apply {
                    putExtra(VideoListenService.EXTRA_INTERNAL, true)
                    putExtra(VideoListenService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(VideoListenService.EXTRA_RESULT_DATA, data)
                }
                startForegroundService(intent)
                toast("已开始，切到视频 App 看字幕")
                finish()
            } else {
                toast("未授权屏幕录制，无法内录")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startService(internal = false)
        } else {
            toast("需要麦克风权限")
        }
    }

    private fun startService(internal: Boolean) {
        val intent = Intent(this, VideoListenService::class.java).apply {
            putExtra(VideoListenService.EXTRA_INTERNAL, internal)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        toast("已开始，切到视频 App 看字幕")
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 从当前主题解析 M3 语义色（使卡片描边跟随明暗主题） */
    private fun themeColor(attrRes: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attrRes, tv, true)
        return if (tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, tv.resourceId)
        } else {
            tv.data
        }
    }

    companion object {
        private const val REQ_PROJECTION = 10
        private const val REQ_MIC = 11
    }
}
