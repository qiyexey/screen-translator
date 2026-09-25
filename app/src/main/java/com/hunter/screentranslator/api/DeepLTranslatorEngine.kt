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

    override suspend fun translate(
        text: String,
        targetLang: String,
        sourceLang: String
    ): Result<String> =
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

                val formBuilder = FormBody.Builder()
                    .add("text", text)
                    .add("target_lang", target)

                // v1.20.0：显式指定源语言时下发 source_lang（DeepL 也是全大写）。
                // DeepL 不接受 "auto"，只有省略 source_lang 才是自动检测。
                // 注意 DeepL 的源语言表**比目标语言表窄**：它不支持把 zh 当源语言
                // 之外的所有组合，但支持的具体集合随计划变动，所以这里不做白名单 ——
                // 若用户选了 DeepL 不支持的源语言，它会回一个带 error message 的
                // 4xx，被下面的 !resp.isSuccessful 分支原样转成可读错误，
                // 比我们在这里维护一张会过期的表更可靠。
                if (sourceLang != SOURCE_AUTO) {
                    formBuilder.add("source_lang", deeplLangCode(sourceLang))
                }

                val req = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "DeepL-Auth-Key $apiKey")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .post(formBuilder.build())
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
