package com.airmesh.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [PresenceTracker] with a fake clock (TASK D2 part 1). */
class PresenceTrackerTest {

    private var now = 1_700_000_000_000L
    private val tracker = PresenceTracker(clock = { now })

    @Test
    fun freshActivityIsOnlineAndTheNeighborFlagFollowsTheLiveSet() {
        tracker.noteActivity(5)

        assertEquals(PresenceState.ONLINE, tracker.states(setOf(5))[5]!!.state)
        assertTrue(tracker.states(setOf(5))[5]!!.isDirectNeighbor)
        assertFalse(tracker.states(emptySet())[5]!!.isDirectNeighbor)
    }

    @Test
    fun onlineHoldsForExactlyOneHundredSecondsThenBecomesRecent() {
        tracker.noteActivity(5)

        now += PresenceTracker.ONLINE_WINDOW_MS
        assertEquals(PresenceState.ONLINE, tracker.states(emptySet())[5]!!.state)

        now += 1
        assertEquals(PresenceState.RECENT, tracker.states(emptySet())[5]!!.state)
    }

    @Test
    fun recentExpiresAfterTwentyFourHours() {
        tracker.noteActivity(5)

        now += PresenceTracker.RECENT_WINDOW_MS
        assertEquals(PresenceState.RECENT, tracker.states(emptySet())[5]!!.state)

        now += 1
        // Past the window the entry is dropped: the node reads as UNKNOWN.
        assertFalse(tracker.states(emptySet()).containsKey(5))
    }

    @Test
    fun newActivityRefreshesTheWindow() {
        tracker.noteActivity(5)
        now += PresenceTracker.ONLINE_WINDOW_MS + 1_000 // would be RECENT now...
        tracker.noteActivity(5)                         // ...but a fresh frame resets it.
        assertEquals(PresenceState.ONLINE, tracker.states(emptySet())[5]!!.state)
    }

    @Test
    fun relayedFramesAlsoCountAsActivity() {
        // Presence is keyed by the ORIGIN node id, so frames seen through a relay
        // still mark their origin as alive; the neighbor flag stays separate.
        tracker.noteActivity(9)

        val presence = tracker.states(setOf(3)) // 9 is not one of our neighbors
        assertEquals(PresenceState.ONLINE, presence[9]!!.state)
        assertFalse(presence[9]!!.isDirectNeighbor)
    }
}
