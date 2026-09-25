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
 *
 * v1.20.0：**不再强制要求 API Key**。
 *
 * 原来是 `require(apiKey.isNotBlank())` 硬拦，理由是"官方的 /v1/audio/transcriptions
 * 必须带 Bearer"。但这个接口早已是事实标准，本地跑的 faster-whisper-server、
 * whisper.cpp 的 server 示例、各家开源自建方案都实现了它，**且都不校验密钥** ——
 * 原来那一行等于把这整类免密钥方案挡在门外，用户即使把 baseUrl 填成
 * `http://127.0.0.1:8000/v1` 也只会看到"未配置 API Key"。
 *
 * 现在的规则：
 * - Key 非空 → 照旧带 `Authorization: Bearer <key>`（官方与各中转都走这条）。
 * - Key 为空 → **完全不带这个 header**，而不是带一个空的 Bearer。
 *   空 Bearer 会被不少实现判成"提供了错误的凭据"而 401，
 *   比不提供凭据更糟 —— 后者通常直接放行。
 *
 * 没配 Key 时连的到底是"本地免鉴权服务"还是"忘了填官方 Key"，
 * 由用户在设置里填的 baseUrl 自己说明；这里不替用户猜，
 * 真错了由 [transcribe] 把上游的 HTTP 401 原样报出来，比一句笼统的前置拦截更有用。
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
                require(wav.isNotEmpty()) { "音频数据为空" }

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
                    .apply {
                        // 空 Key 时整个 header 都不加，见类注释
                        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                    }
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
