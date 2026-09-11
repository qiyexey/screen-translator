package com.hunter.screentranslator.api

import com.hunter.screentranslator.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 微软 Azure Translator v3 实现。
 * 接口：POST https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=zh
 * 鉴权：Ocp-Apim-Subscription-key（Azure 翻译资源密钥）
 *       如使用区域资源还需 Ocp-Apim-Subscription-Region header
 *
 * 设置项：
 * - msApiKey：Azure 密钥
 * - msRegion：区域（如 eastasia、global 等，区域资源必填）
 */
class MicrosoftTranslator(
    private val client: OkHttpClient = HttpClients.standard
) : Translator {

    override suspend fun translate(text: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (text.isBlank()) return@runCatching ""

                val apiKey = App.prefs.msApiKey
                require(apiKey.isNotBlank()) { "未配置微软翻译密钥" }

                val target = msLangCode(targetLang)
                val region = App.prefs.msRegion.trim()

                // Body: [{"Text":"hello"}]
                val body = JSONArray().apply {
                    put(JSONObject().put("Text", text))
                }.toString()

                val urlBuilder = StringBuilder()
                    .append("https://api.cognitive.microsofttranslator.com/translate")
                    .append("?api-version=3.0")
                    .append("&to=").append(target)

                val req = Request.Builder()
                    .url(urlBuilder.toString())
                    .header("Ocp-Apim-Subscription-key", apiKey)
                    .apply {
                        if (region.isNotBlank() && region != "global") {
                            header("Ocp-Apim-Subscription-Region", region)
                        }
                    }
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty()
                        throw RuntimeException("HTTP ${resp.code}: ${err.take(200)}")
                    }
                    val respStr = resp.body?.string()
                        ?: throw RuntimeException("空响应")

                    // [{"translations":[{"text":"...","to":"zh"}]}]
                    JSONArray(respStr)
                        .getJSONObject(0)
                        .getJSONArray("translations")
                        .getJSONObject(0)
                        .getString("text")
                }
            }
        }

    /** 微软语言代码：中文用 zh-Hans，其余直接代码 */
    private fun msLangCode(code: String): String = when (code) {
        "zh" -> "zh-Hans"
        else -> code
    }
}
