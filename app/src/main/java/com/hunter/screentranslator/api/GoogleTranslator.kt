package com.hunter.screentranslator.api

import com.hunter.screentranslator.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Google Cloud Translation API v2 实现。
 * 接口：GET https://translation.googleapis.com/language/translate/v2
 * 鉴权：API Key（在 Google Cloud Console 启用 Translation API 后获取）
 *
 * 设置项：
 * - googleApiKey：Google API Key
 */
class GoogleTranslator(
    private val client: OkHttpClient = HttpClients.standard
) : Translator {

    override suspend fun translate(
        text: String,
        targetLang: String,
        sourceLang: String
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (text.isBlank()) return@runCatching ""

                val apiKey = App.prefs.googleApiKey
                require(apiKey.isNotBlank()) { "未配置 Google API Key" }

                // Google 目标语言代码：中文用 zh-CN，其余直接用代码
                val target = googleLangCode(targetLang)

                val builder = "https://translation.googleapis.com/language/translate/v2".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", text)
                    .addQueryParameter("target", target)
                    .addQueryParameter("format", "text")
                    .addQueryParameter("key", apiKey)

                // v1.20.0：显式指定源语言时下发 `source`。
                // 不传该参数时 Google 自己检测，并在响应的 detectedSourceLanguage
                // 里回报结果（本类目前不解这个字段，也不需要 —— 译文才是有用的）。
                // 注意 auto 要**整个参数略去**，不能发 source=auto：
                // v2 没有 auto 这个语言码，会被判成非法语言直接 400。
                if (sourceLang != SOURCE_AUTO) {
                    builder.addQueryParameter("source", googleLangCode(sourceLang))
                }

                val req = Request.Builder().url(builder.build()).get().build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty()
                        throw RuntimeException("HTTP ${resp.code}: ${err.take(200)}")
                    }
                    val respStr = resp.body?.string()
                        ?: throw RuntimeException("空响应")

                    // {"data":{"translations":[{"translatedText":"...","detectedSourceLanguage":"en"}]}}
                    JSONObject(respStr)
                        .getJSONObject("data")
                        .getJSONArray("translations")
                        .getJSONObject(0)
                        .getString("translatedText")
                }
            }
        }

    private fun googleLangCode(code: String): String = when (code) {
        "zh" -> "zh-CN"
        else -> code
    }
}
