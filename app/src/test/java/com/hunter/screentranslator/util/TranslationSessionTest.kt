package com.hunter.screentranslator.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSessionTest {
    private val config = mapOf("engine" to "openai", "target_lang" to "zh")

    @Test fun unchangedConfigurationKeepsCurrentRequest() {
        val session = TranslationSession()
        assertTrue(session.updateConfiguration(config))
        val token = session.generation
        assertFalse(session.updateConfiguration(config.toMap()))
        assertTrue(session.isCurrent(token, config))
    }

    @Test fun changedFieldsRejectInFlightResultsBeforeNextTick() {
        listOf("engine", "target_lang", "source_lang", "api_key", "model", "base_url", "live_roi",
            "local_model_modified").forEach { key ->
            val session = TranslationSession()
            session.updateConfiguration(config)
            val token = session.generation
            val changed = config + (key to "new-value")
            assertFalse(session.isCurrent(token, changed))
            assertTrue(session.updateConfiguration(changed))
            assertFalse(session.isCurrent(token, changed))
            assertTrue(session.isCurrent(session.generation, changed))
        }
    }

    @Test fun pausePickerStopOrManualRetryInvalidateOldRequest() {
        val session = TranslationSession()
        session.updateConfiguration(config)
        val token = session.generation
        session.invalidate()
        assertFalse(session.isCurrent(token, config))
        assertTrue(session.isCurrent(session.generation, config))
    }
}
