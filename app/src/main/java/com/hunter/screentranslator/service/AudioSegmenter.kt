package com.hunter.screentranslator.service

import java.io.ByteArrayOutputStream

/**
 * 音频切句器（v1.6.0）：把连续 PCM 流按语音活动切成句子片段。
 *
 * 原理：
 * - 逐块计算 RMS 音量，跟踪自适应噪声底（安静环境自动变灵敏）
 * - 音量高于 噪声底×3 且高于绝对阈值 → 语音中
 * - 连续静音 ≥ 700ms 且已累计语音 ≥ 400ms → 切出一句
 * - 单句超过 15s 强制切（控制转写延迟）
 *
 * 输出：每句的 PCM 数据（含尾部静音，给 Whisper 一点上下文）。
 */
class AudioSegmenter(
    private val sampleRate: Int = 16000,
    private val onSegment: (ByteArray) -> Unit
) {

    private val buffer = ByteArrayOutputStream()
    private var voicedMs = 0f
    private var silenceMs = 0f
    private var inSpeech = false

    /** 自适应噪声底（指数平滑跟随最小音量） */
    private var noiseFloor = INITIAL_NOISE_FLOOR

    fun feed(pcm: ByteArray, byteCount: Int) {
        if (byteCount <= 0) return
        val blockMs = byteCount * 1000f / (sampleRate * 2)  // 16bit mono
        val rms = rms(pcm, byteCount)

        // 噪声底跟踪：安静时缓慢下降，有声音时不抬升
        if (rms < noiseFloor) {
            noiseFloor = noiseFloor * 0.9f + rms * 0.1f
        }

        val isVoice = rms > noiseFloor * 3f && rms > ABS_VOICE_THRESHOLD
        buffer.write(pcm, 0, byteCount)

        if (isVoice) {
            inSpeech = true
            voicedMs += blockMs
            silenceMs = 0f
        } else if (inSpeech) {
            silenceMs += blockMs
            val totalMs = voicedMs + silenceMs
            when {
                // 停顿够了且说了足够多 → 成句
                silenceMs >= END_SILENCE_MS && voicedMs >= MIN_SPEECH_MS -> emit()
                // 说了不到 400ms 就安静超过 1.2s → 当噪声丢掉
                voicedMs < MIN_SPEECH_MS && silenceMs >= 1200f -> reset()
                // 正常词间停顿，继续攒
                else -> Unit
            }
            // 超长强切
            if (inSpeech && totalMs >= MAX_SEGMENT_MS) emit()
        }
    }

    /** 强制吐出当前缓冲（结束时调用，避免尾句丢失） */
    fun flush() {
        if (voicedMs >= MIN_SPEECH_MS) emit() else reset()
    }

    private fun emit() {
        val pcm = buffer.toByteArray()
        reset()
        if (pcm.isNotEmpty()) onSegment(pcm)
    }

    private fun reset() {
        buffer.reset()
        voicedMs = 0f
        silenceMs = 0f
        inSpeech = false
    }

    private fun rms(pcm: ByteArray, count: Int): Double {
        var sum = 0.0
        val n = count / 2
        var i = 0
        while (i + 1 < count) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            sum += s.toDouble() * s
            i += 2
        }
        return if (n > 0) kotlin.math.sqrt(sum / n) else 0.0
    }

    companion object {
        private const val END_SILENCE_MS = 700f      // 静音多久算句尾
        private const val MIN_SPEECH_MS = 400f       // 比这短的语音当噪声
        private const val MAX_SEGMENT_MS = 15_000f   // 单句最长（强切）
        private const val ABS_VOICE_THRESHOLD = 250.0 // 16bit PCM 绝对阈值（再安静也有底噪）
        private const val INITIAL_NOISE_FLOOR = 400.0
    }
}
