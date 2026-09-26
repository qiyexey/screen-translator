package com.hunter.screentranslator.util

/** Uses monotonic time supplied by the caller, so wall-clock changes cannot stall retries. */
class TranslationRetry {
    var failures: Int = 0
        private set
    var nextAttemptAt: Long = 0
        private set

    fun canAttempt(now: Long): Boolean = now >= nextAttemptAt

    fun failed(now: Long): Long {
        failures = (failures + 1).coerceAtMost(4)
        val delay = 1_000L shl (failures - 1)
        nextAttemptAt = now + delay
        return delay
    }

    fun reset() {
        failures = 0
        nextAttemptAt = 0
    }
}
