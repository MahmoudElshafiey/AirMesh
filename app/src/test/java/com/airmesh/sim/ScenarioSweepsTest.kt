package com.airmesh.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parameter values of every experiment sweep.
 *
 * Expected values are written out explicitly from the experiment definition,
 * not derived with the same formula as the production code.
 */
class ScenarioSweepsTest {

    @Test
    fun initialTtlSweepCoversOneThroughEight() {
        val scenarios = ScenarioSweeps.initialTtlSweep()

        assertEquals((1..8).toList(), scenarios.map { it.initialTtl })
        assertEquals((1..8).map(Int::toString), scenarios.map { it.parameterValue })
    }

    @Test
    fun linkLossSweepCoversZeroToHalfInTenths() {
        val scenarios = ScenarioSweeps.linkLossSweep()
        val expected = listOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.5)

        assertEquals(expected.size, scenarios.size)
        expected.forEachIndexed { index, loss ->
            assertEquals("loss[$index]", loss, scenarios[index].loss, 1e-9)
        }
    }

    @Test
    fun nodeCountSweepScalesAreaWithNodeCount() {
        val base = ScenarioSweeps.baseScenario()
        val scenarios = ScenarioSweeps.nodeCountSweep(base)
        val expectedAreas = listOf(
            65.319726, // 80 * sqrt(4 / 6)
            80.0,
            103.279556, // 80 * sqrt(10 / 6)
            126.491106, // 80 * sqrt(15 / 6)
            146.059349, // 80 * sqrt(20 / 6)
        )

        assertEquals(listOf(4, 6, 10, 15, 20), scenarios.map { it.nodeCount })
        scenarios.forEachIndexed { index, scenario ->
            assertEquals("area[$index]", expectedAreas[index], scenario.areaSize, 1e-5)
            assertEquals("range[$index]", base.radioRange, scenario.radioRange, 0.0)
            assertEquals("loss[$index]", base.loss, scenario.loss, 0.0)
            assertEquals("messages[$index]", base.messageCount, scenario.messageCount)
            assertEquals("duration[$index]", base.runDurationMs, scenario.runDurationMs)
        }
    }

    @Test
    fun speedSweepDefinesOrderedSpeedsAndConnectedArea() {
        val scenarios = ScenarioSweeps.speedSweep()

        assertEquals(listOf(0.0, 2.0, 5.0, 10.0), scenarios.map { it.minSpeed })
        scenarios.forEachIndexed { index, scenario ->
            assertEquals("speed[$index]", scenario.minSpeed, scenario.maxSpeed, 0.0)
            // 40x40 instead of the 80x80 base: the six-node grid is inside
            // radio range at t=0, so speed 0 is not an all-zero run.
            assertEquals("area[$index]", 40.0, scenario.areaSize, 1e-9)
            assertEquals("nodes[$index]", 6, scenario.nodeCount)
            assertEquals("range[$index]", 22.0, scenario.radioRange, 1e-9)
        }
    }

    @Test
    fun speedZeroDeliversForSeedsOneToThree() {
        val speedZero = ScenarioSweeps.speedSweep().first()
        assertEquals(0.0, speedZero.minSpeed, 0.0)

        (1..3).forEach { seed ->
            val metrics = speedZero.build(seed.toLong()).metrics()

            assertTrue(
                "seed $seed: expected frames on air, got ${metrics.totalFramesOnAir}",
                metrics.totalFramesOnAir > 0,
            )
            assertTrue(
                "seed $seed: expected deliveries, got ${metrics.deliveryRatio}",
                metrics.deliveryRatio > 0.0,
            )
        }
    }

    @Test
    fun csmaSweepVariesOnlyTheCsmaConfig() {
        val scenarios = ScenarioSweeps.csmaSweep()

        assertEquals(2, scenarios.size)
        assertNull(scenarios[0].csma)
        assertTrue(scenarios[1].csma is CsmaCaConfig)
        assertEquals("off", scenarios[0].parameterValue)
        assertEquals("on", scenarios[1].parameterValue)

        val expectedOn = scenarios[0].copy(
            parameterValue = "on",
            csma = CsmaCaConfig(),
        )
        assertEquals(expectedOn, scenarios[1])
    }
}
