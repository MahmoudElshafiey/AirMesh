package com.airmesh.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the aggregate helpers and the per-seed run contract.
 */
class ExperimentRunnerTest {

    @Test
    fun statsComputesMeanAndSampleStandardDeviation() {
        // Hand-computed: mean = 6 / 3 = 2, variance = 2 / (3 - 1) = 1.
        val stats = ExperimentRunner.stats(listOf(1.0, 2.0, 3.0))

        assertEquals(2.0, stats.mean, 1e-12)
        assertEquals(1.0, stats.sampleStdDev, 1e-12)
    }

    @Test
    fun statsOfSingleValueHasZeroStdDev() {
        val stats = ExperimentRunner.stats(listOf(42.0))

        assertEquals(42.0, stats.mean, 0.0)
        assertEquals(0.0, stats.sampleStdDev, 0.0)
    }

    @Test
    fun emptySeedRangeIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ExperimentRunner.run(ScenarioSweeps.baseScenario(), IntRange.EMPTY)
        }

        assertTrue(error.message!!.contains("at least one seed"))
    }

    @Test
    fun sameScenarioAndSeedsAreReproducible() {
        val scenario = ScenarioSweeps.baseScenario().copy(runDurationMs = 10_000)

        val first = ExperimentRunner.run(scenario = scenario, seeds = 1..3)
        val second = ExperimentRunner.run(scenario = scenario, seeds = 1..3)

        assertEquals(listOf(1, 2, 3), first.runs.map { it.seed })
        assertEquals(first.runs, second.runs)
        assertEquals(first.aggregates, second.aggregates)
    }
}
