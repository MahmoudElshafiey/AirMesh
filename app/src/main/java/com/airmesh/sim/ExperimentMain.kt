package com.airmesh.sim

import java.io.File

/**
 * Runs every simulator sweep with 30 seeds and writes the CSV files under
 * `results/`.
 *
 * This is intentionally outside the unit-test suite: start it on demand with
 * `./gradlew :app:runExperiments`.
 */
fun main() {
    val seeds = 1..30
    val resultsDir = File("results")

    for ((fileName, scenarios) in ScenarioSweeps.all()) {
        println("Running $fileName: ${scenarios.size} scenarios x ${seeds.count()} seeds")
        val results = scenarios.map { scenario ->
            ExperimentRunner.run(scenario = scenario, seeds = seeds)
        }
        val output = File(resultsDir, "$fileName.csv")
        CsvWriter.write(results, output)
        println("Wrote ${output.path}")
    }
}
