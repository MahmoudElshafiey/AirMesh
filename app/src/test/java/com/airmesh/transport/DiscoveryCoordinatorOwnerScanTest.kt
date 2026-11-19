package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the owner merge-scan pacing through [DiscoveryCoordinator] (TASK S1 part 3):
 * an empty owner scans every 15 s on strategy 2, an owner with clients once per 90 s cycle in
 * the 8 s window, the supervisor's FreshScanBurst reaches the coordinator, and a service cycle
 * that never completes is released instead of holding the radio forever. The directory loop
 * ticks every 5 s; the tests drive [DiscoveryCoordinator.tick] on the same grid.
 */
class DiscoveryCoordinatorOwnerScanTest {

    /** Records scan calls; chained variants report immediate success (default bodies). */
    private open class RecordingBackend : DiscoveryBackend {
        var discoverCount = 0
            private set
        override fun clearServiceRequests() {}
        override fun addServiceRequest() {}
        override fun discoverServices() { discoverCount++ }
        override fun discoverPeers() {}
    }

    /**
     * Like [RecordingBackend], but can swallow the addServiceRequest callback: the chained cycle
     * then stalls BEFORE the scan starts, so no silence window is ever opened and only the stuck
     * watchdog can release it — the exact failure seen on device (one strategy line, then silence).
     */
    private class StuckAddBackend : RecordingBackend() {
        var stuck = false
        override fun addServiceRequest(onDone: (Boolean, Int) -> Unit) {
            if (stuck) return // the callback never comes back; the chain stalls here
            super.addServiceRequest(onDone)
        }
    }

    private class OwnerSetup(backend: DiscoveryBackend) {
        var now = 0L
        val policy = OwnerScanPolicy()
        val logs = mutableListOf<String>()
        // The production owner plan (MeshService): starts at strategy 2, order 2,4,3,1.
        val coordinator = DiscoveryCoordinator(
            backend = backend,
            log = { logs += it },
            strategySelector = DiscoveryStrategySelector.forService(),
            clock = { now },
        )

        fun gateFor(clients: Int) = { t: Long ->
            policy.shouldScan(clients = clients, mergeEnabled = true, mergeInProgress = false, nowMs = t)
        }
    }

    /** Drives ticks on the 5 s directory grid and records when a scan was issued. */
    private fun scanTimes(setup: OwnerSetup, backend: RecordingBackend, fromMs: Long, toMs: Long): List<Long> {
        val times = mutableListOf<Long>()
        for (t in fromMs..toMs step 5_000L) {
            setup.now = t
            val before = backend.discoverCount
            setup.coordinator.tick(t, serviceWanted = true)
            if (backend.discoverCount > before) times += t
        }
        return times
    }

    @Test
    fun `an empty owner scans every 15 seconds on strategy 2`() {
        val backend = RecordingBackend()
        val setup = OwnerSetup(backend)
        setup.coordinator.ownerScanGate = setup.gateFor(clients = 0)

        assertEquals("one scan per 15 s", listOf(0L, 15_000L, 30_000L, 45_000L, 60_000L), scanTimes(setup, backend, 0L, 60_000L))
        assertEquals("the production owner plan stays on strategy 2", 2, setup.coordinator.currentStrategy)
    }

    @Test
    fun `an owner with clients scans once per 90 s cycle inside the 8 s window`() {
        val backend = RecordingBackend()
        val setup = OwnerSetup(backend)
        setup.coordinator.ownerScanGate = setup.gateFor(clients = 1)

        // Plain cycles: the first scan is immediate, then one per 90 s at the window start.
        assertEquals(listOf(0L, 90_000L, 180_000L), scanTimes(setup, backend, 0L, 200_000L))

        // A client that joins just before the window suppresses its first tick; the scan then
        // fires mid-window once the post-join settle time has passed.
        val backend2 = RecordingBackend()
        val setup2 = OwnerSetup(backend2)
        setup2.coordinator.ownerScanGate = setup2.gateFor(clients = 1)
        val times2 = mutableListOf<Long>()
        for (t in 0L..200_000L step 5_000L) {
            setup2.now = t
            if (t == 85_000L) setup2.policy.noteClientJoined(t) // settles until +95 s
            val before = backend2.discoverCount
            setup2.coordinator.tick(t, serviceWanted = true)
            if (backend2.discoverCount > before) times2 += t
        }
        assertEquals("suppressed at the window start, fired mid-window", listOf(0L, 95_000L, 185_000L), times2)
    }

    @Test
    fun `the supervisor fresh scan burst reaches the coordinator in owner mode`() {
        val backend = RecordingBackend()
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        var now = 0L
        val policy = OwnerScanPolicy()
        val logs = mutableListOf<String>()
        val coordinator = DiscoveryCoordinator(
            backend = backend,
            schedule = { delayMs, block -> scheduled += delayMs to block },
            log = { logs += it },
            strategySelector = DiscoveryStrategySelector.forService(),
            clock = { now },
        )
        coordinator.ownerScanGate = { t -> policy.shouldScan(clients = 0, mergeEnabled = true, mergeInProgress = false, nowMs = t) }
        fun cycles() = logs.count { it.startsWith("dnssd: strategy=") }

        // The owner has been scanning; its last scan was long enough ago that the gate allows a burst.
        now = 100_000L
        coordinator.tick(now, serviceWanted = true) // first tick: immediate cycle (anchor null), anchor at +100 s
        assertEquals("the owner issued one cycle", 1, cycles())

        // Its +500 ms scan step fires and completes the cycle; in gate mode that releases the radio.
        now = 100_500L
        scheduled[0].second.invoke()
        assertEquals("the first scan completed", 1, backend.discoverCount)

        // The supervisor fires FreshScanBurst; MeshService calls onEnterSearching with the current time.
        now = 200_000L
        coordinator.onEnterSearching(now)
        val burstTicks = scheduled.takeLast(3)
        assertEquals("the burst arms its three ticks", listOf(500L, 6_000L, 14_000L), burstTicks.map { it.first })

        // Firing the first burst tick (+500 ms) reaches tick() and issues a scan through the gate.
        now = 200_500L
        burstTicks[0].second.invoke()
        assertEquals("the burst tick issued a cycle", 2, cycles())

        // The remaining burst ticks reach the coordinator too, but the gate paces them: no extra cycles.
        now = 206_000L
        burstTicks[1].second.invoke()
        now = 214_000L
        burstTicks[2].second.invoke()
        assertEquals("the gate still owns the pacing", 2, cycles())
    }

    @Test
    fun `a stuck service cycle is released and owner scanning resumes`() {
        val backend = StuckAddBackend()
        val setup = OwnerSetup(backend)
        setup.coordinator.ownerScanGate = setup.gateFor(clients = 0)
        fun strategyLines() = setup.logs.count { it.startsWith("dnssd: strategy=") }

        // First scan at t=0: the addServiceRequest callback is lost, so the cycle never completes.
        backend.stuck = true
        setup.now = 0L
        setup.coordinator.tick(0L, serviceWanted = true)
        assertEquals("the first cycle was issued", 1, strategyLines())
        assertTrue("the stuck cycle holds the radio", setup.coordinator.serviceDiscoveryActive)

        // Until the watchdog fires, no further cycles happen even though the gate is due — and no
        // rotation can save us either, because the chain stalled before any scan opened a window.
        for (t in 5_000L..85_000L step 5_000L) {
            setup.now = t
            setup.coordinator.tick(t, serviceWanted = true)
        }
        assertEquals("no new cycle while the first one is stuck", 1, strategyLines())
        assertEquals(0, backend.discoverCount)

        // Boundary: at exactly STUCK_CYCLE_MS the watchdog releases and re-issues on the same tick.
        backend.stuck = false // the fresh cycle completes this time
        setup.now = DiscoveryCoordinator.STUCK_CYCLE_MS
        setup.coordinator.tick(DiscoveryCoordinator.STUCK_CYCLE_MS, serviceWanted = true)
        assertEquals("the stuck cycle was replaced by a fresh one", 2, strategyLines())
        assertEquals("the fresh cycle reached the scan", 1, backend.discoverCount)
        assertFalse(setup.coordinator.serviceDiscoveryActive)
        assertTrue("the release is logged", setup.logs.any { it.contains("stuck") })

        // Scanning resumes on the normal 15 s cadence (the gate anchor advanced to +90 s).
        assertEquals("back to the 15 s cadence", listOf(105_000L, 120_000L), scanTimes(setup, backend, fromMs = 95_000L, toMs = 120_000L))
    }
}
