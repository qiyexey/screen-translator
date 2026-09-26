package com.hunter.screentranslator.api

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BingWebTranslatorTest {
    private val html = """
        <main data-iid="translator.5023"></main>
        <script>var IG:"ABCD1234"; var params_AbusePreventionHelper=[123,"token",456];</script>
    """.trimIndent()
    private val translation = """[{"translations":[{"text":"你好"}]}]"""

    @Test fun postUsesFinalPageHostAfterRedirect() = runBlocking {
        MockWebServer().use { entry ->
            MockWebServer().use { regional ->
                entry.enqueue(MockResponse().setResponseCode(302)
                    .addHeader("Location", regional.url("/translator")))
                regional.enqueue(MockResponse().setBody(html))
                regional.enqueue(MockResponse().setBody(translation))

                val translator = BingWebTranslator(entry.url("/translator").toString(), OkHttpClient())
                assertEquals("你好", translator.translate("Hello", "zh", SOURCE_AUTO).getOrThrow())
                assertEquals(1, entry.requestCount)
                assertEquals("GET", regional.takeRequest().method)
                val post = regional.takeRequest()
                assertEquals("POST", post.method)
                assertEquals("/ttranslatev3", post.requestUrl?.encodedPath)
                assertEquals("zh-Hans", post.body.readUtf8().substringAfter("to=").substringBefore('&'))
                assertTrue(post.getHeader("Referer")!!.startsWith(regional.url("/translator").toString()))
            }
        }
    }

    @Test fun reusesSessionForSubsequentRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(html))
            server.enqueue(MockResponse().setBody(translation))
            server.enqueue(MockResponse().setBody(translation))
            val translator = BingWebTranslator(server.url("/translator").toString(), OkHttpClient())

            assertEquals("你好", translator.translate("Hello", "zh", SOURCE_AUTO).getOrThrow())
            assertEquals("你好", translator.translate("World", "zh", SOURCE_AUTO).getOrThrow())
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun emptySuccessBodyIsReportedAndRetriedOnce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(html))
            server.enqueue(MockResponse().setBody(""))
            server.enqueue(MockResponse().setBody(html))
            server.enqueue(MockResponse().setBody(""))
            val translator = BingWebTranslator(server.url("/translator").toString(), OkHttpClient())

            val result = translator.translate("Hello", "zh", SOURCE_AUTO)
            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("必应返回空响应"))
            assertEquals(4, server.requestCount)
        }
    }
}
