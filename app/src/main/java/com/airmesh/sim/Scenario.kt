package com.airmesh.sim

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * One reproducible simulator scenario.
 *
 * A scenario describes the topology, radio model, mobility, traffic, TTL, and
 * optional CSMA/CA configuration. [build] turns it into a fully configured
 * [SimNetwork] for one seed, originates the scenario traffic, and runs the
 * virtual clock to [runDurationMs].
 *
 * @property name scenario/sweep name written to CSV.
 * @property parameterName name of the swept parameter, for example `linkLoss`.
 * @property parameterValue value of the swept parameter, for example `0.2`.
 */
data class Scenario(
    val name: String,
    val parameterName: String = "",
    val parameterValue: String = "",
    val nodeCount: Int,
    val areaSize: Double,
    val radioRange: Double,
    val loss: Double,
    val latencyMs: Long,
    val minSpeed: Double,
    val maxSpeed: Double,
    val messageCount: Int,
    val runDurationMs: Long,
    val initialTtl: Int,
    /** Null means CSMA/CA is OFF; a config enables the optional model. */
    val csma: CsmaCaConfig? = null,
) {

    init {
        require(nodeCount > 0) { "nodeCount must be positive: $nodeCount" }
        require(areaSize > 0.0) { "areaSize must be positive: $areaSize" }
        require(radioRange > 0.0) { "radioRange must be positive: $radioRange" }
        require(loss in 0.0..1.0) { "loss must be in 0.0..1.0: $loss" }
        require(latencyMs >= 0) { "latencyMs must not be negative: $latencyMs" }
        require(minSpeed >= 0.0) { "minSpeed must not be negative: $minSpeed" }
        require(maxSpeed >= minSpeed) { "maxSpeed must be at least minSpeed: $maxSpeed" }
        require(messageCount >= 0) { "messageCount must not be negative: $messageCount" }
        require(runDurationMs > 0) { "runDurationMs must be positive: $runDurationMs" }
        require(initialTtl in 1..0xFF) { "initialTtl must be in 1..255: $initialTtl" }
    }

    /**
     * Builds, populates, and runs this scenario for [seed].
     *
     * Positions use a deterministic grid so the only seed-dependent changes
     * are mobility and link loss. Messages are originated round-robin over the
     * nodes and spread evenly across the run.
     */
    fun build(seed: Long): SimNetwork {
        val net = SimNetwork(seed = seed, initialTtl = initialTtl)

        // Square-ish grid: keeps node density comparable as nodeCount changes.
        val columns = ceil(sqrt(nodeCount.toDouble())).toInt()
        val rows = ceil(nodeCount.toDouble() / columns).toInt()
        repeat(nodeCount) { index ->
            val column = index % columns
            val row = index / columns
            val x = areaSize * (column + 0.5) / columns
            val y = areaSize * (row + 0.5) / rows
            net.addNode(index + 1, x = x, y = y)
        }

        net.configureRadioRange(
            range = radioRange,
            loss = loss,
            latencyMs = latencyMs,
        )
        net.enableMobility(
            width = areaSize,
            height = areaSize,
            minSpeed = minSpeed,
            maxSpeed = maxSpeed,
            stepMs = 1_000,
        )

        // CSMA/CA must be enabled before the first frame is transmitted.
        csma?.let { net.enableCsmaCa(it) }

        repeat(messageCount) { index ->
            net.originate(
                from = 1 + (index % nodeCount),
                payload = ByteArray(4) { index.toByte() },
                at = index.toLong() * runDurationMs / messageCount,
            )
        }
        net.runUntil(runDurationMs)
        return net
    }
}
