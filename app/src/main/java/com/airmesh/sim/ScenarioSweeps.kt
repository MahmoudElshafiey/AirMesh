package com.airmesh.sim

import java.util.Locale
import kotlin.math.sqrt

/**
 * Scenario sweeps for the simulator experiments.
 *
 * Every sweep varies exactly one parameter and copies everything else from
 * [baseScenario]. The base matches the existing six-node mobility scenario:
 * 80x80 area, radio range 22, 20% loss, 5 ms latency, speed 2..5, 12 messages
 * over 60 s, TTL 8, and CSMA/CA off.
 */
object ScenarioSweeps {

    /** Ten nodes per side is enough for the dense CSMA comparison. */
    private const val DENSE_NODE_COUNT = 15
    private const val DENSE_AREA_SIZE = 40.0
    private const val DENSE_MESSAGE_COUNT = 60

    fun baseScenario(): Scenario = Scenario(
        name = "base",
        nodeCount = 6,
        areaSize = 80.0,
        radioRange = 22.0,
        loss = 0.2,
        latencyMs = 5,
        minSpeed = 2.0,
        maxSpeed = 5.0,
        messageCount = 12,
        runDurationMs = 60_000,
        initialTtl = 8,
        csma = null,
    )

    /** Initial TTL 1..8. */
    fun initialTtlSweep(base: Scenario = baseScenario()): List<Scenario> =
        (1..8).map { ttl ->
            base.copy(
                name = "ttl",
                parameterName = "initialTtl",
                parameterValue = ttl.toString(),
                initialTtl = ttl,
            )
        }

    /** Link loss 0.0, 0.1, ... 0.5. */
    fun linkLossSweep(base: Scenario = baseScenario()): List<Scenario> =
        (0..5).map { tenth ->
            val loss = tenth / 10.0
            base.copy(
                name = "loss",
                parameterName = "linkLoss",
                parameterValue = decimal(loss),
                loss = loss,
            )
        }

    /**
     * 4, 6, 10, 15, 20 nodes. The area side grows with the square root of the
     * node count so node density stays proportional to the base scenario.
     */
    fun nodeCountSweep(base: Scenario = baseScenario()): List<Scenario> =
        listOf(4, 6, 10, 15, 20).map { nodeCount ->
            val scaledSide = base.areaSize * sqrt(nodeCount.toDouble() / base.nodeCount.toDouble())
            base.copy(
                name = "nodeCount",
                parameterName = "nodeCount",
                parameterValue = nodeCount.toString(),
                nodeCount = nodeCount,
                areaSize = scaledSide,
            )
        }

    /** Speed 0, 2, 5, 10 units/s; min and max are equal for a fixed speed. */
    fun speedSweep(base: Scenario = baseScenario()): List<Scenario> =
        listOf(0.0, 2.0, 5.0, 10.0).map { speed ->
            base.copy(
                name = "speed",
                parameterName = "speed",
                parameterValue = decimal(speed),
                minSpeed = speed,
                maxSpeed = speed,
            )
        }

    /** CSMA/CA off vs on for a dense 15-node, small-area, 60-message run. */
    fun csmaSweep(base: Scenario = baseScenario()): List<Scenario> {
        val dense = base.copy(
            name = "csma",
            nodeCount = DENSE_NODE_COUNT,
            areaSize = DENSE_AREA_SIZE,
            messageCount = DENSE_MESSAGE_COUNT,
        )
        return listOf(
            dense.copy(parameterName = "csma", parameterValue = "off", csma = null),
            dense.copy(parameterName = "csma", parameterValue = "on", csma = CsmaCaConfig()),
        )
    }

    /** All five sweeps keyed by their output file name. */
    fun all(base: Scenario = baseScenario()): Map<String, List<Scenario>> = linkedMapOf(
        "ttl" to initialTtlSweep(base),
        "loss" to linkLossSweep(base),
        "node-count" to nodeCountSweep(base),
        "speed" to speedSweep(base),
        "csma" to csmaSweep(base),
    )

    private fun decimal(value: Double): String =
        String.format(Locale.ROOT, "%.1f", value)
}
