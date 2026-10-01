package com.hunter.screentranslator.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FallbackTranslatorTest {

    private class Fake(private val result: () -> Result<String>) : Translator {
        var calls = 0
        override suspend fun translate(text: String, targetLang: String, sourceLang: String): Result<String> {
            calls++
            return result()
        }
    }

    @Before
    fun setUp() {
        FallbackTranslator.clock = { 10_000L }
        FallbackTranslator.log = {}
        FallbackTranslator.reset()
    }

    @Test
    fun primarySuccessDoesNotTouchFallback() = runBlocking {
        val primary = Fake { Result.success("主") }
        val backup = Fake { Result.success("备") }
        val t = FallbackTranslator(primary, backup, "备用", fallbackVision = false)
        assertEquals("主", t.translate("hi", "zh").getOrNull())
        assertEquals(0, backup.calls)
    }

    @Test
    fun primaryFailureUsesFallbackThenCoolsDown() = runBlocking {
        val primary = Fake { Result.failure(RuntimeException("必应挂了")) }
        val backup = Fake { Result.success("备") }
        val t = FallbackTranslator(primary, backup, "备用", fallbackVision = false)

        assertEquals("备", t.translate("a", "zh").getOrNull())
        assertEquals(1, primary.calls)
        // 熔断期内不再先试主引擎
        assertEquals("备", t.translate("b", "zh").getOrNull())
        assertEquals(1, primary.calls)
        assertEquals(2, backup.calls)
        assertEquals("备用", FallbackTranslator.recentFallbackName())
    }

    @Test
    fun bothFailReturnsFallbackError() = runBlocking {
        val primary = Fake { Result.failure(RuntimeException("主错")) }
        val backup = Fake { Result.failure(RuntimeException("备错")) }
        val r = FallbackTranslator(primary, backup, "备用", fallbackVision = false).translate("a", "zh")
        assertTrue(r.isFailure)
        assertEquals("备错", r.exceptionOrNull()?.message)
    }

    @Test
    fun cancellationIsNotTreatedAsFailure() = runBlocking {
        val primary = Fake { Result.failure(CancellationException("取消")) }
        val backup = Fake { Result.success("备") }
        try {
            FallbackTranslator(primary, backup, "备用", fallbackVision = false).translate("a", "zh")
            fail("取消应当往上抛")
        } catch (_: CancellationException) {
        }
        assertEquals(0, backup.calls)
    }
}
