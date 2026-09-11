package com.hunter.screentranslator.util

import java.io.ByteArrayOutputStream

/**
 * WAV 打包工具（v1.6.0）：给 PCM 数据加 44 字节 RIFF 头。
 * Whisper 接口要求标准 WAV 容器，裸 PCM 会被拒。
 */
object WavUtils {

    /**
     * @param pcm        16bit little-endian PCM 数据
     * @param sampleRate 采样率（如 16000）
     * @param channels   声道数（1=mono）
     */
    fun pcmToWav(pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size

        val out = ByteArrayOutputStream(44 + dataSize)
        // RIFF chunk
        out.write("RIFF".toByteArray())
        writeIntLE(out, 36 + dataSize)
        out.write("WAVE".toByteArray())
        // fmt chunk
        out.write("fmt ".toByteArray())
        writeIntLE(out, 16)                    // fmt chunk size
        writeShortLE(out, 1)                   // PCM
        writeShortLE(out, channels)
        writeIntLE(out, sampleRate)
        writeIntLE(out, byteRate)
        writeShortLE(out, blockAlign)
        writeShortLE(out, bitsPerSample)
        // data chunk
        out.write("data".toByteArray())
        writeIntLE(out, dataSize)
        out.write(pcm)
        return out.toByteArray()
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeShortLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
