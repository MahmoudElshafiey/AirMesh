package com.airmesh.sim

import java.io.File
import java.util.Locale

/**
 * Writes experiment results as CSV.
 *
 * One row per `(scenario, seed)` contains the raw [SimMetrics]. One summary row
 * per scenario follows, with a blank seed column, the mean in each metric
 * column, and the sample standard deviation in the matching `*StdDev` column.
 *
 * The delimiter is a comma and decimals always use a dot, independent of the
 * host locale.
 */
object CsvWriter {

    private class Metric(
        val name: String,
        val value: (SimMetrics) -> Double,
        val stats: (ExperimentRunner.Aggregates) -> ExperimentRunner.MetricStats,
    )

    private val metrics = listOf(
        Metric("deliveryRatio", { it.deliveryRatio }, { it.deliveryRatio }),
        Metric("averageLatencyMs", { it.averageLatencyMs }, { it.averageLatencyMs }),
        Metric("maxLatencyMs", { it.maxLatencyMs.toDouble() }, { it.maxLatencyMs }),
        Metric("averageHopCount", { it.averageHopCount }, { it.averageHopCount }),
        Metric("totalFramesOnAir", { it.totalFramesOnAir.toDouble() }, { it.totalFramesOnAir }),
        Metric("duplicatesDropped", { it.duplicatesDropped.toDouble() }, { it.duplicatesDropped }),
        Metric("collisions", { it.collisions.toDouble() }, { it.collisions }),
        Metric("channelBusyRatio", { it.channelBusyRatio }, { it.channelBusyRatio }),
        Metric(
            "framesDroppedAfterMaxRetries",
            { it.framesDroppedAfterMaxRetries.toDouble() },
            { it.framesDroppedAfterMaxRetries },
        ),
    )

    private val header: String = buildList {
        addAll(listOf("scenarioName", "parameterName", "parameterValue", "seed"))
        addAll(metrics.map { it.name })
        addAll(metrics.map { "${it.name}StdDev" })
    }.joinToString(",")

    /**
     * Writes [results] to [file], creating parent directories when needed.
     * Existing content is replaced.
     */
    fun write(results: List<ExperimentRunner.ScenarioResult>, file: File) {
        file.parentFile?.mkdirs()
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine(header)
            for (result in results) {
                val scenario = result.scenario
                for (run in result.runs) {
                    writer.appendLine(
                        row(
                            scenario = scenario,
                            seed = run.seed.toString(),
                            metrics = run.metrics,
                            aggregates = null,
                        ),
                    )
                }
                writer.appendLine(
                    row(
                        scenario = scenario,
                        seed = "",
                        metrics = null,
                        aggregates = result.aggregates,
                    ),
                )
            }
        }
    }

    private fun row(
        scenario: Scenario,
        seed: String,
        metrics: SimMetrics?,
        aggregates: ExperimentRunner.Aggregates?,
    ): String {
        val fields = mutableListOf(
            escape(scenario.name),
            escape(scenario.parameterName),
            escape(scenario.parameterValue),
            seed,
        )
        for (metric in this.metrics) {
            val value =
                if (metrics != null) {
                    metric.value(metrics)
                } else {
                    metric.stats(requireNotNull(aggregates)).mean
                }
            fields += format(value)
        }
        for (metric in this.metrics) {
            fields += if (aggregates != null) {
                format(metric.stats(aggregates).sampleStdDev)
            } else {
                ""
            }
        }
        return fields.joinToString(",")
    }

    /** Fixed six decimals with a dot, independent of the JVM default locale. */
    private fun format(value: Double): String =
        String.format(Locale.ROOT, "%.6f", value)

    /** Minimal RFC 4180 quoting for names that contain delimiters or quotes. */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
