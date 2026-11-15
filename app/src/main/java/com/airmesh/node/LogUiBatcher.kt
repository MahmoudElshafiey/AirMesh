package com.airmesh.node

/**
 * Paces how often the event log is republished to the UI (TASK U4 part 4).
 *
 * [EventLog.entries] emits on every logged line; during a discovery burst that can be many
 * times per second. The service uses this batcher to publish at most once per
 * [MIN_PUBLISH_INTERVAL_MS]:
 * - [canPublish] is true until [markPublished] opens the next window, so a quiet log updates
 *   the UI immediately;
 * - [waitMs] tells the caller how long to wait before publishing again. The service delays by
 *   that amount inside its collector; if newer lines arrive meanwhile the delay is cancelled
 *   and re-evaluated against the same fixed deadline, so a burst converges on one publish per
 *   window instead of dropping updates (no starvation).
 *
 * Pure Kotlin with an injectable clock so the batching is unit-testable (TASK U4 part 4).
 */
class LogUiBatcher(private val clock: () -> Long = System::currentTimeMillis) {

    companion object {
        /** The UI sees at most one log update per this interval. */
        const val MIN_PUBLISH_INTERVAL_MS = 250L
    }

    private var nextPublishMs = 0L // the first publish is allowed immediately

    /** True when publishing now would not violate the minimum interval. */
    fun canPublish(): Boolean = clock() >= nextPublishMs

    /** How long to wait before [canPublish] becomes true (0 if it already is). */
    fun waitMs(): Long = (nextPublishMs - clock()).coerceAtLeast(0L)

    /** Opens the next window: no publish until [MIN_PUBLISH_INTERVAL_MS] from now. */
    fun markPublished() {
        nextPublishMs = clock() + MIN_PUBLISH_INTERVAL_MS
    }
}
