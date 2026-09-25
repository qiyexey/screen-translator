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
import com.hunter.screentranslator.R
import com.hunter.screentranslator.databinding.ActivityVideoListenBinding
import com.hunter.screentranslator.service.VideoListenService
import com.hunter.screentranslator.util.EdgeToEdge

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
        EdgeToEdge.install(this)

        b.topAppBar.setNavigationOnClickListener { finish() }

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
                toast(getString(R.string.video_listen_t09))
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
            toast(getString(R.string.video_listen_t10))
            finish()
        }
    }

    private fun start() {
        // v1.20.0：Key 从"硬性前置条件"降级为"需要确认的提醒"。
        // 自建/本地的 OpenAI 兼容语音服务不校验密钥，不该被拦；
        // 但 baseUrl 指官方端点而忘填 Key 是最常见的配置疏漏，先问一次再放行。
        if (App.prefs.asrApiKey.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("没有填写语音识别 API Key")
                .setMessage(
                    "即将连接：\n${App.prefs.asrBaseUrl}\n\n" +
                            "如果是自己搭的免鉴权服务（本地 faster-whisper、whisper.cpp 等），" +
                            "直接继续即可。\n" +
                            "如果这里指的是 OpenAI 官方或需要密钥的中转，" +
                            "请回主界面 → 「语音识别（听视频用）」填入 API Key，" +
                            "否则会收到 401。"
                )
                .setPositiveButton("继续") { _, _ -> proceedStart() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        proceedStart()
    }

    private fun proceedStart() {
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
                toast(getString(R.string.video_listen_t11))
                finish()
            } else {
                toast(getString(R.string.video_listen_t12))
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startService(internal = false)
        } else {
            toast(getString(R.string.video_listen_t13))
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
        toast(getString(R.string.video_listen_t11))
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
