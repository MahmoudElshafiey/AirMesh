package com.airmesh.ui

import com.airmesh.transport.AutoMeshState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the chats empty-state helpers (TASK U3 part 2). */
class EmptyStateUiHelpersTest {

    // --- ring math -----------------------------------------------------------

    @Test
    fun ringStartsSmallAndFadesToExactlyZero() {
        val start = ringAt(0f)
        assertEquals(RING_MIN_FRACTION, start.radiusFraction, 0f)
        assertEquals(RADAR_RING_MAX_ALPHA, start.alpha, 0f)

        val end = ringAt(1f)
        assertEquals(1f, end.radiusFraction, 0f)
        // The last frame must be fully transparent — no visible "pop" when a ring wraps.
        assertEquals(0f, end.alpha, 0f)
    }

    @Test
    fun radiusGrowsMonotonicallyWithinOneCycle() {
        var previous = -1f
        for (i in 0..100) {
            val p = i / 100f
            val ring = ringAt(p)
            assertTrue("radius must not shrink at phase $p", ring.radiusFraction >= previous)
            previous = ring.radiusFraction
        }
        // And it is strictly growing in the middle of the cycle, not flat.
        assertTrue(ringAt(0.75f).radiusFraction > ringAt(0.25f).radiusFraction)
    }

    @Test
    fun outOfRangePhasesClampToTheCycleEnds() {
        assertEquals(ringAt(0f), ringAt(-1f))
        assertEquals(ringAt(1f), ringAt(2f))
    }

    @Test
    fun ringsAreStaggeredByOneThirdOfThePeriod() {
        assertEquals(ringAt(0f), ring(0f, 0, 3))
        assertEquals(ringAt(1 / 3f), ring(0f, 1, 3))
        assertEquals(ringAt(2 / 3f), ring(0f, 2, 3))
    }

    @Test
    fun staggeredRingIsPeriodicInPhase() {
        for (index in 0 until 3) {
            assertEquals(ring(0.5f, index, 3), ring(1.5f, index, 3))
        }
    }

    // --- phase ---------------------------------------------------------------

    @Test
    fun phaseIsZeroAtCycleStartAndHalfwayAtMidPeriod() {
        assertEquals(0f, radarPhase(0L, RADAR_SEARCHING_PERIOD_MS), 0f)
        assertEquals(0.5f, radarPhase(RADAR_SEARCHING_PERIOD_MS / 2, RADAR_SEARCHING_PERIOD_MS), 0f)
    }

    @Test
    fun phaseWrapsEveryPeriod() {
        val period = RADAR_CONNECTING_PERIOD_MS
        assertEquals(radarPhase(0L, period), radarPhase(period, period))
        assertEquals(radarPhase(300L, period), radarPhase(period + 300L, period))
    }

    @Test
    fun phaseStaysInUnitIntervalAndGrowsWithinOnePeriod() {
        val period = RADAR_SEARCHING_PERIOD_MS
        var previous = -1f
        for (t in 0L until period step 97L) {
            val phase = radarPhase(t, period)
            assertTrue("phase out of [0,1): $phase", phase >= 0f && phase < 1f)
            assertTrue("phase must not go backwards at t=$t", phase >= previous)
            previous = phase
        }
    }

    @Test
    fun nonPositivePeriodIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { radarPhase(0L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { radarPhase(1L, -5L) }
    }

    // --- reduced motion --------------------------------------------------------

    @Test
    fun unreadableScaleNeverCountsAsReduced() {
        assertFalse(isReducedMotion(null))
    }

    @Test
    fun zeroOrNegativeScaleMeansAnimationsOff() {
        assertTrue(isReducedMotion(0f))
        assertTrue(isReducedMotion(-1f))
    }

    @Test
    fun positiveScaleKeepsAnimationOn() {
        assertFalse(isReducedMotion(0.5f))
        assertFalse(isReducedMotion(1f))
        // A NaN read must behave like "unreadable", not like "off".
        assertFalse(isReducedMotion(Float.NaN))
    }

    // --- kind mapper -----------------------------------------------------------

    private val allStates: List<AutoMeshState> = listOf(
        AutoMeshState.Idle,
        AutoMeshState.Searching(0L),
        AutoMeshState.Creating,
        AutoMeshState.Joining("AA:BB:CC:DD:EE:FF", 7, 0L),
        AutoMeshState.Owner,
        AutoMeshState.Client,
        AutoMeshState.Yielding("AA:BB:CC:DD:EE:FF", 7, 2, 0L),
        AutoMeshState.Backoff(1_000L, "busy"),
    )

    @Test
    fun missingPermissionsAlwaysWin() {
        allStates.forEach { state ->
            assertEquals(EmptyStateKind.PERMISSION_MISSING, emptyStateKind(state, permissionsGranted = false))
        }
    }

    @Test
    fun searchingMapsToSearchingUnlessTheNoPeersHintIsActive() {
        val searching = AutoMeshState.Searching(0L)
        assertEquals(EmptyStateKind.SEARCHING, emptyStateKind(searching, true))
        assertEquals(
            EmptyStateKind.NO_AIRMESH_NEARBY,
            emptyStateKind(searching, permissionsGranted = true, noAirMeshPeersHintActive = true),
        )
    }

    @Test
    fun theNoPeersHintIsIgnoredOutsideSearching() {
        // The banner only exists while searching; if it somehow did not, fall through to the state.
        assertEquals(EmptyStateKind.CONNECTED_NO_CONTACTS, emptyStateKind(AutoMeshState.Owner, true, noAirMeshPeersHintActive = true))
    }

    @Test
    fun connectingStatesMapToConnecting() {
        listOf<AutoMeshState>(
            AutoMeshState.Creating,
            AutoMeshState.Joining("AA:BB:CC:DD:EE:FF", 7, 0L),
            AutoMeshState.Yielding("AA:BB:CC:DD:EE:FF", 7, 2, 0L),
        ).forEach { state ->
            assertEquals(EmptyStateKind.CONNECTING, emptyStateKind(state, permissionsGranted = true))
        }
    }

    @Test
    fun idleAndBackoffMapToWaiting() {
        assertEquals(EmptyStateKind.IDLE_BACKOFF, emptyStateKind(AutoMeshState.Idle, true))
        assertEquals(EmptyStateKind.IDLE_BACKOFF, emptyStateKind(AutoMeshState.Backoff(1_000L, "busy"), true))
    }

    @Test
    fun connectedStatesMapToConnectedNoContacts() {
        assertEquals(EmptyStateKind.CONNECTED_NO_CONTACTS, emptyStateKind(AutoMeshState.Owner, true))
        assertEquals(EmptyStateKind.CONNECTED_NO_CONTACTS, emptyStateKind(AutoMeshState.Client, true))
    }

    // --- texts -----------------------------------------------------------------

    @Test
    fun everyKindHasItsTitle() {
        assertEquals("Looking for phones nearby…", emptyStateText(EmptyStateKind.SEARCHING, null))
        assertEquals("Wi-Fi Direct permissions are missing", emptyStateText(EmptyStateKind.PERMISSION_MISSING, null))
        assertEquals("Waiting a moment…", emptyStateText(EmptyStateKind.IDLE_BACKOFF, null))
        assertEquals("No AirMesh phones found yet", emptyStateText(EmptyStateKind.NO_AIRMESH_NEARBY, null))
        assertEquals(
            "Connected. Waiting for others to introduce themselves…",
            emptyStateText(EmptyStateKind.CONNECTED_NO_CONTACTS, null),
        )
    }

    @Test
    fun connectingShowsTheTargetIdWhenKnown() {
        assertEquals("Connecting to #42…", emptyStateText(EmptyStateKind.CONNECTING, 42))
        // Creating has no target yet.
        assertEquals("Starting a group…", emptyStateText(EmptyStateKind.CONNECTING, null))
    }

    private companion object {
        const val RING_MIN_FRACTION = 0.25f
    }
}
