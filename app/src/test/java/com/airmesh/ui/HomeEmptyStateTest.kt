package com.airmesh.ui

import com.airmesh.transport.AutoMeshState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the chats-home empty-state mapping and search timer (TASK U4 part 1). */
class HomeEmptyStateTest {

    private val searching = AutoMeshState.Searching(sinceMs = 0L)

    // --- Searching: always animated, caption driven by devices / wait time --------

    @Test
    fun searchingWithNoDevicesShowsPlainHeadline() {
        val state = homeEmptyState(searching, otherDevicesFound = 0, searchingForMs = 5_000L, permissionOk = true)
        assertEquals(EmptyStateKind.SEARCHING, state.kind)
        assertEquals(HOME_SEARCHING_HEADLINE, state.headline)
        assertNull(state.caption)
        assertFalse(state.showManualButton)
    }

    @Test
    fun searchingWithOneOtherDeviceStaysAnimatedAndExplainsTheCaption() {
        // The bug this task fixes: a classic device must NOT switch to a static kind.
        val state = homeEmptyState(searching, otherDevicesFound = 1, searchingForMs = 5_000L, permissionOk = true)
        assertEquals(EmptyStateKind.SEARCHING, state.kind)
        assertEquals(HOME_SEARCHING_HEADLINE, state.headline)
        assertEquals(HOME_OTHER_DEVICES_CAPTION, state.caption)
        assertFalse(state.showManualButton)
    }

    @Test
    fun searchingWithManyOtherDevicesUsesTheSameCaption() {
        val state = homeEmptyState(searching, otherDevicesFound = 7, searchingForMs = 5_000L, permissionOk = true)
        assertEquals(EmptyStateKind.SEARCHING, state.kind)
        assertEquals(HOME_OTHER_DEVICES_CAPTION, state.caption)
        assertFalse(state.showManualButton)
    }

    @Test
    fun justBeforeTheNinetySecondBoundaryShowsNoButton() {
        val noDevices = homeEmptyState(searching, 0, HOME_MANUAL_BUTTON_AFTER_MS - 1L, true)
        assertNull(noDevices.caption)
        assertFalse(noDevices.showManualButton)

        // With a device present the ignored-devices caption still applies at 89.999 s.
        val withDevice = homeEmptyState(searching, 1, HOME_MANUAL_BUTTON_AFTER_MS - 1L, true)
        assertEquals(HOME_OTHER_DEVICES_CAPTION, withDevice.caption)
        assertFalse(withDevice.showManualButton)
    }

    @Test
    fun atTheNinetySecondBoundaryTheLongWaitCaptionAndButtonAppear() {
        val noDevices = homeEmptyState(searching, 0, HOME_MANUAL_BUTTON_AFTER_MS, true)
        assertEquals(EmptyStateKind.SEARCHING, noDevices.kind)
        assertEquals(HOME_LONG_WAIT_CAPTION, noDevices.caption)
        assertTrue(noDevices.showManualButton)

        // The long-wait caption takes precedence over the ignored-devices one.
        val withDevices = homeEmptyState(searching, 3, HOME_MANUAL_BUTTON_AFTER_MS, true)
        assertEquals(HOME_LONG_WAIT_CAPTION, withDevices.caption)
        assertTrue(withDevices.showManualButton)
    }

    @Test
    fun wellPastTheBoundaryKeepsShowingTheButton() {
        val state = homeEmptyState(searching, 2, HOME_MANUAL_BUTTON_AFTER_MS + 30_000L, true)
        assertEquals(HOME_LONG_WAIT_CAPTION, state.caption)
        assertTrue(state.showManualButton)
    }

    // --- Non-searching states keep their existing kind and text --------------------

    @Test
    fun joiningShowsConnectingWithTheTargetId() {
        val state = homeEmptyState(
            AutoMeshState.Joining(targetAddress = "192.168.49.1", targetNodeId = 42, sinceMs = 0L),
            otherDevicesFound = 0, searchingForMs = 0L, permissionOk = true,
        )
        assertEquals(EmptyStateKind.CONNECTING, state.kind)
        assertEquals("Connecting to #42…", state.headline)
        assertNull(state.caption)
        assertFalse(state.showManualButton)
    }

    @Test
    fun creatingShowsConnectingWithoutATarget() {
        val state = homeEmptyState(AutoMeshState.Creating, 0, 0L, true)
        assertEquals(EmptyStateKind.CONNECTING, state.kind)
        assertEquals("Starting a group…", state.headline)
    }

    @Test
    fun yieldingShowsConnectingWithTheTargetId() {
        val state = homeEmptyState(
            AutoMeshState.Yielding(targetAddress = "192.168.49.1", targetNodeId = 7, targetPhones = 3, sinceMs = 0L),
            otherDevicesFound = 0, searchingForMs = 0L, permissionOk = true,
        )
        assertEquals(EmptyStateKind.CONNECTING, state.kind)
        assertEquals("Connecting to #7…", state.headline)
    }

    @Test
    fun idleAndBackoffShowWaitingAMoment() {
        for (status in listOf<AutoMeshState>(AutoMeshState.Idle, AutoMeshState.Backoff(untilMs = 1L, reason = "x"))) {
            val state = homeEmptyState(status, 0, 0L, true)
            assertEquals(EmptyStateKind.IDLE_BACKOFF, state.kind)
            assertEquals("Waiting a moment…", state.headline)
        }
    }

    @Test
    fun ownerAndClientShowConnectedNoContacts() {
        for (status in listOf<AutoMeshState>(AutoMeshState.Owner, AutoMeshState.Client)) {
            val state = homeEmptyState(status, 0, 0L, true)
            assertEquals(EmptyStateKind.CONNECTED_NO_CONTACTS, state.kind)
            assertEquals("Connected. Waiting for others to introduce themselves…", state.headline)
        }
    }

    // --- Precedence and invariants ---------------------------------------------------

    @Test
    fun missingPermissionsOutrankEverythingElse() {
        val state = homeEmptyState(searching, otherDevicesFound = 3, searchingForMs = HOME_MANUAL_BUTTON_AFTER_MS + 1L, permissionOk = false)
        assertEquals(EmptyStateKind.PERMISSION_MISSING, state.kind)
        assertEquals("Wi-Fi Direct permissions are missing", state.headline)
        assertNull(state.caption)
        assertFalse(state.showManualButton)
    }

    @Test
    fun noAirMeshNearbyIsNeverProduced() {
        // The old static "no AirMesh phones" kind is unreachable from this mapping: a classic
        // device only changes the caption while searching.
        val statuses = listOf(
            searching, AutoMeshState.Creating, AutoMeshState.Owner, AutoMeshState.Client,
            AutoMeshState.Idle, AutoMeshState.Backoff(untilMs = 1L, reason = "x"),
        )
        for (status in statuses) {
            for (devices in intArrayOf(0, 1, 5)) {
                for (waitMs in longArrayOf(0L, HOME_MANUAL_BUTTON_AFTER_MS - 1L, HOME_MANUAL_BUTTON_AFTER_MS, 2 * HOME_MANUAL_BUTTON_AFTER_MS)) {
                    val state = homeEmptyState(status, devices, waitMs, true)
                    assertTrue("unexpected NO_AIRMESH_NEARBY for $status/$devices/$waitMs",
                        state.kind != EmptyStateKind.NO_AIRMESH_NEARBY)
                }
            }
        }
    }

    // --- SearchTimer with a fake clock -------------------------------------------------

    @Test
    fun timerCountsFromTheMomentSearchingStarts() {
        var now = 1_000L
        val timer = SearchTimer(clock = { now })
        assertEquals(0L, timer.update(searching)) // first tick at entry: zero elapsed
        now = 5_000L
        assertEquals(4_000L, timer.update(searching))
        now = 91_000L
        // Entry was at t=1s, so exactly the boundary has passed here.
        assertEquals(HOME_MANUAL_BUTTON_AFTER_MS, timer.update(searching))
    }

    @Test
    fun timerResetsWhenLeavingSearching() {
        var now = 1_000L
        val timer = SearchTimer(clock = { now })
        timer.update(searching) // enter at t=1s
        now = 5_000L
        assertEquals(4_000L, timer.update(searching))
        now = 60_000L
        assertEquals(0L, timer.update(AutoMeshState.Idle))
    }

    @Test
    fun reenterSearchingCountsFromTheNewEntry() {
        var now = 1_000L
        val timer = SearchTimer(clock = { now })
        timer.update(searching) // enter at t=1s
        now = 50_000L
        assertEquals(49_000L, timer.update(searching))
        now = 60_000L
        timer.update(AutoMeshState.Backoff(untilMs = 61_000L, reason = "x")) // leave
        now = 200_000L
        assertEquals(0L, timer.update(searching)) // re-enter: counted from t=200s, not t=1s
        now = 205_000L
        assertEquals(5_000L, timer.update(searching))
    }

    @Test
    fun defaultTimerUsesTheRealClock() {
        // The production default (System.currentTimeMillis) must work without injection.
        assertEquals(0L, SearchTimer().update(searching))
    }

    // --- baseRoute: the registered pattern must still compare as its plain route -----

    @Test
    fun baseRouteStripsTheQueryPartOfRegisteredPatterns() {
        assertEquals(ROUTE_NETWORK, baseRoute(ROUTE_NETWORK_PATTERN))
        assertEquals("chat/{conversationId}", baseRoute("chat/{conversationId}"))
        assertNull(baseRoute(null))
    }

    @Test
    fun theExpandAdvancedRouteRoundTripsThroughBaseRoute() {
        val route = networkRouteExpandAdvanced()
        assertTrue(route.startsWith("$ROUTE_NETWORK?"))
        assertEquals(ROUTE_NETWORK, baseRoute(route))
    }

    @Test
    fun theNetworkPatternStillCountsAsTopLevel() {
        // On device, destination.route reports exactly this string for the Network tab.
        assertTrue(isTopLevelRoute(ROUTE_NETWORK_PATTERN))
        assertFalse(isTopLevelRoute("chat/{conversationId}"))
    }
}
