package com.airmesh.presence

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fake-clock tests for per-node evidence bookkeeping feeding [PresenceResolver] (TASK U5 part 2). */
class PresenceBookkeepingTest {

    private var now = 0L
    private val bookkeeping = PresenceBookkeeping(clock = { now })

    @Test
    fun aNewNeighborIsOnlineDirectImmediately() {
        bookkeeping.noteNeighbors(setOf(7))
        assertEquals(PresenceState.ONLINE_DIRECT, bookkeeping.states()[7])
    }

    @Test
    fun anIndividualDropWhileConnectedIsOutOfRangeWithoutWaiting() {
        bookkeeping.noteNeighbors(setOf(7, 8))
        now += 1_000L
        bookkeeping.noteActivity(7)
        assertEquals(PresenceState.ONLINE_DIRECT, bookkeeping.states()[7])

        // Node 7 leaves the set while we are still connected to node 8: OUT_OF_RANGE at once.
        now += 5_000L
        bookkeeping.noteNeighbors(setOf(8))
        val states = bookkeeping.states()
        assertEquals(PresenceState.OUT_OF_RANGE, states[7])
        assertEquals(PresenceState.ONLINE_DIRECT, states[8])
    }

    @Test
    fun aRelayedFrameAfterADropKeepsThemOnlineByAge() {
        bookkeeping.noteNeighbors(setOf(7, 8))
        now += 1_000L
        bookkeeping.noteActivity(7)
        bookkeeping.states() // publish the online round

        now += 5_000L
        bookkeeping.noteNeighbors(setOf(8)) // 7 drops out of range
        assertEquals(PresenceState.OUT_OF_RANGE, bookkeeping.states()[7])

        // A relayed frame strictly after the drop: back to online by its age...
        now += 10_000L
        bookkeeping.noteActivity(7)
        assertEquals(PresenceState.ONLINE_RELAYED, bookkeeping.states()[7])

        // ...and ages into checking once it is exactly 60 s old (inclusive boundary).
        now += 60_000L
        assertEquals(PresenceState.CHECKING, bookkeeping.states()[7])
    }

    @Test
    fun zeroNeighborsMakesEverybodyNotConnected() {
        bookkeeping.noteNeighbors(setOf(7, 8))
        now += 1_000L
        bookkeeping.noteActivity(7)
        bookkeeping.states()

        now += 5_000L
        bookkeeping.noteNeighbors(emptySet())
        val states = bookkeeping.states()
        assertEquals(PresenceState.NOT_CONNECTED, states[7])
        assertEquals(PresenceState.NOT_CONNECTED, states[8])
    }

    @Test
    fun ownLinkLossAndRestoreGivesCheckingGraceThenLastSeen() {
        bookkeeping.noteNeighbors(setOf(7))
        now += 1_000L
        bookkeeping.noteActivity(7)
        assertEquals(PresenceState.ONLINE_DIRECT, bookkeeping.states()[7]) // online before the loss

        // Our whole link goes down; we come back connected to someone else.
        now += 5_000L
        bookkeeping.noteNeighbors(emptySet())
        bookkeeping.states() // publishes NOT_CONNECTED for everyone during the outage
        now += 10_000L
        bookkeeping.noteNeighbors(setOf(9))

        // Node 7's only evidence predates the restore and they were online before: CHECKING.
        assertEquals(PresenceState.CHECKING, bookkeeping.states()[7])

        // After one announce cycle plus slack the same stale evidence is simply LastSeen.
        now += PresenceResolver.DEFAULT_ANNOUNCE_INTERVAL_MS + PresenceResolver.RESTORE_GRACE_SLACK_MS
        assertEquals(PresenceState.LastSeen(1_000L), bookkeeping.states()[7])
    }

    @Test
    fun aRejoinClearsTheOldDropMarkerSoTheSecondDropIsImmediate() {
        bookkeeping.noteNeighbors(setOf(7, 8))
        now += 1_000L
        bookkeeping.noteActivity(7)
        bookkeeping.states()

        // First drop and rejoin.
        now += 5_000L
        bookkeeping.noteNeighbors(setOf(8))
        assertEquals(PresenceState.OUT_OF_RANGE, bookkeeping.states()[7])
        now += 5_000L
        bookkeeping.noteNeighbors(setOf(7, 8))
        now += 1_000L
        bookkeeping.noteActivity(7) // a fresh frame while the socket is open again
        assertEquals(PresenceState.ONLINE_DIRECT, bookkeeping.states()[7])

        // Second drop: still immediate OUT_OF_RANGE — the stale first-drop marker must not let
        // them linger in the age buckets.
        now += 5_000L
        bookkeeping.noteNeighbors(setOf(8))
        assertEquals(PresenceState.OUT_OF_RANGE, bookkeeping.states()[7])
    }

    @Test
    fun bootingWithNoNeighborsThenConnectingTracksRealLossesNormally() {
        bookkeeping.noteNeighbors(emptySet()) // boot: no phantom outage is recorded
        now += 1_000L
        bookkeeping.noteNeighbors(setOf(7))
        bookkeeping.noteActivity(7)
        assertEquals(PresenceState.ONLINE_DIRECT, bookkeeping.states()[7])

        now += 5_000L
        bookkeeping.noteNeighbors(emptySet()) // a REAL loss this time
        bookkeeping.states()
        now += 10_000L
        bookkeeping.noteNeighbors(setOf(8)) // restore with someone else
        assertEquals(PresenceState.CHECKING, bookkeeping.states()[7])
    }

    @Test
    fun aRelayedOnlyNodeAgesThroughTheBuckets() {
        bookkeeping.noteNeighbors(setOf(8)) // we are connected to someone else
        now += 1_000L
        bookkeeping.noteActivity(7) // relayed frame from 7, which is not our neighbor
        assertEquals(PresenceState.ONLINE_RELAYED, bookkeeping.states()[7])

        now = 61_000L // the frame is exactly 60 s old: checking (inclusive boundary)
        assertEquals(PresenceState.CHECKING, bookkeeping.states()[7])
        now = 101_000L // exactly 100 s old: still checking
        assertEquals(PresenceState.CHECKING, bookkeeping.states()[7])
        now = 101_001L
        assertEquals(PresenceState.LastSeen(1_000L), bookkeeping.states()[7])
    }
}
