package com.hunter.screentranslator.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容协议通用翻译器。
 * 覆盖：DeepSeek / OpenAI / 通义千问 / 智谱 GLM / 火山豆包 等
 *
 * endpoint 传入完整的 chat/completions 地址（由工厂用 buildChatEndpoint 构造）。
 */
open class OpenAICompatibleTranslator(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val engineName: String = "AI",
    /** 共享 client（HttpClients 单例）；测试可注入替身 */
    private val client: OkHttpClient = HttpClients.llm
) : Translator {

    /**
     * v1.8.0 图片翻译（OpenAI 兼容多模态格式）。
     *
     * 关键点（照抄官方要求，避免"图片被静默忽略"这类最难排查的失败）：
     * 1. `content` 必须是**数组**，不是字符串 —— 传字符串时模型会收到不到图片数据，
     *    且**不报错**，只会基于提示词瞎编。
     * 2. 图片放在 `image_url.url`，用 `data:<mime>;base64,<data>` 的 data URI。
     * 3. `detail` 用 "high"：屏幕截图多为小字，low 档会丢细节。
     * 4. 必须显式设置 `max_tokens`，否则视觉请求的响应可能被截断。
     */
    override suspend fun translateImage(
        imageBytes: ByteArray,
        mimeType: String,
        targetLang: String,
        hint: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "未配置 $engineName API Key" }
            require(imageBytes.isNotEmpty()) { "图片数据为空" }

            val targetName = LANG_DISPLAY[targetLang] ?: targetLang
            val dataUri = "data:$mimeType;base64,${Base64.encodeToString(imageBytes, Base64.NO_WRAP)}"

            val body = JSONObject().apply {
                // 模型名留空时回退到引擎常用的文本模型：实测 DeepSeek 官方端点对
                // deepseek-chat / deepseek-v4-flash / vision-exp 三个名字都能读图
                // （服务端统一路由到 deepseek-flash），所以这里不阻断请求。
                // 若用户的自定义中转不支持图片，会在下方 HTTP 分支拿到明确报错。
                put("model", model.ifBlank { "deepseek-chat" })
                put("temperature", 0.1)
                put("stream", false)
                // 视觉请求必须给足输出上限，否则长截图的长译文会被截断
                put("max_tokens", VISION_MAX_TOKENS)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", buildImageSystemPrompt(targetName, hint))
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        // content 是数组！不是字符串
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "请翻译这张图片里的文字。")
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", dataUri)
                                    put("detail", "high")
                                })
                            })
                        })
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    throw RuntimeException("HTTP ${resp.code}: ${err.take(300)}")
                }
                val respStr = resp.body?.string() ?: throw RuntimeException("空响应")
                val content = JSONObject(respStr)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                if (content.isEmpty()) {
                    throw RuntimeException("模型没有返回译文（可能图片里没有可识别的文字）")
                }
                content
            }
        }
    }

    override suspend fun translate(text: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (text.isBlank()) return@runCatching ""
                require(apiKey.isNotBlank()) { "未配置 $engineName API Key" }

                val targetName = LANG_DISPLAY[targetLang] ?: targetLang

                val body = JSONObject().apply {
                    put("model", model.ifBlank { "deepseek-chat" })
                    put("temperature", 0.1)
                    put("stream", false)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", buildSystemPrompt(targetName))
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", text)
                        })
                    })
                }.toString()

                val req = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $apiKey")
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

                    JSONObject(respStr)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                }
            }
        }

    private fun buildSystemPrompt(targetName: String): String = """
        你是一个专业的实时屏幕翻译引擎。用户会给你一段从屏幕上抓取的文字，你需要：

        1. 自动识别原文使用的语言（即使包含多种语言混合）。
        2. 把它翻译成【$targetName】。
        3. 只输出翻译结果本身，不要解释、不要原译文对照、不要分点、不要任何额外说明。
        4. 如果原文本身就是 $targetName，则原样输出。
        5. 保留原文的换行结构、标点风格、专有名词。
        6. 如果原文是 UI 按钮/菜单文字，翻译要简洁；如果是长段落，翻译要自然流畅。

        现在请翻译：
    """.trimIndent()

    /**
     * v1.8.0 图片翻译用的系统提示词：强调"只翻画面里的文字"。
     *
     * v1.15.0 支持 [hint]（调用场景说明）。这里刻意**把 hint 拼在基础提示词之外**，
     * 而不是直接塞进 `"""..."""` 里做插值：trimIndent() 是在插值**之后**才执行的，
     * 插入内容里的换行会参与"最小缩进"的计算，导致整段提示词的缩进被意外改写。
     * 拼在外面就不存在这个问题。
     */
    private fun buildImageSystemPrompt(targetName: String, hint: String? = null): String {
        val base = """
            你是一个专业的屏幕翻译引擎。用户会给你一张手机屏幕截图，你需要：

            1. 读出画面里所有可见的文字（含 UI 按钮、菜单、正文、字幕）。
            2. 把这些文字翻译成【$targetName】。
            3. 只输出译文，不要描述画面、不要解释、不要加"翻译如下"之类的开场白。
            4. 如果画面里有多段文字，按阅读顺序（自上而下、自左而右）分行输出译文。
            5. 如果画面里没有任何文字，只输出：没有识别到文字
            6. 保留原文的换行与段落结构；UI 短标签译得简洁，长段落译得自然。
        """.trimIndent()
        val extra = if (hint.isNullOrBlank()) "" else "\n\n【本次输入的特殊说明】\n$hint"
        return "$base$extra\n\n现在请翻译画面里的文字："
    }

    companion object {
        /** 视觉请求的输出上限：长截图的长译文需要足够空间，否则会被截断 */
        private const val VISION_MAX_TOKENS = 4096
        /**
         * 由 baseUrl 构造完整 chat/completions 端点。
         * 兼容各种版本后缀：
         * - https://api.deepseek.com            → +/v1/chat/completions
         * - https://api.openai.com/v1           → +/chat/completions
         * - https://.../compatible-mode/v1      → +/chat/completions（通义）
         * - https://open.bigmodel.cn/api/paas/v4 → +/chat/completions（智谱）
         * - https://ark.cn-beijing.volces.com/api/v3 → +/chat/completions（火山）
         */
        fun buildChatEndpoint(baseUrl: String): String {
            // 修复：原来用 baseUrl.trimEnd() —— Kotlin 的 trimEnd() 只去空白字符，
            // 不去 '/'。用户把 baseUrl 填成 "https://api.deepseek.com/" 会得到
            // "https://api.deepseek.com//v1/chat/completions"（双斜杠），网关直接 404。
            // 默认值不带尾斜杠所以默认能用，用户一改就崩。
            val b = baseUrl.trim().trimEnd('/')
            require(b.isNotEmpty()) { "Base URL 不能为空" }
            return if (Regex("/v\\d+$").containsMatchIn(b)) {
                "$b/chat/completions"
            } else {
                "$b/v1/chat/completions"
            }
        }
    }
}
