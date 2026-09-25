package com.hunter.screentranslator.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 必应网页端翻译（v1.15.14）。
 *
 * ## 这是**非公开接口**
 *
 * 走的是 www.bing.com/translator 这个网页自己用的接口，不是 Azure 官方 API。
 * 好处是**不需要密钥、不花钱**；代价也必须说清楚：
 *   - 微软随时可能改版（页面结构一变，取不到 IG / IID / key / token 就直接失效）
 *   - 可能被限流或触发风控
 *   - 不属于官方支持的用法
 * 所以实现上按"随时可能失效"来写：**任何一步失败都如实抛出具体原因**，
 * 不静默降级、不假装成功。失效时的正确动作是把用户引回官方引擎。
 *
 * ## 调用流程（这也是网页端自己的流程）
 *
 * ```
 * 1) GET  https://www.bing.com/translator        → 拿 cookie + 页面里的
 *                                                 IG / data-iid / params_AbusePreventionHelper
 * 2) POST https://www.bing.com/ttranslatev3?isVertical=1&IG=..&IID=..
 *         body: fromLang=auto-detect&text=..&to=..&token=..&key=..
 * 3) 返回 JSON 数组，取 [0].translations[0].text
 * ```
 *
 * key/token 是**页面级的时效凭证**（token 与时间戳绑定），所以缓存会话并定期重建；
 * 一旦 POST 失败就丢弃会话重试一次 —— 这是这类接口最常见的失败模式。
 *
 * ## 只能翻文字
 *
 * 它不接受图片，所以 [TranslationEngine.BING_WEB] 的 `visionCapable = false`，
 * **实时屏幕翻译用不了它**（那条路要的是能读图的引擎）。
 */
class BingWebTranslator : Translator {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            private val jar = HashMap<String, List<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                jar[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                jar[url.host] ?: emptyList()
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class Session(val ig: String, val iid: String, val key: String, val token: String, val at: Long)

    @Volatile private var session: Session? = null

    override suspend fun translate(
        text: String,
        targetLang: String,
        sourceLang: String
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (text.isBlank()) return@runCatching ""
                val to = bingLang(targetLang)
                // v1.20.0：from 位。必应网页端用字符串 "auto-detect" 表示自动，
                // 显式指定时换成真实语言码（同样走 bingLang，中文必须带 zh-Hans）。
                val from = if (sourceLang == SOURCE_AUTO) AUTO_DETECT else bingLang(sourceLang)

                // 长文本要分段：网页端单次请求有长度上限，超了会整段失败。
                // 按行切、每段不超过 CHUNK，避免在句子中间截断。
                val chunks = split(text.trim(), CHUNK)
                val out = StringBuilder()
                for (c in chunks) {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(requestOnce(c, to, from))
                }
                out.toString()
            }
        }

    /** 一次请求；会话失效则丢弃重建后重试一次 */
    private fun requestOnce(text: String, to: String, from: String): String {
        val s = session ?: bootstrap().also { session = it }
        return try {
            post(text, to, from, s)
        } catch (e: Exception) {
            // token 与时间戳绑定，过期/风控都会走到这里。重建一次再试，
            // 仍失败就把真实原因抛出去（不吞）。
            session = null
            val fresh = bootstrap()
            session = fresh
            try {
                post(text, to, from, fresh)
            } catch (e2: Exception) {
                throw RuntimeException("必应网页端失败：${e2.message ?: e2.javaClass.simpleName}")
            }
        }
    }

    private fun bootstrap(): Session {
        val req = Request.Builder()
            .url("https://www.bing.com/translator")
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        val html = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("取必应页面失败 HTTP ${resp.code}")
            resp.body?.string() ?: throw RuntimeException("必应页面内容为空")
        }
        val ig = Regex("IG:\"([0-9A-Fa-f]{8,})\"").find(html)?.groupValues?.get(1)
            ?: throw RuntimeException("必应页面结构已变（未找到 IG）")
        val iid = Regex("data-iid=\"(translator\\.[0-9]+)\"").find(html)?.groupValues?.get(1)
            ?: throw RuntimeException("必应页面结构已变（未找到 IID）")
        val m = Regex("params_AbusePreventionHelper\\s*=\\s*\\[([0-9]+),\"([^\"]+)\",([0-9]+)]")
            .find(html)
            ?: throw RuntimeException("必应页面结构已变（未找到 key/token）")
        return Session(ig, iid, m.groupValues[1], m.groupValues[2], System.currentTimeMillis())
    }

    private fun post(text: String, to: String, from: String, s: Session): String {
        val body = FormBody.Builder()
            .add("fromLang", from)
            .add("text", text)
            .add("to", to)
            .add("token", s.token)
            .add("key", s.key)
            .build()
        val req = Request.Builder()
            .url("https://www.bing.com/ttranslatev3?isVertical=1&IG=${s.ig}&IID=${s.iid}")
            .header("User-Agent", UA)
            .header("Referer", "https://www.bing.com/translator")
            .header("Origin", "https://www.bing.com")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val raw = client.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}：${t.take(160)}")
            t
        }
        val arr = JSONArray(raw)
        val tr = arr.getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text")
        if (tr.isBlank()) throw RuntimeException("必应返回空译文")
        return tr
    }

    /** App 内部语言码 → 必应语言码（中文必须带脚本后缀，否则可能返回繁体） */
    private fun bingLang(lang: String): String = when (lang) {
        "zh" -> "zh-Hans"
        "en" -> "en"
        "ja" -> "ja"
        "ko" -> "ko"
        "fr" -> "fr"
        "de" -> "de"
        "es" -> "es"
        "ru" -> "ru"
        else -> lang
    }

    /** 按行切块，尽量不在句子中间断开 */
    private fun split(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (line in text.split('\n')) {
            if (sb.isNotEmpty() && sb.length + line.length + 1 > max) {
                out.add(sb.toString()); sb.setLength(0)
            }
            if (line.length > max) {
                // 单行超长（少见）：硬切，否则这一段永远发不出去
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                var i = 0
                while (i < line.length) {
                    out.add(line.substring(i, minOf(i + max, line.length)))
                    i += max
                }
            } else {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val CHUNK = 900

        /**
         * 必应网页端表示"自动检测"的字面量。
         *
         * 注意它和 [SOURCE_AUTO] 不是同一个值 —— 接口层用 "auto"，而 bing.com
         * 的表单字段要的是 "auto-detect"。直接透传 "auto" 必应不认，会当成
         * 未知语言码处理。这类"同一概念在不同上游叫法不同"的偏差，
         * 正是 v1.20.0 串联源语言时最容易漏掉的一类 bug。
         */
        private const val AUTO_DETECT = "auto-detect"
    }
}
