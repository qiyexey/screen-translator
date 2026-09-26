package com.hunter.screentranslator.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationRetryTest {
    @Test fun firstAttemptIsImmediate() {
        assertTrue(TranslationRetry().canAttempt(0))
    }

    @Test fun exponentialDelayIsCapped() {
        val retry = TranslationRetry()
        listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L).forEach { delay ->
            assertEquals(delay, retry.failed(100))
            assertEquals(100 + delay, retry.nextAttemptAt)
        }
    }

    @Test fun staticFrameCanBeRetriedAfterDeadline() {
        val retry = TranslationRetry()
        retry.failed(500)
        assertFalse(retry.canAttempt(1_499))
        assertTrue(retry.canAttempt(1_500))
    }

    @Test fun successOrManualRetryResetsBackoff() {
        val retry = TranslationRetry()
        repeat(5) { retry.failed(10_000) }
        retry.reset()
        assertTrue(retry.canAttempt(10_001))
        assertEquals(0, retry.failures)
        assertEquals(1_000L, retry.failed(10_001))
    }
}
