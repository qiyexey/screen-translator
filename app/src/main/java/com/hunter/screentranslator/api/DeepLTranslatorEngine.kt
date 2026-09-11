package com.hunter.screentranslator.api

import com.hunter.screentranslator.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * DeepL API v2 实现。
 * 接口：POST https://api-free.deepl.com/v2/translate（Free 计划）
 *      POST https://api.deepl.com/v2/translate（Pro 计划）
 * 鉴权：Authorization: DeepL-Auth-Key xxx
 *
 * 设置项：
 * - deeplApiKey：DeepL Auth Key（形如 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx:fx）
 *   注意结尾 :fx 是 Free 计划，自动选 api-free.deepl.com；否则走 Pro
 */
class DeepLTranslatorEngine(
    private val client: OkHttpClient = HttpClients.standard
) : Translator {

    override suspend fun translate(text: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (text.isBlank()) return@runCatching ""

                val apiKey = App.prefs.deeplApiKey.trim()
                require(apiKey.isNotBlank()) { "未配置 DeepL Auth Key" }

                // Free 计划的 key 以 :fx 结尾，自动选对应端点
                val endpoint = if (apiKey.endsWith(":fx")) {
                    "https://api-free.deepl.com/v2/translate"
                } else {
                    "https://api.deepl.com/v2/translate"
                }

                val target = deeplLangCode(targetLang)

                val form = FormBody.Builder()
                    .add("text", text)
                    .add("target_lang", target)
                    .build()

                val req = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "DeepL-Auth-Key $apiKey")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .post(form)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty()
                        throw RuntimeException("HTTP ${resp.code}: ${err.take(200)}")
                    }
                    val respStr = resp.body?.string()
                        ?: throw RuntimeException("空响应")

                    // {"translations":[{"detected_source_language":"EN","text":"..."}]}
                    JSONObject(respStr)
                        .getJSONArray("translations")
                        .getJSONObject(0)
                        .getString("text")
                }
            }
        }

    /** DeepL 目标语言代码全大写：ZH、EN、JA... */
    private fun deeplLangCode(code: String): String = code.uppercase()
}
