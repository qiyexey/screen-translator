package com.hunter.screentranslator.util

/** In-flight work may commit only into the configuration and control generation that started it. */
class TranslationSession {
    private var configuration: Map<String, Any?>? = null
    var generation: Long = 0
        private set

    fun updateConfiguration(value: Map<String, Any?>): Boolean {
        if (configuration == value) return false
        configuration = value
        invalidate()
        return true
    }

    fun invalidate() {
        generation++
    }

    fun isCurrent(token: Long, value: Map<String, Any?>): Boolean =
        token == generation && configuration == value
}
