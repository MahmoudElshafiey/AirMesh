package com.airmesh.sim

import kotlin.random.Random

/**
 * Per-node 802.11-style contention window policy.
 *
 * A failed transmission doubles the window up to [cwMax]. A successful
 * transmission resets it to [cwMin]. The random backoff is drawn uniformly
 * from `[0, current)`, so `cwMin = 1` means no effective random backoff.
 */
internal class ContentionWindow(
    val cwMin: Int,
    val cwMax: Int,
) {
    init {
        require(cwMin > 0) { "cwMin must be positive: $cwMin" }
        require(cwMax >= cwMin) { "cwMax must be at least cwMin: cwMax=$cwMax, cwMin=$cwMin" }
    }

    /** Current window size in slots. */
    var current: Int = cwMin
        private set

    /** Draws a fresh backoff uniformly from `[0, current)`. */
    fun nextBackoff(random: Random): Int = random.nextInt(current)

    /** Doubles the window after a collision, capped at [cwMax]. */
    fun onCollision() {
        current =
            if (current > cwMax / 2) {
                cwMax
            } else {
                current * 2
            }
    }

    /** Resets the window after a successful transmission. */
    fun onSuccess() {
        current = cwMin
    }
}
