package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * JVM tests for startup latency reductions and the Searching scan burst (Part 2):
 * first scan within 500 ms, intentional group removal shortcut, unexpected loss
 * debounce + backoff preserved, burst schedule (+0.5 s, +6 s, +14 s, then 60 s),
 * no burst while in a group, and burst strategies never issuing discoverPeers.
 */
class StartupLatencyAndBurstTest {

    private class RecordingBackend : DiscoveryBackend {
        val calls = mutableListOf<String>()
        val scanTimestampsMs = mutableListOf<Long>()
        var clock: () -> Long = { 0L }

        override fun clearServiceRequests() {
            calls.add("clear")
        }

        override fun addServiceRequest() {
            calls.add("add")
        }

        override fun addServiceRequest(filtered: Boolean, onDone: (Boolean, Int) -> Unit) {
            calls.add("add(filtered=$filtered)")
            onDone(true, 0)
        }

        override fun reRegisterLocalService(onDone: (Boolean, Int) -> Unit) {
            calls.add("reRegister")
            onDone(true, 0)
        }

        override fun discoverServices() {
            calls.add("services")
            scanTimestampsMs.add(clock())
        }

        override fun discoverPeers() {
            calls.add("peers")
        }
    }

    @Test
    fun `first scan starts within 500 ms of entering Searching`() {
        var now = 0L
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val backend = RecordingBackend().apply { clock = { now } }
        val coordinator = DiscoveryCoordinator(
            backend = backend,
            schedule = { delayMs, block ->
                // Only record burst timer schedules (>= 500 ms when sequencer is not in ADD->DISCOVER,
                // or run step schedules at their due time).
                scheduled.add((now + delayMs) to block)
            },
            strategySelector = DiscoveryStrategySelector.forService(),
            clock = { now },
        )

        coordinator.onEnterSearching(nowMs = 0L)
        assertTrue("no scan at 0 ms before the 500 ms burst offset", backend.scanTimestampsMs.isEmpty())

        // Advance to 500 ms (not waiting for the 5 s loop slice) and run due scheduled work.
        now = 500L
        val dueAt500 = scheduled.filter { it.first <= now }.map { it.second }
        scheduled.removeAll { it.first <= now }
        dueAt500.forEach { it() }
        // Run the chained discoverServices step if scheduled with a 0..500 ms step delay.
        val stepDue = scheduled.toList()
        scheduled.clear()
        stepDue.forEach { it.second() }

        assertEquals(1, backend.scanTimestampsMs.size)
        assertTrue("first scan started within 500 ms", backend.scanTimestampsMs.first() <= 500L)
    }

    @Test
    fun `intentional removal reaches Searching in at most 500 ms`() {
        var now = 1_000L
        val tracker = GroupPresenceTracker()
        // Establish a formed group first.
        assertFalse(tracker.onReport(groupFormed = true, nowMs = now))

        // Mark removal intentional: the very next groupFormed=false confirms immediately (0 ms),
        // skipping the 2,000 ms transient-blip grace period.
        tracker.markIntentionalRemoval()
        assertTrue(tracker.onReport(groupFormed = false, nowMs = now))

        // Controller in Owner / Client skips the 1-3 s group-gone backoff on intentional removal.
        val controller = AutoMeshController(
            myNodeId = 6469,
            tieBreakToken = 0x1234,
            clock = { now },
            random = Random(1L),
        )
        controller.onEvent(AutoMeshEvent.PermissionsChanged(true))
        controller.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.OWNER))
        assertEquals(AutoMeshState.Owner, controller.state)

        val actions = controller.onEvent(AutoMeshEvent.IntentionalGroupGone)
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), actions)
        val searching = controller.state as AutoMeshState.Searching
        assertTrue("reached Searching in <= 500 ms", searching.sinceMs - now <= 500L)

        // Also works when markIntentionalGroupRemoval() precedes a standard GroupGone event.
        controller.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))
        now = 2_000L
        controller.markIntentionalGroupRemoval()
        val clientActions = controller.onEvent(AutoMeshEvent.GroupGone)
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), clientActions)
        assertTrue(controller.state is AutoMeshState.Searching)
    }

    @Test
    fun `unexpected loss still waits for debounce and group-gone backoff as before`() {
        val tracker = GroupPresenceTracker()
        assertFalse(tracker.onReport(groupFormed = true, nowMs = 0L))
        // Unexpected false report is debounced for the full 2,000 ms grace window.
        assertFalse(tracker.onReport(groupFormed = false, nowMs = 1_000L))
        assertFalse(tracker.onReport(groupFormed = false, nowMs = 2_999L))
        assertTrue(tracker.onReport(groupFormed = false, nowMs = 3_000L))

        for (seed in 1L..10L) {
            var now = 5_000L
            val controller = AutoMeshController(
                myNodeId = 6469,
                tieBreakToken = 0x1234,
                clock = { now },
                random = Random(seed),
            )
            controller.onEvent(AutoMeshEvent.PermissionsChanged(true))
            controller.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))

            val actions = controller.onEvent(AutoMeshEvent.GroupGone)
            assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), actions)
            val backoff = controller.state as AutoMeshState.Backoff
            assertTrue(
                "seed $seed unexpected loss backoff must be in 1..3 s",
                backoff.untilMs in (now + 1_000L)..(now + 3_000L),
            )
        }
    }

    @Test
    fun `burst schedule is exact at 0_5 s, 6 s, 14 s and then 60 s cadence`() {
        var now = 0L
        val backend = RecordingBackend().apply { clock = { now } }
        val coordinator = DiscoveryCoordinator(
            backend = backend,
            strategySelector = DiscoveryStrategySelector.forService(),
            clock = { now },
        )

        coordinator.onEnterSearching(nowMs = 0L)

        // Probe every boundary: 0..499 ms silent, 500 ms scan #1, 6,000 ms scan #2,
        // 14,000 ms scan #3, then 60,000 ms later at 74,000 ms scan #4, and 134,000 ms scan #5.
        val probeTimes = listOf(
            0L, 499L, 500L,
            1_000L, 5_999L, 6_000L,
            10_000L, 13_999L, 14_000L,
            20_000L, 60_000L, 73_999L, 74_000L,
            100_000L, 133_999L, 134_000L,
        )
        for (t in probeTimes) {
            now = t
            coordinator.tick(nowMs = t, serviceWanted = true)
            if (t == 500L) {
                // First callback proves the strategy works so silence rotation does not alter the cadence.
                coordinator.notifyRawCallback(t)
            }
        }

        assertEquals(
            listOf(500L, 6_000L, 14_000L, 74_000L, 134_000L),
            backend.scanTimestampsMs,
        )
    }

    @Test
    fun `no burst scans run while in a group`() {
        var now = 0L
        val backend = RecordingBackend().apply { clock = { now } }
        val coordinator = DiscoveryCoordinator(
            backend = backend,
            strategySelector = DiscoveryStrategySelector.forService(),
            clock = { now },
        )

        // Enter Searching at t=0, then form a group at t=200 ms before the first burst fires.
        coordinator.onEnterSearching(nowMs = 0L)
        now = 200L
        coordinator.stopService() // GroupFormed emits StopDiscovery -> stopService()
        backend.calls.clear()
        backend.scanTimestampsMs.clear()

        for (t in listOf(500L, 1_000L, 6_000L, 14_000L, 60_000L, 74_000L)) {
            now = t
            coordinator.tick(nowMs = t, serviceWanted = false)
            coordinator.refreshIfDue(nowMs = t)
        }

        assertTrue("no scans while in a group", backend.scanTimestampsMs.isEmpty())
        assertTrue("no backend calls while in a group", backend.calls.isEmpty())
    }

    @Test
    fun `bursts never issue discoverPeers or service re-registration`() {
        for (initialStrategy in 1..4) {
            var now = 0L
            val backend = RecordingBackend().apply { clock = { now } }
            val selector = DiscoveryStrategySelector(
                store = NoDiscoveryStrategyStore,
                defaultStrategy = initialStrategy,
                rotationOrder = listOf(2, 4, 3, 1),
                silenceWindowMs = 12_000L,
            )
            val coordinator = DiscoveryCoordinator(
                backend = backend,
                strategySelector = selector,
                clock = { now },
            )

            coordinator.onEnterSearching(nowMs = 0L)
            for (t in listOf(500L, 6_000L, 14_000L)) {
                now = t
                coordinator.tick(nowMs = t, serviceWanted = true)
            }

            assertEquals(3, backend.scanTimestampsMs.size)
            assertFalse(
                "strategy $initialStrategy burst must never issue discoverPeers",
                backend.calls.contains("peers"),
            )
            assertFalse(
                "strategy $initialStrategy burst must never re-register local service",
                backend.calls.contains("reRegister"),
            )
            // Strategy 3 and 4 only appear when silence rotation triggers after the burst.
            now = 26_000L // 14_000 + 12_000 silence window
            backend.calls.clear()
            coordinator.tick(nowMs = now, serviceWanted = true)
            if (selector.strategy == 3 || selector.strategy == 4) {
                assertTrue("rotation to ${selector.strategy} issues discoverPeers", backend.calls.contains("peers"))
            }
        }
    }
}
