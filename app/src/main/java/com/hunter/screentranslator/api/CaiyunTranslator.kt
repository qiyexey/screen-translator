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
 * 彩云小译 API 实现。
 * 接口：POST https://api.interpreter.caiyunai.com/v1/translator
 * 鉴权：X-Authorization: token XXX
 *
 * 设置项：caiyunToken（在 platform.caiyunapp.com 申请）
 * 免费额度：新用户 100 万字/月
 */
class CaiyunTranslator(
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

                val token = App.prefs.caiyunToken.trim()
                require(token.isNotBlank()) { "未配置彩云小译 Token" }

                // trans_type 的格式是 "<源>2<目标>"，彩云把小写 "auto" 识别为自动检测。
                // 彩云的语言码与内部码一致（zh/en/ja/ko/fr/de/es/ru 全部相同），
                // 所以显式指定时可以直接用内部码，不需要额外的映射表。
                val transType = "${sourceLang}2$targetLang"

                // 多行文本按行拆分翻译（彩云按行返回）
                val lines = text.split('\n')

                val body = JSONObject().apply {
                    put("source", JSONArray(lines))
                    put("trans_type", transType)
                    put("detect", true)
                    put("media", "text")
                }.toString()

                val req = Request.Builder()
                    .url("https://api.interpreter.caiyunai.com/v1/translator")
                    .header("X-Authorization", "token $token")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty()
                        throw RuntimeException("HTTP ${resp.code}: ${err.take(200)}")
                    }
                    val respStr = resp.body?.string()
                        ?: throw RuntimeException("空响应")

                    // {"target":["第一行译文","第二行译文"]}
                    val target = JSONObject(respStr).getJSONArray("target")
                    val sb = StringBuilder()
                    for (i in 0 until target.length()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(target.getString(i))
                    }
                    sb.toString()
                }
            }
        }
}
