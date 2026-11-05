# AirMesh

Android mesh messenger for infrastructure-less, disaster-scenario communication.
Phones exchange flooded frames over Wi-Fi Direct with store-and-forward; the
protocol and routing layers are pure Kotlin and unit-testable on the JVM.

## Simulator experiments

The simulator can run reproducible parameter sweeps and write the results as
CSV. Run every sweep with 30 seeds and write the files to `results/`:

```bash
./gradlew :app:runExperiments
```

On Windows:

```powershell
.\gradlew.bat :app:runExperiments
```

The task writes:

```text
results/ttl.csv
results/loss.csv
results/node-count.csv
results/speed.csv
results/csma.csv
```

Each file has one row per `(scenario, seed)` and one summary row per scenario.
The summary row has a blank `seed` column, the mean in each metric column, and
the sample standard deviation in the matching `*StdDev` column. Decimals use a
dot and fields are comma-separated.

Run the fast smoke test (3 seeds, 8 s scenarios) with:

```bash
./gradlew :app:testDebugUnitTest --tests "com.airmesh.sim.ExperimentSmokeTest"
```

The full 30-seed run is deliberately not part of the normal test suite.

## Build and test

```bash
./gradlew test
./gradlew assembleDebug
```
