package com.airmesh.sim

/**
 * Configuration for the optional simulator CSMA/CA model.
 *
 * The model is intentionally scaled for the event-based simulator: one
 * contention slot is a configurable number of integer virtual milliseconds,
 * not a real 802.11 microsecond slot. It demonstrates carrier sensing,
 * random backoff, contention windows, and collisions without pretending to be
 * a bit-accurate physical-layer simulation.
 *
 * @property cwMin initial contention window in slots. Must be positive.
 * @property cwMax upper bound for the contention window. Must be at least
 *   [cwMin].
 * @property slotTimeMs duration of one contention slot in integer virtual
 *   milliseconds. This is a scaled model, not real 802.11 timing.
 * @property bitrate simulated channel bitrate in bits per second.
 * @property maxRetries retries allowed after the first attempt. A value of 2
 *   permits three transmitter attempts in total.
 */
data class CsmaCaConfig(
    val cwMin: Int = 16,
    val cwMax: Int = 1024,
    val slotTimeMs: Long = 1,
    val bitrate: Long = 1_000_000,
    val maxRetries: Int = 7,
) {
    init {
        require(cwMin > 0) { "cwMin must be positive: $cwMin" }
        require(cwMax >= cwMin) { "cwMax must be at least cwMin: cwMax=$cwMax, cwMin=$cwMin" }
        require(slotTimeMs > 0) { "slotTimeMs must be positive: $slotTimeMs" }
        require(bitrate > 0) { "bitrate must be positive: $bitrate" }
        require(maxRetries >= 0) { "maxRetries must not be negative: $maxRetries" }
    }
}
