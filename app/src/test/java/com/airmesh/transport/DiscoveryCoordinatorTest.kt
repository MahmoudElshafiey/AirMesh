package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the scan serializer: cycle order, 60 s cadence, no overlap. */
class DiscoveryCoordinatorTest {

    /** Records backend calls in order so tests can assert on them. */
    private class FakeBackend : DiscoveryBackend {
        val calls = mutableListOf<String>()
        override fun clearServiceRequests() { calls.add("clear") }
        override fun addServiceRequest() { calls.add("add") }
        override fun discoverServices() { calls.add("services") }
        override fun discoverPeers() { calls.add("peers") }
    }

    private var now = 0L
    private val backend = FakeBackend()
    private val coordinator = DiscoveryCoordinator(backend) { now }

    @Test
    fun `a service cycle issues clear, add and scan in order`() {
        coordinator.issueServiceCycle()
        assertEquals(listOf("clear", "add", "services"), backend.calls)
        assertTrue(coordinator.serviceDiscoveryActive)
        assertEquals(0L, coordinator.lastCycleAtMs)
    }

    @Test
    fun `refresh is not due before 60 s and re-issues the full cycle at or after it`() {
        now = 1_000
        coordinator.issueServiceCycle()
        assertFalse(coordinator.refreshIfDue(59_999))
        assertEquals(listOf("clear", "add", "services"), backend.calls)
        assertTrue(coordinator.refreshIfDue(61_000))
        assertEquals(listOf("clear", "add", "services", "clear", "add", "services"), backend.calls)
    }

    @Test
    fun `refresh does nothing when no service scan is active`() {
        assertFalse(coordinator.refreshIfDue(120_000))
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `a manual peer scan clears an active service scan first`() {
        now = 0L
        coordinator.issueServiceCycle()
        backend.calls.clear()
        coordinator.requestPeerScan()
        assertEquals(listOf("clear", "peers"), backend.calls)
        assertFalse(coordinator.serviceDiscoveryActive)
        assertTrue(coordinator.peerScanActive)
    }

    @Test
    fun `a peer scan issued while none is active does not clear anything`() {
        now = 0L
        coordinator.requestPeerScan()
        assertEquals(listOf("peers"), backend.calls)
    }

    @Test
    fun `discoverPeers is never issued while a service scan is active`() {
        // Interleave cycles and manual scans; every "peers" call must be preceded by
        // the clear that stopped any active service scan.
        now = 0L
        coordinator.issueServiceCycle()                    // [clear, add, services]
        now = 10_000; coordinator.requestPeerScan()         // clears first: [clear, peers]
        now = 40_000; coordinator.tick(now, true)           // window over → resume cycle
        now = 100_000; coordinator.refreshIfDue(now)        // due again → re-issue
        now = 110_000; coordinator.requestPeerScan()        // clears again: [clear, peers]
        for (index in backend.calls.indices) {
            if (backend.calls[index] == "peers") {
                assertEquals("a clear must precede every peer scan", "clear", backend.calls[index - 1])
            }
        }
    }

    @Test
    fun `the service cycle does not resume until the peer scan window elapses`() {
        now = 0L
        coordinator.issueServiceCycle()
        coordinator.requestPeerScan() // holds the radio for PEER_SCAN_WINDOW_MS
        backend.calls.clear()
        coordinator.tick(29_999, serviceWanted = true)
        assertTrue("no resume inside the window", backend.calls.isEmpty())
        now = 30_000
        coordinator.tick(now, serviceWanted = true)
        assertEquals(listOf("clear", "add", "services"), backend.calls)
    }

    @Test
    fun `the service cycle does not resume when auto-mesh is paused`() {
        now = 0L
        coordinator.issueServiceCycle()
        coordinator.requestPeerScan()
        backend.calls.clear()
        coordinator.tick(DiscoveryCoordinator.PEER_SCAN_WINDOW_MS + 1, serviceWanted = false)
        assertTrue("paused: no scan at all", backend.calls.isEmpty())
        assertFalse(coordinator.serviceDiscoveryActive)
    }

    @Test
    fun `tick does not double-issue an already active service scan`() {
        now = 0L
        coordinator.issueServiceCycle()
        backend.calls.clear()
        coordinator.tick(1_000, serviceWanted = true)
        assertTrue("already scanning: tick issues nothing", backend.calls.isEmpty())
    }

    @Test
    fun `stopService clears the request and resets both scans`() {
        now = 0L
        coordinator.issueServiceCycle()
        backend.calls.clear()
        coordinator.stopService()
        assertEquals(listOf("clear"), backend.calls)
        assertFalse(coordinator.serviceDiscoveryActive)
        assertFalse(coordinator.peerScanActive)
    }
}
