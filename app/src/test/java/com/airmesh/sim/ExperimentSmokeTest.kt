package com.airmesh.sim

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast smoke coverage for the experiment runner and CSV writer.
 *
 * Every sweep runs with only three seeds and an 8 s scenario duration. The
 * full 30-seed experiments are intentionally not part of the normal test suite.
 */
class ExperimentSmokeTest {

    @Test
    fun sweepsWriteHeaderAndExpectedRowCounts() {
        val startedAt = System.nanoTime()
        val base = ScenarioSweeps.baseScenario().copy(runDurationMs = 8_000)
        val tempDir = Files.createTempDirectory("airmesh-experiments").toFile()

        try {
            for ((fileName, scenarios) in ScenarioSweeps.all(base)) {
                val seeds = 1..3
                val results = scenarios.map { scenario ->
                    ExperimentRunner.run(scenario = scenario, seeds = seeds)
                }
                val file = File(tempDir, "$fileName.csv")
                CsvWriter.write(results, file)

                val lines = file.readLines()
                assertEquals("$fileName header", EXPECTED_HEADER, lines.first())
                assertEquals(
                    "$fileName: header plus 3 seed rows plus 1 summary row per scenario",
                    1 + scenarios.size * (seeds.count() + 1),
                    lines.size,
                )

                val summaryRows = lines.drop(1).count { line -> line.split(",")[3].isEmpty() }
                assertEquals("$fileName: one summary row per scenario", scenarios.size, summaryRows)
            }
        } finally {
            tempDir.deleteRecursively()
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("smoke run took ${elapsedMs}ms, expected under 10s", elapsedMs < 10_000)
    }

    private companion object {
        const val EXPECTED_HEADER =
            "scenarioName,parameterName,parameterValue,seed," +
                "deliveryRatio,averageLatencyMs,maxLatencyMs,averageHopCount,totalFramesOnAir," +
                "duplicatesDropped,collisions,channelBusyRatio,framesDroppedAfterMaxRetries," +
                "deliveryRatioStdDev,averageLatencyMsStdDev,maxLatencyMsStdDev,averageHopCountStdDev," +
                "totalFramesOnAirStdDev,duplicatesDroppedStdDev,collisionsStdDev,channelBusyRatioStdDev," +
                "framesDroppedAfterMaxRetriesStdDev"
    }
}
