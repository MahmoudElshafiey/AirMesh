package com.airmesh.sim

import kotlin.math.sqrt

/**
 * Runs one [Scenario] for a range of seeds and aggregates the results.
 *
 * Every seed gets a fresh [SimNetwork], so runs are independent and
 * reproducible: the same scenario and seed always produce the same metrics.
 */
object ExperimentRunner {

    /** Metrics from one scenario run with one seed. */
    data class SeedResult(
        val seed: Int,
        val metrics: SimMetrics,
    )

    /** Mean and sample standard deviation (n - 1 denominator) of one metric. */
    data class MetricStats(
        val mean: Double,
        val sampleStdDev: Double,
    )

    /** Aggregates for every metric reported in the CSV. */
    data class Aggregates(
        val deliveryRatio: MetricStats,
        val averageLatencyMs: MetricStats,
        val maxLatencyMs: MetricStats,
        val averageHopCount: MetricStats,
        val totalFramesOnAir: MetricStats,
        val duplicatesDropped: MetricStats,
        val collisions: MetricStats,
        val channelBusyRatio: MetricStats,
        val framesDroppedAfterMaxRetries: MetricStats,
    )

    /** Per-seed results plus the aggregate row for one scenario. */
    data class ScenarioResult(
        val scenario: Scenario,
        val runs: List<SeedResult>,
        val aggregates: Aggregates,
    )

    /**
     * Runs [scenario] once per seed in [seeds] and returns the raw metrics plus
     * the aggregate summary.
     */
    fun run(scenario: Scenario, seeds: IntRange): ScenarioResult {
        require(!seeds.isEmpty()) { "at least one seed is required" }
        val runs = seeds.map { seed ->
            SeedResult(
                seed = seed,
                metrics = scenario.build(seed = seed.toLong()).metrics(),
            )
        }
        return ScenarioResult(
            scenario = scenario,
            runs = runs,
            aggregates = aggregate(runs),
        )
    }

    private fun aggregate(runs: List<SeedResult>): Aggregates = Aggregates(
        deliveryRatio = stats(runs.map { it.metrics.deliveryRatio }),
        averageLatencyMs = stats(runs.map { it.metrics.averageLatencyMs }),
        maxLatencyMs = stats(runs.map { it.metrics.maxLatencyMs.toDouble() }),
        averageHopCount = stats(runs.map { it.metrics.averageHopCount }),
        totalFramesOnAir = stats(runs.map { it.metrics.totalFramesOnAir.toDouble() }),
        duplicatesDropped = stats(runs.map { it.metrics.duplicatesDropped.toDouble() }),
        collisions = stats(runs.map { it.metrics.collisions.toDouble() }),
        channelBusyRatio = stats(runs.map { it.metrics.channelBusyRatio }),
        framesDroppedAfterMaxRetries = stats(runs.map { it.metrics.framesDroppedAfterMaxRetries.toDouble() }),
    )

    /** Sample standard deviation; a single sample has zero spread. */
    internal fun stats(values: List<Double>): MetricStats {
        require(values.isNotEmpty()) { "at least one value is required" }
        val mean = values.average()
        val variance =
            if (values.size <= 1) {
                0.0
            } else {
                values.sumOf { value -> (value - mean) * (value - mean) } / (values.size - 1)
            }
        return MetricStats(mean = mean, sampleStdDev = sqrt(variance))
    }
}
