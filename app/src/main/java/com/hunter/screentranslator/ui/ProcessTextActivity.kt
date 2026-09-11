package com.hunter.screentranslator.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.service.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统文字选择菜单入口。
 * 任何应用里长按选中文字 → 系统菜单出现「🌐 翻译」→ 点击后本 Activity 透明启动，
 * 抓取选中文本，调 DeepSeek 翻译，结果显示在悬浮窗，然后立即 finish() 回到原应用。
 *
 * 覆盖划词事件收不到的场景（部分 WebView、第三方阅读器等）。
 */
class ProcessTextActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()

        if (text.isNullOrBlank() || text.length < 2) {
            finish()
            return
        }

        // 拉起悬浮窗服务，然后异步翻译（本 Activity 立即结束，不打断用户）
        runCatching { OverlayService.start(this) }

        lifecycleScope.launch {
            OverlayService.update(text.take(300), "正在翻译…")
            val result = TranslatorFactory.current().translate(text, App.prefs.targetLang)
            val translated = result.fold(
                onSuccess = { it },
                onFailure = { e ->
                    Log.e("ScreenTranslator", "[菜单划词] 翻译失败", e)
                    "翻译失败：${e.message ?: "未知错误"}"
                }
            )
            OverlayService.update(text.take(300), translated)
        }

        finish()  // 透明 Activity，直接结束回到原应用
    }
}
