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
 * 2) POST 到 GET 的最终域名 /ttranslatev3?isVertical=1&IG=..&IID=..
 *         body: fromLang=auto-detect&text=..&to=..&token=..&key=..
 * 3) 返回 JSON 数组，取 [0].translations[0].text
 * ```
 *
 * key/token 是**页面级的时效凭证**（token 与时间戳绑定），所以缓存会话并定期重建；
 * 一旦 POST 失败就丢弃会话重试一次 —— 这是这类接口最常见的失败模式。
 *
 * ## 只能翻文字
 *
 * 它不接受图片，所以实时屏幕翻译先在本机 OCR，再把文字交给它。
 */
class BingWebTranslator(
    private val translatorPage: String = "https://www.bing.com/translator",
    private val client: OkHttpClient = newClient()
) : Translator {

    private data class Session(
        val pageUrl: HttpUrl,
        val ig: String,
        val iid: String,
        val key: String,
        val token: String,
        val at: Long
    )

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
                val from = if (sourceLang == SOURCE_AUTO) AUTO_DETECT else bingLang(sourceLang)
                val chunks = split(text.trim(), CHUNK)
                val out = StringBuilder()
                for (c in chunks) {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(requestOnce(c, to, from))
                }
                out.toString()
            }
        }

    /** A page token and its cookies must be used on the same final host after redirects. */
    @Synchronized
    private fun requestOnce(text: String, to: String, from: String): String {
        val now = System.currentTimeMillis()
        val s = session?.takeIf { now - it.at in 0 until SESSION_TTL_MS }
            ?: bootstrap().also { session = it }
        return try {
            post(text, to, from, s)
        } catch (e: Exception) {
            session = null
            val fresh = bootstrap()
            session = fresh
            try {
                post(text, to, from, fresh)
            } catch (e2: Exception) {
                throw RuntimeException("必应网页端失败：${e2.message ?: e2.javaClass.simpleName}", e2)
            }
        }
    }

    private fun bootstrap(): Session {
        val req = Request.Builder()
            .url(translatorPage)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        val (pageUrl, html) = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("取必应页面失败 HTTP ${resp.code}")
            resp.request.url to (resp.body?.string() ?: throw RuntimeException("必应页面内容为空"))
        }
        val ig = Regex("IG:\"([0-9A-Fa-f]{8,})\"").find(html)?.groupValues?.get(1)
            ?: throw RuntimeException("必应页面结构已变（未找到 IG）")
        val iid = Regex("data-iid=\"(translator\\.[0-9]+)\"").find(html)?.groupValues?.get(1)
            ?: throw RuntimeException("必应页面结构已变（未找到 IID）")
        val m = Regex("params_AbusePreventionHelper\\s*=\\s*\\[([0-9]+),\"([^\"]+)\",([0-9]+)]")
            .find(html)
            ?: throw RuntimeException("必应页面结构已变（未找到 key/token）")
        return Session(pageUrl, ig, iid, m.groupValues[1], m.groupValues[2], System.currentTimeMillis())
    }

    private fun post(text: String, to: String, from: String, s: Session): String {
        val body = FormBody.Builder()
            .add("fromLang", from)
            .add("text", text)
            .add("to", to)
            .add("token", s.token)
            .add("key", s.key)
            .build()
        val origin = s.pageUrl.newBuilder().encodedPath("/").query(null).fragment(null)
            .build().toString().removeSuffix("/")
        val url = s.pageUrl.newBuilder()
            .encodedPath("/ttranslatev3")
            .query(null)
            .addQueryParameter("isVertical", "1")
            .addQueryParameter("IG", s.ig)
            .addQueryParameter("IID", s.iid)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", s.pageUrl.toString())
            .header("Origin", origin)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val raw = client.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}：${t.take(160)}")
            if (t.isBlank()) throw RuntimeException("必应返回空响应（HTTP ${r.code}，请稍后重试）")
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
        private const val SESSION_TTL_MS = 5 * 60 * 1000L
        private const val AUTO_DETECT = "auto-detect"

        private fun newClient(): OkHttpClient = OkHttpClient.Builder()
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
    }
}
