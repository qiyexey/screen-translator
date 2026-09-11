package com.hunter.screentranslator.api

import com.hunter.screentranslator.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 百度通用翻译 API 实现。
 * 接口：POST https://fanyi-api.baidu.com/api/trans/vip/translate
 * 鉴权：MD5 签名 = md5(appid + q + salt + 密钥)
 *
 * 设置项：baiduAppId + baiduKey（在 fanyi-api.baidu.com 注册获取）
 * 免费额度：标准版每月 5 万字符（QPS 限制 1）
 */
class BaiduTranslator(
    private val client: OkHttpClient = HttpClients.standard
) : Translator {

    override suspend fun translate(text: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (text.isBlank()) return@runCatching ""

                val prefs = App.prefs
                val appId = prefs.baiduAppId.trim()
                val key = prefs.baiduKey.trim()
                require(appId.isNotBlank() && key.isNotBlank()) { "未配置百度翻译 AppID/密钥" }

                // 百度目标语言代码与通用代码不同，必须显式映射。
                // 修复：原来只映射了 ja/ko/fr，漏了 es —— 而 LANG_DISPLAY 明确提供
                // "Español (es)" 选项，用户选中后百度收到 to=es（非法代码）必现失败。
                // 同时改用白名单：未知语言直接报错，而不是把内部码透传给上游
                // （透传是这一族 bug 的根因）。
                val target = BAIDU_LANG[targetLang]
                    ?: throw IllegalArgumentException("百度翻译不支持目标语言：$targetLang")

                val salt = System.currentTimeMillis().toString()
                val sign = md5(appId + text + salt + key)

                val form = FormBody.Builder()
                    .add("q", text)
                    .add("from", "auto")
                    .add("to", target)
                    .add("appid", appId)
                    .add("salt", salt)
                    .add("sign", sign)
                    .build()

                val req = Request.Builder()
                    .url("https://fanyi-api.baidu.com/api/trans/vip/translate")
                    .post(form)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw RuntimeException("HTTP ${resp.code}")
                    }
                    val respStr = resp.body?.string()
                        ?: throw RuntimeException("空响应")
                    val json = JSONObject(respStr)

                    // 错误响应：{"error_code":"54001","error_msg":"Invalid Sign"}
                    if (json.has("error_code")) {
                        throw RuntimeException(
                            "百度错误 ${json.getString("error_code")}: ${json.optString("error_msg")}"
                        )
                    }

                    // 多行文本会按行返回多个结果，拼接
                    val results = json.getJSONArray("trans_result")
                    val sb = StringBuilder()
                    for (i in 0 until results.length()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(results.getJSONObject(i).getString("dst"))
                    }
                    sb.toString()
                }
            }
        }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        // 显式无符号化："%02x".format(Byte) 依赖 Formatter 对 Byte 的特殊处理（侥幸正确），
        // 一旦有人改成 .format(it.toInt()) 就会得到 "ffffffff"，签名全错且报错难排查。
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        /**
         * 内部语言码 → 百度语言码。
         * 百度与通用代码不同的：日语 jp、韩语 kor、法语 fra、西班牙语 spa。
         * 覆盖 LANG_DISPLAY 中的全部 8 种目标语言。
         */
        private val BAIDU_LANG = mapOf(
            "zh" to "zh",
            "en" to "en",
            "ja" to "jp",
            "ko" to "kor",
            "fr" to "fra",
            "de" to "de",
            "es" to "spa",
            "ru" to "ru"
        )
    }
}
