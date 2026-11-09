package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * JVM tests for [StaleGroupGuard], [StaleGroupStartupHarness], and non-negative
 * [TimelineRecorder] phases (Part 3).
 */
class StaleGroupGuardTest {

    private class RecordingBackend(private val clock: () -> Long) : DiscoveryBackend {
        val scanTimesMs = mutableListOf<Long>()
        override fun clearServiceRequests() = Unit
        override fun addServiceRequest() = Unit
        override fun discoverServices() {
            scanTimesMs.add(clock())
        }
        override fun discoverPeers() = Unit
    }

    @Test
    fun `replays exact A54 log removeGroup, second groupFormed=true 26 ms later, groupFormed=false at +560 ms`() {
        var now = 0L
        val logs = mutableListOf<String>()
        val backend = RecordingBackend { now }
        val coordinator = DiscoveryCoordinator(
            backend = backend,
            log = { logs.add(it) },
            strategySelector = DiscoveryStrategySelector.forService(),
            clock = { now },
        )
        val controller = AutoMeshController(
            myNodeId = 6469,
            tieBreakToken = 0x1111,
            clock = { now },
            random = Random(1L),
        )
        val harness = StaleGroupStartupHarness(
            clock = { now },
            controller = controller,
            coordinator = coordinator,
            log = { logs.add(it) },
        )

        // App starts at t = 0 ms; permissions granted -> enters Searching at t = 0 ms.
        harness.grantPermissions()
        assertTrue(controller.state is AutoMeshState.Searching)
        assertEquals(0L, (controller.state as AutoMeshState.Searching).sinceMs)

        // +400 ms (+0.4 s): first connection-info reply reports the stale group from previous session.
        now = 400L
        harness.onConnectionInfo(groupFormed = true, isGroupOwner = true, hasLiveLink = false)
        assertEquals(1, harness.removeGroupCalls)
        assertTrue(harness.guard.state is StaleGroupGuardState.Removing)
        assertTrue(logs.contains("auto: stale group cleanup started"))

        // +426 ms (26 ms later): second in-flight requestConnectionInfo reply still says groupFormed=true, isGroupOwner=true.
        now = 426L
        harness.onConnectionInfo(groupFormed = true, isGroupOwner = true, hasLiveLink = false)

        // +500 ms: first burst scan fires within 500 ms of entering Searching.
        now = 500L
        harness.tick()
        assertEquals(listOf(500L), backend.scanTimesMs)

        // +960 ms (+560 ms after removeGroup at 400 ms): groupFormed=false confirms removal.
        now = 960L
        harness.onConnectionInfo(groupFormed = false, isGroupOwner = false, hasLiveLink = false)

        // Assertions required by Part 3:
        assertFalse(
            "controller must never reach Owner",
            harness.controllerStateHistory.any { it == AutoMeshState.Owner },
        )
        assertEquals("no TCP listener start request", 0, harness.tcpListenerStarts)
        assertFalse(
            "DNS-SD service must never be registered with owner=1",
            harness.advertisedOwnerHistory.contains(true),
        )
        assertTrue("controller stays in Searching", controller.state is AutoMeshState.Searching)
        assertTrue(
            "controller reached Searching within 500 ms",
            (controller.state as AutoMeshState.Searching).sinceMs <= 500L,
        )
        assertFalse(
            "no backoff state entered",
            harness.controllerStateHistory.any { it is AutoMeshState.Backoff },
        )
        assertNull("timeline groupFormed must not be set by stale reply", harness.timelineRecorder.groupFormedMs)
        assertEquals(StaleGroupGuardState.Inactive, harness.guard.state)
        assertTrue(harness.guard.visitedStates.contains(StaleGroupGuardState.Confirmed))
        assertTrue(logs.contains("auto: stale group cleanup done after 560ms"))
    }

    @Test
    fun `two stale groupFormed=true replies in a row are both ignored`() {
        var now = 400L
        val backend = RecordingBackend { now }
        val coordinator = DiscoveryCoordinator(backend = backend, clock = { now })
        val controller = AutoMeshController(6469, 0x1111, { now }, Random(1L))
        val harness = StaleGroupStartupHarness(
            clock = { now },
            controller = controller,
            coordinator = coordinator,
        )
        harness.grantPermissions()

        // First reply at 400 ms starts Removing; next two stale replies at 426 ms and 510 ms are both ignored.
        harness.onConnectionInfo(groupFormed = true, isGroupOwner = true)
        now = 426L
        harness.onConnectionInfo(groupFormed = true, isGroupOwner = true)
        now = 510L
        harness.onConnectionInfo(groupFormed = true, isGroupOwner = true)

        assertEquals(0, harness.tcpListenerStarts)
        assertFalse(harness.advertisedOwnerHistory.contains(true))
        assertFalse(harness.controllerStateHistory.any { it == AutoMeshState.Owner })
        assertTrue(controller.state is AutoMeshState.Searching)
    }

    @Test
    fun `reply with groupFormed=true after the guard is Inactive is handled normally`() {
        var now = 0L
        val backend = RecordingBackend { now }
        val coordinator = DiscoveryCoordinator(backend = backend, clock = { now })
        val controller = AutoMeshController(6469, 0x1111, { now }, Random(1L))
        val harness = StaleGroupStartupHarness(
            clock = { now },
            controller = controller,
            coordinator = coordinator,
        )
        harness.grantPermissions()

        // Stale group at 400 ms -> confirmed gone at 960 ms -> guard is now Inactive.
        now = 400L
        harness.onConnectionInfo(groupFormed = true, isGroupOwner = true)
        now = 960L
        harness.onConnectionInfo(groupFormed = false, isGroupOwner = false)
        assertEquals(StaleGroupGuardState.Inactive, harness.guard.state)

        // Also verify leaving Removing via removeGroup success + 1,500 ms settle window.
        val settleGuard = StaleGroupGuard(clock = { now })
        now = 1_000L
        settleGuard.onStaleGroupRemovalStarted(now)
        now = 1_100L
        settleGuard.onRemoveGroupSuccess(now)
        now = 2_599L
        assertFalse(settleGuard.tick(now))
        now = 2_600L
        assertTrue(settleGuard.tick(now))
        assertEquals(StaleGroupGuardState.Inactive, settleGuard.state)

        // Now a real groupFormed=true reply arrives at 3,000 ms while guard is Inactive: handled normally.
        now = 3_000L
        harness.onConnectionInfo(groupFormed = true, isGroupOwner = true)
        assertEquals(AutoMeshState.Owner, controller.state)
        assertEquals(1, harness.tcpListenerStarts)
        assertEquals(true, harness.advertisedOwnerHistory.last())
        assertEquals(3_000L, harness.timelineRecorder.groupFormedMs)
    }

    @Test
    fun `removeGroup failure plus the 4 s hard timeout logs warning and continues normally`() {
        var now = 100L
        val logs = mutableListOf<String>()
        val backend = RecordingBackend { now }
        val coordinator = DiscoveryCoordinator(backend = backend, clock = { now })
        val controller = AutoMeshController(6469, 0x1111, { now }, Random(1L))
        val harness = StaleGroupStartupHarness(
            clock = { now },
            controller = controller,
            coordinator = coordinator,
            log = { logs.add(it) },
        )
        harness.grantPermissions()

        // Startup stale group triggers removeGroup at t = 100 ms, which fails at t = 150 ms.
        harness.onConnectionInfo(groupFormed = true, isGroupOwner = true)
        now = 150L
        harness.guard.onRemoveGroupFailure(reason = 2, nowMs = now)
        assertTrue(harness.guard.state is StaleGroupGuardState.Removing)

        // Before 4,000 ms (at t = 4,099 ms), guard is still Removing and ignores groupFormed=true.
        now = 4_099L
        harness.tick()
        harness.onConnectionInfo(groupFormed = true, isGroupOwner = true)
        assertTrue(harness.guard.state is StaleGroupGuardState.Removing)
        assertEquals(0, harness.tcpListenerStarts)

        // At t = 4,100 ms (4,000 ms after removeGroup at 100 ms), hard timeout leaves Removing -> Confirmed -> Inactive.
        now = 4_100L
        harness.tick()
        assertEquals(StaleGroupGuardState.Inactive, harness.guard.state)
        assertTrue(logs.any { it.contains("warning") && it.contains("4000ms") })
        assertTrue(logs.contains("auto: stale group cleanup done after 4000ms"))

        // Subsequent groupFormed=true reply is processed normally.
        now = 4_200L
        harness.onConnectionInfo(groupFormed = true, isGroupOwner = true)
        assertEquals(AutoMeshState.Owner, controller.state)
        assertEquals(1, harness.tcpListenerStarts)
    }

    @Test
    fun `a group created by this process is never ignored by the guard`() {
        var now = 0L
        val backend = RecordingBackend { now }
        val coordinator = DiscoveryCoordinator(backend = backend, clock = { now })
        val controller = AutoMeshController(38010, 0x2222, { now }, Random(1L))
        val harness = StaleGroupStartupHarness(
            clock = { now },
            controller = controller,
            coordinator = coordinator,
        )
        harness.grantPermissions()

        // This process creates a group itself (e.g. elected creator or manual createGroup).
        now = 300L
        harness.createGroupFromThisProcess()
        assertTrue(harness.guard.processCreatedGroup)

        // First connection-info reply reports the group we created: never treated as stale.
        now = 500L
        harness.onConnectionInfo(groupFormed = true, isGroupOwner = true, hasLiveLink = false)
        assertEquals(StaleGroupGuardState.Inactive, harness.guard.state)
        assertEquals(0, harness.removeGroupCalls)
        assertEquals(AutoMeshState.Owner, controller.state)
        assertEquals(1, harness.tcpListenerStarts)
        assertEquals(true, harness.advertisedOwnerHistory.last())
    }

    @Test
    fun `controller that already entered Owner from a stale reply returns to Searching without backoff`() {
        var now = 0L
        val controller = AutoMeshController(6469, 0x1111, { now }, Random(1L))
        controller.onEvent(AutoMeshEvent.PermissionsChanged(true))
        now = 400L
        controller.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.OWNER))
        assertEquals(AutoMeshState.Owner, controller.state)

        // Stale reply cleanup fires IntentionalGroupGone: returns directly to Searching without backoff.
        val actions = controller.onEvent(AutoMeshEvent.IntentionalGroupGone)
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), actions)
        assertEquals(AutoMeshState.Searching(400L), controller.state)
    }

    @Test
    fun `timeline recorder ignores stale events and never reports negative phases`() {
        var now = 0L
        val recorder = TimelineRecorder { now }
        recorder.onStart(0L)

        now = 400L
        recorder.onSearching(now)
        // Stale groupFormed at +400 ms is ignored when marked stale=true.
        assertNull(
            recorder.onAutoStateChanged(
                previous = AutoMeshState.Searching(400L),
                current = AutoMeshState.Owner,
                nowMs = 426L,
                stale = true,
            ),
        )
        assertNull(recorder.groupFormedMs)

        // Even if an unmarked out-of-order groupFormed was recorded at 426 ms before decision at 13,621 ms,
        // the timeline must never report a negative phase (-13195ms) and instead reports n/a until real formation.
        recorder.onGroupFormed(nowMs = 426L, stale = false)
        now = 900L
        recorder.onFirstScan(now)
        now = 13_600L
        recorder.onFirstPeer(now)
        now = 13_621L
        val atDecision = recorder.onAutoStateChanged(
            previous = AutoMeshState.Searching(400L),
            current = AutoMeshState.Joining("BB:00:00:00:00:02", 38010, now),
            nowMs = now,
        )!!
        assertFalse("timeline must never contain negative phase: $atDecision", atDecision.contains("=-"))
        assertTrue(atDecision.contains("decision->groupFormed=n/a"))

        // Real group formation at 15,000 ms records a positive decision->groupFormed delta (1379ms).
        now = 15_000L
        val atRealFormation = recorder.onAutoStateChanged(
            previous = AutoMeshState.Joining("BB:00:00:00:00:02", 38010, 13_621L),
            current = AutoMeshState.Client,
            nowMs = now,
        )!!
        assertFalse(atRealFormation.contains("=-"))
        assertTrue(atRealFormation.contains("decision->groupFormed=1379ms"))
    }
}
