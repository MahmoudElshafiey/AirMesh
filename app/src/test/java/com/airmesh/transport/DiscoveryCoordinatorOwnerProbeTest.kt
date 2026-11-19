package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the owner discoverability probe (TASK T1 part C3): while
 * [DiscoveryCoordinator.ownerProbeActive] is set, [DiscoveryCoordinator.tick] issues one-shot
 * strategy-3 scans (discoverPeers then services) on their own 20 s cadence — ahead of the owner
 * merge-scan gate — and a stuck cycle is released after the custom watchdog interval instead of
 * holding the radio. The directory loop ticks every 5 s; the tests drive tick on the same grid.
 */
class DiscoveryCoordinatorOwnerProbeTest {

    /** Records scan calls; chained variants report immediate success (default bodies). */
    private open class RecordingBackend : DiscoveryBackend {
        var discoverCount = 0
            private set
        override fun clearServiceRequests() {}
        override fun addServiceRequest() {}
        override fun discoverServices() { discoverCount++ }
        override fun discoverPeers() {}
    }

    /** Swallows the addServiceRequest callback so the chained cycle stalls before the scan. */
    private class StuckAddBackend : RecordingBackend() {
        var stuck = false
        override fun addServiceRequest(onDone: (Boolean, Int) -> Unit) {
            if (stuck) return // the callback never comes back; the chain stalls here
            super.addServiceRequest(onDone)
        }
    }

    private class ProbeSetup(backend: DiscoveryBackend, stuckCycleMs: Long = DiscoveryCoordinator.STUCK_CYCLE_MS) {
        var now = 0L
        val policy = OwnerScanPolicy()
        val logs = mutableListOf<String>()
        // The production owner plan (MeshService): starts at strategy 2, order 2,4,3,1.
        val coordinator = DiscoveryCoordinator(
            backend = backend,
            log = { logs += it },
            strategySelector = DiscoveryStrategySelector.forService(),
            stuckCycleMs = stuckCycleMs,
            clock = { now },
        )

        fun gateFor(clients: Int) = { t: Long ->
            policy.shouldScan(clients = clients, mergeEnabled = true, mergeInProgress = false, nowMs = t)
        }
    }

    /** Drives ticks on the 5 s directory grid and records when a scan was issued. */
    private fun scanTimes(setup: ProbeSetup, backend: RecordingBackend, fromMs: Long, toMs: Long): List<Long> {
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
    fun `an empty owner probes every 20 seconds on strategy 3`() {
        val backend = RecordingBackend()
        val setup = ProbeSetup(backend)
        setup.coordinator.ownerProbeActive = true

        assertEquals("one probe scan per 20 s", listOf(0L, 20_000L, 40_000L, 60_000L, 80_000L, 100_000L), scanTimes(setup, backend, 0L, 100_000L))
        assertEquals("every probe cycle uses strategy 3", 6, setup.logs.count { it == "dnssd: strategy=3" })
        assertEquals(
            "no other strategy is issued while the probe runs (rotation never issues its own scan)",
            0,
            setup.logs.count { it.startsWith("dnssd: strategy=") && it != "dnssd: strategy=3" },
        )
        assertEquals("each probe scan is logged with the spec wording", 6, setup.logs.count { it == "dnssd: owner probe cycle" })
    }

    @Test
    fun `the probe takes precedence over the merge-scan gate`() {
        val backend = RecordingBackend()
        val setup = ProbeSetup(backend)
        // Without the probe this gate would scan every 15 s on strategy 2 (see OwnerScanTest).
        setup.coordinator.ownerScanGate = setup.gateFor(clients = 0)
        setup.coordinator.ownerProbeActive = true

        assertEquals("the probe's 20 s cadence wins over the gate's 15 s", listOf(0L, 20_000L, 40_000L, 60_000L), scanTimes(setup, backend, 0L, 60_000L))
        assertEquals("the probe forces strategy 3 even though the owner plan starts at 2", 4, setup.logs.count { it == "dnssd: strategy=3" })
    }

    @Test
    fun `a stuck probe cycle is released after the custom watchdog interval`() {
        val backend = StuckAddBackend()
        // What MeshService passes (TASK T1 part C3): 25 s instead of the default 90 s.
        val setup = ProbeSetup(backend, stuckCycleMs = 25_000L)
        setup.coordinator.ownerProbeActive = true
        fun strategyLines() = setup.logs.count { it.startsWith("dnssd: strategy=") }

        // First probe at t=0: the addServiceRequest callback is lost, so the cycle never completes.
        backend.stuck = true
        setup.now = 0L
        setup.coordinator.tick(0L, serviceWanted = true)
        assertEquals("the first probe cycle was issued", 1, strategyLines())
        assertTrue("the stuck cycle holds the radio", setup.coordinator.serviceDiscoveryActive)

        // Until the watchdog fires, no further cycles happen — even though the 20 s probe interval
        // is due at t=20 s, a running (stuck) cycle owns the radio.
        for (t in 5_000L..20_000L step 5_000L) {
            setup.now = t
            setup.coordinator.tick(t, serviceWanted = true)
        }
        assertEquals("no new cycle while the first one is stuck", 1, strategyLines())
        assertEquals(0, backend.discoverCount)

        // Boundary: at exactly the custom 25 s interval the watchdog releases and re-issues on the same tick.
        backend.stuck = false // the fresh cycle completes this time
        setup.now = 25_000L
        setup.coordinator.tick(25_000L, serviceWanted = true)
        assertEquals("the stuck cycle was replaced by a fresh probe", 2, strategyLines())
        assertEquals("the fresh probe reached the scan", 1, backend.discoverCount)
        assertFalse(setup.coordinator.serviceDiscoveryActive)
        assertTrue("the release is logged with the custom interval", setup.logs.any { it.contains("stuck for 25000ms") })

        // Probing resumes on the 20 s cadence from the fresh cycle.
        assertEquals("back to the 20 s cadence", listOf(45_000L), scanTimes(setup, backend, fromMs = 30_000L, toMs = 60_000L))
    }

    @Test
    fun `stopService leaves probe mode`() {
        val backend = RecordingBackend()
        val setup = ProbeSetup(backend)
        setup.coordinator.ownerProbeActive = true
        setup.now = 0L
        setup.coordinator.tick(0L, serviceWanted = true) // one probe cycle

        setup.coordinator.stopService()
        assertFalse("stopService clears the probe flag", setup.coordinator.ownerProbeActive)

        for (t in 5_000L..100_000L step 20_000L) {
            setup.now = t
            setup.coordinator.tick(t, serviceWanted = true)
        }
        assertEquals("no probe cycles after stopService", 1, setup.logs.count { it == "dnssd: owner probe cycle" })
    }
}
