package com.airmesh.sim

/**
 * Unordered identity of one bidirectional link.
 *
 * The lower node id is always stored first, so `(1, 2)` and `(2, 1)` map to the
 * same key.
 */
internal data class LinkKey(
    val low: Int,
    val high: Int,
) {
    init {
        require(low < high) { "link key must have low < high: ($low, $high)" }
    }

    companion object {
        fun of(a: Int, b: Int): LinkKey {
            require(a != b) { "a node cannot link to itself: $a" }
            return if (a < b) LinkKey(a, b) else LinkKey(b, a)
        }
    }
}

/**
 * One bidirectional simulated radio link.
 *
 * Both directions use the same [loss] probability and one-way [latencyMs].
 */
internal data class SimLink(
    val key: LinkKey,
    val loss: Double,
    val latencyMs: Long,
) {
    init {
        require(loss in 0.0..1.0) { "loss must be in 0.0..1.0: $loss" }
        require(latencyMs >= 0) { "latencyMs must not be negative: $latencyMs" }
    }
}
