package com.hunter.screentranslator.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.LANG_DISPLAY
import com.hunter.screentranslator.api.TranslationEngine
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.databinding.ActivityTranslateInputBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 输入翻译（v1.6.0）：打字停顿 600ms 即自动翻译。
 * 复用现有引擎体系（11 家）与目标语言配置；源语言自动识别。
 * 入口：主界面「输入翻译」按钮 / 悬浮球长按。
 */
class TranslateInputActivity : AppCompatActivity() {

    private lateinit var b: ActivityTranslateInputBinding
    private var translateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTranslateInputBinding.inflate(layoutInflater)
        setContentView(b.root)

        val engine = TranslationEngine.fromKey(App.prefs.engine)
        b.tvEngineInfo.text = "${engine.displayName} → ${LANG_DISPLAY[App.prefs.targetLang] ?: App.prefs.targetLang}"

        b.btnBack.setOnClickListener { finish() }
        b.btnClear.setOnClickListener {
            b.etInput.setText("")
            b.tvResult.text = "译文会显示在这里…"
        }

        b.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, t: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, t: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return
                // 防抖：停顿 600ms 再翻
                translateJob?.cancel()
                translateJob = lifecycleScope.launch {
                    delay(600)
                    doTranslate(text)
                }
            }
        })
    }

    private suspend fun doTranslate(text: String) {
        runOnUiThread { b.tvResult.text = "正在翻译…" }
        val result = TranslatorFactory.current().translate(text, App.prefs.targetLang)
        // 翻译期间输入可能又变了，过期结果丢弃
        val latest = b.etInput.text?.toString()?.trim().orEmpty()
        if (latest != text) return
        result.fold(
            onSuccess = {
                runOnUiThread {
                    b.tvResult.text = it
                    // 滚到底部看最新译文
                    b.scrollResult.post { b.scrollResult.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            },
            onFailure = { e ->
                runOnUiThread {
                    b.tvResult.text = "翻译失败：${e.message ?: "未知错误"}"
                    Toast.makeText(this, "翻译失败，请检查引擎配置", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
