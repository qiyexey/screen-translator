package com.hunter.screentranslator.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineReadinessTest {
    @Test fun bingNeedsNoCredentials() {
        assertEquals(EngineReadiness.Ready, engineReadiness(TranslationEngine.BING_WEB) { "" })
    }

    @Test fun localModelMustBeComplete() {
        assertTrue(engineReadiness(TranslationEngine.HYMT_LOCAL) { "" } is EngineReadiness.NotReady)
        assertEquals(EngineReadiness.Ready, engineReadiness(TranslationEngine.HYMT_LOCAL, true) { "" })
    }

    @Test fun baiduRequiresBothFields() {
        assertRequiredFields(TranslationEngine.BAIDU, "baidu_app_id", "baidu_key")
    }

    @Test fun doubaoRequiresBothFields() {
        assertRequiredFields(TranslationEngine.DOUBAO, "doubao_api_key", "doubao_model")
    }

    @Test fun otherCloudEnginesRequireNonBlankCredentials() {
        val engines = TranslationEngine.entries.filter { !it.keyless }
        engines.forEach { engine ->
            assertTrue(engineReadiness(engine) { "  " } is EngineReadiness.NotReady)
            assertEquals(EngineReadiness.Ready, engineReadiness(engine) { "configured" })
        }
    }

    private fun assertRequiredFields(engine: TranslationEngine, first: String, second: String) {
        listOf(emptyMap(), mapOf(first to "value"), mapOf(second to "value"),
            mapOf(first to "value", second to " ")).forEach { values ->
            assertTrue(engineReadiness(engine) { values[it].orEmpty() } is EngineReadiness.NotReady)
        }
        val values = mapOf(first to "value", second to "value")
        assertEquals(EngineReadiness.Ready, engineReadiness(engine) { values[it].orEmpty() })
    }
}
