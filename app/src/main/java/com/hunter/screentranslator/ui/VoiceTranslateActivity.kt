package com.hunter.screentranslator.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.api.WhisperClient
import com.hunter.screentranslator.databinding.ActivityVoiceTranslateBinding
import com.hunter.screentranslator.service.AudioSegmenter
import com.hunter.screentranslator.util.WavUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 语音输入翻译（v1.6.0，v1.7.0 增强引擎选择）：
 *
 * - 系统引擎：SpeechRecognizer 连续听写，partial 边说边翻；依赖设备语音服务（GMS/厂商）
 * - Whisper 引擎（v1.7.0）：自建麦克风采集 + 静音切句 + Whisper API 转写，
 *   不依赖任何系统服务，无 GMS 设备（多数国产 ROM）也能用
 */
class VoiceTranslateActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var b: ActivityVoiceTranslateBinding
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var restartJob: Job? = null
    private var partialJob: Job? = null

    /** 识别语言（说话人的语言），点击按钮循环切换 */
    private val listenLangs = listOf("en-US", "zh-CN", "ja-JP", "ko-KR", "fr-FR", "de-DE", "es-ES", "ru-RU")
    private val listenLangNames = listOf("英语", "中文", "日语", "韩语", "法语", "德语", "西语", "俄语")
    private var langIndex = 0

    // ===== Whisper 引擎模式（v1.7.0）=====
    private var useWhisper = false
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val whisperMutex = Mutex()   // 转写串行，避免结果乱序

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVoiceTranslateBinding.inflate(layoutInflater)
        setContentView(b.root)

        useWhisper = App.prefs.voiceEngine == "whisper"
        refreshEngineUi()

        b.btnBack.setOnClickListener { finish() }

        b.btnEngine.setOnClickListener {
            if (listening) {
                toast("先停止再切换引擎")
                return@setOnClickListener
            }
            useWhisper = !useWhisper
            App.prefs.voiceEngine = if (useWhisper) "whisper" else "system"
            refreshEngineUi()
        }

        b.btnLang.setOnClickListener {
            langIndex = (langIndex + 1) % listenLangs.size
            updateLangButton()
            if (listening && !useWhisper) restartListening()
        }
        updateLangButton()

        b.btnToggle.setOnClickListener { toggle() }
    }

    private fun refreshEngineUi() {
        b.btnEngine.text = if (useWhisper) "引擎：Whisper" else "引擎：系统"
        if (!useWhisper && !SpeechRecognizer.isRecognitionAvailable(this)) {
            // 系统模式不可用：直接引导切 Whisper
            AlertDialog.Builder(this)
                .setTitle("此设备没有系统语音服务")
                .setMessage(
                    "语音输入默认用系统自带识别（Google/厂商引擎），这台设备没有。\n\n" +
                            "点「切换到 Whisper 引擎」改用自建采集 + Whisper API 识别，" +
                            "不依赖系统服务（需在主界面配置语音识别 API Key）。"
                )
                .setPositiveButton("切换到 Whisper") { _, _ ->
                    useWhisper = true
                    App.prefs.voiceEngine = "whisper"
                    refreshEngineUi()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun updateLangButton() {
        b.btnLang.text = "识别：${listenLangNames[langIndex]}"
    }

    private fun toggle() {
        if (listening) {
            stopAll()
        } else {
            startWithPermission()
        }
    }

    private fun startWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            reallyStart()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            reallyStart()
        } else {
            toast("需要麦克风权限才能语音识别")
        }
    }

    private fun reallyStart() {
        if (useWhisper) startWhisper() else startSystem()
    }

    // ============================ 系统引擎模式 ============================

    private fun startSystem() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(this)
            }
        }
        listening = true
        b.btnToggle.text = "停止"
        b.tvStatus.text = "● 正在聆听（说 ${listenLangNames[langIndex]}）"
        startListening()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, listenLangs[langIndex])
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { recognizer?.startListening(intent) }
            .onFailure { toast("语音识别启动失败：${it.message}") }
    }

    private fun restartListening() {
        recognizer?.stopListening()
        startListening()
    }

    // ============================ Whisper 引擎模式（v1.7.0） ============================

    private fun startWhisper() {
        if (App.prefs.asrApiKey.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("需要先配置语音识别 API")
                .setMessage("Whisper 引擎走 OpenAI 兼容的语音识别接口，请回主界面 → 「语音识别（听视频用）」填入 API Key。")
                .setPositiveButton("回去配置") { _, _ -> finish() }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 2))
                .build()
        } catch (e: Exception) {
            toast("麦克风初始化失败：${e.message}")
            return
        }

        audioRecord = record
        listening = true
        b.btnToggle.text = "停止"
        b.tvStatus.text = "● 正在聆听（Whisper · 停顿断句）"

        val langHint = listenLangs[langIndex].substringBefore('-')  // en-US → en
        val segmenter = AudioSegmenter(sampleRate) { pcm ->
            lifecycleScope.launch { processWhisperSegment(pcm, sampleRate, langHint) }
        }

        captureThread = Thread {
            val chunk = ByteArray(sampleRate / 10 * 2)  // 100ms
            try {
                record.startRecording()
                while (listening) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n > 0) segmenter.feed(chunk, n)
                    else if (n < 0) break
                }
                segmenter.flush()
            } catch (_: Exception) {
            }
        }.apply { start() }
    }

    private suspend fun processWhisperSegment(pcm: ByteArray, sampleRate: Int, langHint: String) {
        whisperMutex.withLock {
            if (!listening) return@withLock
            b.tvStatus.text = "● 正在转写…"
            val wav = withContext(Dispatchers.Default) { WavUtils.pcmToWav(pcm, sampleRate, 1) }
            val whisper = WhisperClient(App.prefs.asrBaseUrl, App.prefs.asrApiKey, App.prefs.asrModel)
            val text = whisper.transcribe(wav, langHint).getOrElse { e ->
                b.tvStatus.text = "● 转写失败：${e.message?.take(60)}"
                return@withLock
            }
            if (text.isBlank()) {
                b.tvStatus.text = "● 正在聆听（Whisper · 停顿断句）"
                return@withLock
            }
            b.tvHeard.text = text
            b.tvStatus.text = "● 正在翻译…"
            translate(text, isFinal = true)
            b.tvStatus.text = "● 正在聆听（Whisper · 停顿断句）"
        }
    }

    // ============================ 停止 ============================

    private fun stopAll() {
        listening = false
        restartJob?.cancel()
        partialJob?.cancel()
        runCatching { recognizer?.stopListening() }
        // Whisper 模式：停采集线程并释放麦克风
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        captureThread = null
        b.btnToggle.text = "开始聆听"
        b.tvStatus.text = "● 待机"
    }

    // ============================ RecognitionListener（系统引擎） ============================

    override fun onReadyForSpeech(params: Bundle?) {
        b.tvStatus.text = "● 正在聆听（说 ${listenLangNames[langIndex]}）"
    }

    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        b.tvStatus.text = "● 处理中…"
    }

    override fun onError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (listening) scheduleRestart(200)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (listening) scheduleRestart(500)
            SpeechRecognizer.ERROR_CLIENT -> if (listening) scheduleRestart(300)
            else -> {
                b.tvStatus.text = "● 出错了（代码 $error），已停止"
                stopAll()
            }
        }
    }

    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            b.tvHeard.text = text
            translate(text, isFinal = true)
        }
        if (listening) scheduleRestart(100)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            b.tvHeard.text = text
            partialJob?.cancel()
            partialJob = lifecycleScope.launch {
                delay(1200)
                translate(text, isFinal = false)
            }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ============================ 翻译 ============================

    private var translating = false

    private fun translate(text: String, isFinal: Boolean) {
        if (translating && !isFinal) return
        translating = true
        lifecycleScope.launch {
            if (!isFinal) b.tvResult.text = "正在翻译…"
            val result = TranslatorFactory.current().translate(text, App.prefs.targetLang)
            translating = false
            result.fold(
                onSuccess = {
                    b.tvResult.text = it
                    b.scrollResult.post { b.scrollResult.fullScroll(ScrollView.FOCUS_DOWN) }
                },
                onFailure = {
                    if (isFinal) b.tvResult.text = "翻译失败：${it.message ?: "未知错误"}"
                }
            )
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        restartJob?.cancel()
        restartJob = lifecycleScope.launch {
            delay(delayMs)
            if (listening && !useWhisper) startListening()
        }
    }

    override fun onStop() {
        // 切后台就别继续占麦克风了
        if (listening) stopAll()
        super.onStop()
    }

    override fun onDestroy() {
        listening = false
        restartJob?.cancel()
        partialJob?.cancel()
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        super.onDestroy()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
