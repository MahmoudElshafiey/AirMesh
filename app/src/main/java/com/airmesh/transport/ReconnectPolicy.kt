package com.airmesh.transport

/**
 * Backoff schedule for repeated TCP connect attempts from a Wi-Fi Direct client.
 *
 * The first three failures wait 1 s, 2 s and 3 s; afterwards the delay stays at
 * 5 s. The clock is injectable so tests can assert absolute retry times without
 * real delays.
 */
class ReconnectPolicy(private val clock: () -> Long = System::currentTimeMillis) {

    private var failures = 0
    private var lastDelayMs: Long? = null

    /** Records one failed attempt and returns how long (ms) to wait before the next one. */
    fun recordFailure(): Long {
        failures += 1
        val delayMs = if (failures <= MAX_STEPS) failures * STEP_MS else MAX_DELAY_MS
        lastDelayMs = delayMs
        return delayMs
    }

    /** Absolute time at which the next attempt should run after a recorded failure. */
    fun nextAttemptTime(): Long = clock() + (lastDelayMs ?: 0L)

    /** Clears the schedule; the next failure restarts backoff from [STEP_MS]. */
    fun reset() {
        failures = 0
        lastDelayMs = null
    }

    companion object {
        /** Delay step for the first three retries. */
        const val STEP_MS = 1_000L

        /** Retry delay cap after the first three failures. */
        const val MAX_DELAY_MS = 5_000L

        private const val MAX_STEPS = 3
    }
}
