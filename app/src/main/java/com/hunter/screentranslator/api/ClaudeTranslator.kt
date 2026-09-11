package com.hunter.screentranslator.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Claude Messages API 实现。
 * 接口：POST https://api.anthropic.com/v1/messages
 * 鉴权：x-api-key + anthropic-version: 2023-06-01
 *
 * 设置项：claudeApiKey、claudeModel（默认 claude-3-5-haiku，便宜快速）
 */
class ClaudeTranslator(
    private val client: OkHttpClient = HttpClients.llm
) : Translator {

    /**
     * v1.8.0 图片翻译（Anthropic Messages API 的多模态格式）。
     *
     * 注意：Claude 的结构与 OpenAI **完全不同**，不能照搬：
     * - OpenAI: {"type":"image_url","image_url":{"url":"data:..."}}
     * - Claude: {"type":"image","source":{"type":"base64","media_type":"...","data":"..."}}
     * 图片块要用 `source`，且 media_type 与 data 分开传；混用格式会导致请求被拒。
     */
    override suspend fun translateImage(
        imageBytes: ByteArray,
        mimeType: String,
        targetLang: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(imageBytes.isNotEmpty()) { "图片数据为空" }

            val prefs = com.hunter.screentranslator.App.prefs
            val apiKey = prefs.claudeApiKey.trim()
            require(apiKey.isNotBlank()) { "未配置 Claude API Key" }

            val model = prefs.claudeModel.trim().ifBlank { "claude-3-5-haiku-20241022" }
            val targetName = LANG_DISPLAY[targetLang] ?: targetLang
            val b64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)

            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 4096)
                put(
                    "system",
                    "你是屏幕翻译引擎。读出用户给的截图里所有可见文字并翻译成【$targetName】。" +
                        "只输出译文，按阅读顺序分行；不要描述画面、不要解释。" +
                        "画面里没有文字时只输出：没有识别到文字"
                )
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        // Claude：content 数组里用 source 而非 image_url
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "image")
                                put("source", JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", mimeType)
                                    put("data", b64)
                                })
                            })
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "请翻译这张图片里的文字。")
                            })
                        })
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    throw RuntimeException("HTTP ${resp.code}: ${err.take(300)}")
                }
                val respStr = resp.body?.string() ?: throw RuntimeException("空响应")
                val content = JSONObject(respStr).getJSONArray("content")
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val item = content.getJSONObject(i)
                    if (item.optString("type") == "text") sb.append(item.getString("text"))
                }
                val out = sb.toString().trim()
                if (out.isEmpty()) throw RuntimeException("模型没有返回译文")
                out
            }
        }
    }

    override suspend fun translate(text: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (text.isBlank()) return@runCatching ""

                val prefs = com.hunter.screentranslator.App.prefs
                val apiKey = prefs.claudeApiKey.trim()
                require(apiKey.isNotBlank()) { "未配置 Claude API Key" }

                val model = prefs.claudeModel.trim().ifBlank { "claude-3-5-haiku-20241022" }
                val targetName = LANG_DISPLAY[targetLang] ?: targetLang

                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 4096)
                    put("system", "你是实时屏幕翻译引擎。把用户给你的文字翻译成【$targetName】（自动识别源语言）。只输出译文本身，保留换行结构，不要任何解释。")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", text)
                        })
                    })
                }.toString()

                val req = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty()
                        throw RuntimeException("HTTP ${resp.code}: ${err.take(300)}")
                    }
                    val respStr = resp.body?.string()
                        ?: throw RuntimeException("空响应")

                    // {"content":[{"type":"text","text":"..."}]}
                    val content = JSONObject(respStr).getJSONArray("content")
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val item = content.getJSONObject(i)
                        if (item.optString("type") == "text") {
                            sb.append(item.getString("text"))
                        }
                    }
                    sb.toString().trim()
                }
            }
        }
}
