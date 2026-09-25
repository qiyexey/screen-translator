package com.hunter.screentranslator.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.slider.Slider
import com.hunter.screentranslator.App
import com.hunter.screentranslator.R
import com.hunter.screentranslator.api.LANG_DISPLAY
import com.hunter.screentranslator.databinding.ActivityTtsSettingsBinding
import com.hunter.screentranslator.util.EdgeToEdge
import com.hunter.screentranslator.util.Speaker
import com.hunter.screentranslator.util.TtsContent

/**
 * v1.12.0 三级页：朗读（TTS）。
 *
 * 语速/音调用 M3 Slider 的档位 0..15 映射 0.5~2.0（步进 0.1），与 Prefs 的 coerce 范围一致。
 *
 * v1.19.0：SeekBar → Slider。Slider 的 value 越界会抛 IllegalStateException
 * （SeekBar 只会静默钳到 max），所以下面两处初始化都显式 coerceIn(0f, 15f)。
 *
 * ⚠️ 拆页时的一处适配：原来的「试听 / 检测引擎」靠主页的 `spinnerTarget` 反查语言，
 * 那个下拉现在在[EngineSettingsActivity]里，本页取不到 —— 改为直接用已保存的
 * `App.prefs.targetLang`，语义完全等价（试听本来就该读当前目标语言）。
 */
class TtsSettingsActivity : BaseActivity() {

    private lateinit var b: ActivityTtsSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTtsSettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.install(this)
        b.topAppBar.setNavigationOnClickListener { finish() }

        setupTtsControls()

        b.btnSave.setOnClickListener {
            App.prefs.ttsAutoSpeak = b.swTtsAuto.isChecked
            // v1.9.5 修复语速"设 1.0 却偏快"：原来这里写的是 (progress + 10) / 10f，
            // 而拖动监听用的是 (p + 5) / 10f —— 同一个滑杆两套换算，
            // progress=5 时一个得 1.0、一个得 1.5，一点保存就快 50% 且标签不刷新。
            App.prefs.ttsRate = (b.seekTtsRate.value.toInt() + 5) / 10f
            App.prefs.ttsPitch = (b.seekTtsPitch.value.toInt() + 5) / 10f
            toast(getString(R.string.common_t10))
        }
    }

    private fun setupTtsControls() {
        b.swTtsAuto.isChecked = App.prefs.ttsAutoSpeak

        // ---- 朗读内容：原文 / 译文 / 两者 ----
        val contentValues = TtsContent.OPTIONS.map { it.first }
        val contentLabels = TtsContent.OPTIONS.map { it.second }
        b.spinnerTtsContent.setSimpleItems(contentLabels.toTypedArray())
        setSel(b.spinnerTtsContent, contentValues.indexOf(App.prefs.ttsContent).coerceAtLeast(0))
        b.spinnerTtsContent.setOnItemClickListener { _, _, pos, _ ->
            App.prefs.ttsContent = contentValues[pos]
            // 只有涉及原文时才需要选原文语言
            applyTtsSourceLangVisibility()
        }

        // ---- 原文语言：自动 + 8 种目标语言（译文语言就是「目标语言」，不在此列）----
        val srcValues = listOf("auto") + LANG_DISPLAY.keys.toList()
        val srcLabels = listOf("自动判断（推荐）") +
                LANG_DISPLAY.map { "${it.value} (${it.key})" }
        b.spinnerTtsSourceLang.setSimpleItems(srcLabels.toTypedArray())
        setSel(b.spinnerTtsSourceLang, srcValues.indexOf(App.prefs.ttsSourceLang).coerceAtLeast(0))
        b.spinnerTtsSourceLang.setOnItemClickListener { _, _, pos, _ ->
            App.prefs.ttsSourceLang = srcValues[pos]
        }
        applyTtsSourceLangVisibility()

        // 语速：progress = rate*10 - 5（0.5→0, 1.0→5, 2.0→15）
        b.seekTtsRate.value = ((App.prefs.ttsRate * 10).toInt() - 5).toFloat().coerceIn(0f, 15f)
        b.seekTtsRate.addOnChangeListener(object : Slider.OnChangeListener {
            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                val p = value.toInt()
                val rate = (p + 5) / 10f
                App.prefs.ttsRate = rate
                if (fromUser) b.tvTtsRateValue.text = String.format("%.1f×", rate)
            }
        })
        b.tvTtsRateValue.text = String.format("%.1f×", App.prefs.ttsRate)

        // 音调：同上
        b.seekTtsPitch.value = ((App.prefs.ttsPitch * 10).toInt() - 5).toFloat().coerceIn(0f, 15f)
        b.seekTtsPitch.addOnChangeListener(object : Slider.OnChangeListener {
            override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
                val p = value.toInt()
                val pitch = (p + 5) / 10f
                App.prefs.ttsPitch = pitch
                if (fromUser) b.tvTtsPitchValue.text = String.format("%.1f", pitch)
            }
        })
        b.tvTtsPitchValue.text = String.format("%.1f", App.prefs.ttsPitch)

        // 试听：用当前目标语言读一句示例，顺便验证系统 TTS 是否可用
        b.btnTtsTest.setOnClickListener {
            val lang = App.prefs.targetLang
            val sample = TTS_SAMPLE[lang] ?: TTS_SAMPLE["en"]!!
            Speaker.speak(this, sample, lang) { ok, err ->
                if (ok) {
                    toast(getString(R.string.tts_settings_t09))
                } else if (err != null) {
                    AlertDialog.Builder(this)
                        .setTitle("朗读不可用")
                        .setMessage(err)
                        .setPositiveButton("去系统设置") { _, _ ->
                            Speaker.openSystemTtsSettings(this)
                        }
                        .setNegativeButton("知道了", null)
                        .show()
                }
            }
        }

        // 语音引擎诊断：列出系统里实际装了的引擎 + 当前默认引擎 + 目标语言支持情况
        b.btnTtsDiag.setOnClickListener {
            val lang = App.prefs.targetLang
            val locale = Speaker.localeOf(lang)
            val info = StringBuilder(Speaker.engineSummary(this))
            info.append("\n\n目标语言：${LANG_DISPLAY[lang] ?: lang}")

            Speaker.ensureReady(this) { ok ->
                val support = if (!ok) {
                    "❌ 引擎初始化失败\n${Speaker.lastInitError ?: ""}"
                } else {
                    val avail = runCatching {
                        Speaker.isLanguageSupported(locale)
                    }.getOrDefault(false)
                    if (avail) "✅ 当前引擎支持该语言，朗读应该正常"
                    else "⚠️ 当前引擎不支持该语言，但本应用会自动切换其它引擎；\n" +
                            "若都没有，请到系统设置里下载该语言包"
                }
                AlertDialog.Builder(this)
                    .setTitle("语音引擎检测")
                    .setMessage("$info\n\n$support")
                    .setPositiveButton("去系统设置") { _, _ ->
                        Speaker.openSystemTtsSettings(this)
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    /** 只在「朗读内容」涉及原文时显示原文语言选择行（只读译文时它是多余的） */
    private fun applyTtsSourceLangVisibility() {
        val needSource = App.prefs.ttsContent != TtsContent.TRANSLATED
        b.rowTtsSourceLang.visibility = if (needSource) View.VISIBLE else View.GONE
    }

    private companion object {
        /** 试听用的各语言示例句（覆盖 LANG_DISPLAY 全部语言） */
        val TTS_SAMPLE = mapOf(
            "zh" to "你好，这是屏幕翻译的朗读测试。",
            "en" to "Hello, this is the screen translator speech test.",
            "ja" to "こんにちは、画面翻訳の読み上げテストです。",
            "ko" to "안녕하세요, 화면 번역 읽기 테스트입니다.",
            "fr" to "Bonjour, ceci est un test de lecture.",
            "de" to "Hallo, dies ist ein Sprachtest.",
            "es" to "Hola, esta es una prueba de lectura.",
            "ru" to "Здравствуйте, это тест озвучивания."
        )
    }
}
