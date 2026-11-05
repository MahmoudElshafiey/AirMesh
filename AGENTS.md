# AirMesh

Android app: an infrastructure-less multi-hop mesh messenger for disaster scenarios
(cellular network down). Phones talk to each other over Wi-Fi Direct (or a hotspot
fallback). University project for a Data Communications course: the goal is to
implement frame design, CRC error detection, CSMA/CA-style backoff, flooding with
TTL, deduplication, and store-and-forward FROM SCRATCH, transparently.
Do NOT use Nearby Connections, Bridgefy, or any ready-made mesh SDK.

## Stack
- Kotlin, Jetpack Compose, Gradle Kotlin DSL, minSdk 29
- Room (store-and-forward queue), Kotlin Coroutines/Flow
- Wi-Fi Direct via WifiP2pManager + sockets

## Package layout
- protocol/  : frame encode/decode, CRC-16 (pure Kotlin, NO Android imports)
- routing/   : flooding, TTL, dedup cache, store-and-forward logic (pure Kotlin, NO Android imports)
- transport/ : Transport interface, WifiDirectTransport, FakeTransport (simulator)
- storage/   : Room entities/DAOs
- ui/        : Compose screens

## Rules
- One task = one goal. Do not implement things I did not ask for.
- After every task run `./gradlew test` (and `./gradlew assembleDebug` if Android code changed). Do not say "done" until they pass.
- protocol/ and routing/ must stay free of Android dependencies so they are unit-testable on the JVM.
- Every public function in protocol/ and routing/ needs unit tests.
- If a build or runtime error occurs, read the actual error message and the relevant file. Do not guess.
- Keep code simple and readable; add short comments explaining protocol decisions (this will be presented in a course).
- Make small commits with clear messages after each passing task.
- Never add "Generated with" or "Co-Authored-By" trailers to commit messages; use the repository's configured git identity.
- Android limitation: a device cannot be in two Wi-Fi Direct groups at once. Multi-hop is achieved by
  connecting to groups in turns + store-and-forward. Real multi-hop is only tested with 2-3 phones;
  large multi-hop tests run in the FakeTransport simulator.