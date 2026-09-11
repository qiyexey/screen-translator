package com.hunter.screentranslator.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 语音识别客户端（v1.6.0）：OpenAI 兼容 /v1/audio/transcriptions 接口（Whisper 系模型）。
 *
 * 兼容：OpenAI 官方、自建中转、硅基流动等 OpenAI 兼容平台。
 * 用法：把切分好的 WAV 片段（16kHz mono 16bit）发上去，返回转写文本。
 */
class WhisperClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    /**
     * 共享 client（HttpClients 单例）。
     * 修复前这里在构造函数里新建 OkHttpClient，而 VideoListenService 每个音频分段
     * 都新建一个 WhisperClient —— 每个分段泄漏一个连接池 + 一个线程池。
     */
    private val client: OkHttpClient = HttpClients.asr
) {

    /** 把一段 WAV 音频转写成文字 */
    suspend fun transcribe(wav: ByteArray, langHint: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(apiKey.isNotBlank()) { "未配置语音识别 API Key（主界面 → 语音识别设置）" }

                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", "audio.wav",
                        wav.toRequestBody("audio/wav".toMediaType())
                    )
                    .addFormDataPart("model", model.ifBlank { "whisper-1" })
                    .apply {
                        // 可选：语言提示（whisper 语言代码如 en/zh/ja）
                        if (!langHint.isNullOrBlank()) addFormDataPart("language", langHint)
                    }
                    .build()

                val req = Request.Builder()
                    .url(buildTranscriptionEndpoint(baseUrl))
                    .header("Authorization", "Bearer $apiKey")
                    .post(multipart)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty()
                        throw RuntimeException("语音识别 HTTP ${resp.code}: ${err.take(300)}")
                    }
                    val respStr = resp.body?.string()
                        ?: throw RuntimeException("空响应")
                    JSONObject(respStr).optString("text", "").trim()
                }
            }
        }

    companion object {
        /** 与 buildChatEndpoint 同规则：有 /v1 结尾直接拼，没有则补 /v1 */
        fun buildTranscriptionEndpoint(baseUrl: String): String {
            val b = baseUrl.trim().trimEnd('/')
            return if (Regex("/v\\d+$").containsMatchIn(b)) {
                "$b/audio/transcriptions"
            } else {
                "$b/v1/audio/transcriptions"
            }
        }
    }
}
