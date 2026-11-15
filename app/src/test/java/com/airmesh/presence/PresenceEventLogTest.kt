package com.airmesh.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake-clock tests for the debounced presence transition log (TASK U5 part 1). */
class PresenceEventLogTest {

    private var now = 0L
    private val log = PresenceEventLog(clock = { now }) // default 5 s debounce, cap 20

    /** Reports [state] at the current fake time. */
    private fun note(nodeId: Int, state: PresenceState) = log.note(nodeId, state)

    /** Advances the clock and reports again — what the service's 5 s tick does. */
    private fun advance(ms: Long, nodeId: Int, state: PresenceState) {
        now += ms
        note(nodeId, state)
    }

    /** Brings a node to a confirmed ONLINE side (first sighting is adopted silently). */
    private fun confirmOnline(nodeId: Int) {
        note(nodeId, PresenceState.ONLINE_DIRECT)
        advance(5_000L, nodeId, PresenceState.ONLINE_DIRECT) // stable; no event expected
    }

    @Test
    fun theFirstSightingIsAdoptedWithoutAnEvent() {
        confirmOnline(7)
        assertTrue("adopting a side is not a transition", log.eventsFor(7).isEmpty())
    }

    @Test
    fun goingOfflineEmitsWentOutOfRangeOnlyAfterFiveSeconds() {
        confirmOnline(7)
        now += 10_000L
        val dropAt = now
        note(7, PresenceState.OUT_OF_RANGE) // pending since dropAt
        advance(4_999L, 7, PresenceState.OUT_OF_RANGE)
        assertTrue("not held long enough yet", log.eventsFor(7).isEmpty())
        advance(1L, 7, PresenceState.OUT_OF_RANGE) // now exactly 5 s after the drop
        val events = log.eventsFor(7)
        assertEquals(1, events.size)
        assertEquals(PresenceTransition.WENT_OUT_OF_RANGE, events[0].transition)
        // The event is stamped with when the state STARTED holding, not when it was confirmed.
        assertEquals(dropAt, events[0].atMs)
    }

    @Test
    fun aBriefDropIsIgnored() {
        confirmOnline(7)
        now += 10_000L
        note(7, PresenceState.OUT_OF_RANGE) // pending...
        advance(2_000L, 7, PresenceState.ONLINE_DIRECT) // ...back before it held 5 s
        advance(10_000L, 7, PresenceState.ONLINE_DIRECT)
        assertTrue("brief drops must not produce events", log.eventsFor(7).isEmpty())
    }

    @Test
    fun checkingFlickerIsIgnored() {
        confirmOnline(7)
        now += 10_000L
        note(7, PresenceState.CHECKING) // neutral: starts nothing
        advance(1_000L, 7, PresenceState.ONLINE_DIRECT)
        assertTrue(log.eventsFor(7).isEmpty())

        // Even a CHECKING state held past the debounce window confirms nothing by itself.
        now += 10_000L
        note(7, PresenceState.CHECKING)
        advance(6_000L, 7, PresenceState.CHECKING)
        assertTrue("CHECKING alone is not a transition", log.eventsFor(7).isEmpty())
    }

    @Test
    fun comingBackEmitsBackOnlineOnlyAfterFiveSeconds() {
        confirmOnline(7)
        now += 10_000L
        note(7, PresenceState.OUT_OF_RANGE)
        advance(5_000L, 7, PresenceState.OUT_OF_RANGE) // WENT_OUT_OF_RANGE confirmed
        assertEquals(1, log.eventsFor(7).size)

        now += 10_000L
        val backAt = now
        note(7, PresenceState.ONLINE_RELAYED) // pending BACK_ONLINE since backAt
        advance(4_999L, 7, PresenceState.ONLINE_RELAYED)
        assertEquals("not confirmed yet", 1, log.eventsFor(7).size)
        advance(1L, 7, PresenceState.ONLINE_DIRECT)
        val events = log.eventsFor(7)
        assertEquals(2, events.size)
        assertEquals(PresenceTransition.BACK_ONLINE, events[1].transition)
        assertEquals(backAt, events[1].atMs)
    }

    @Test
    fun aNeutralStateNeitherConfirmsNorCancelsAPendingTransition() {
        confirmOnline(7)
        now += 10_000L
        val dropAt = now
        note(7, PresenceState.OUT_OF_RANGE) // pending since dropAt
        advance(2_000L, 7, PresenceState.CHECKING) // neutral: the wait keeps running
        advance(4_000L, 7, PresenceState.LastSeen(dropAt)) // offline again at +6 s total
        val events = log.eventsFor(7)
        assertEquals(1, events.size)
        assertEquals(PresenceTransition.WENT_OUT_OF_RANGE, events[0].transition)
        assertEquals("stamped with the original drop time", dropAt, events[0].atMs)
    }

    @Test
    fun nodesAreTrackedIndependently() {
        confirmOnline(1)
        confirmOnline(2)
        now += 10_000L
        note(1, PresenceState.OUT_OF_RANGE)
        advance(5_000L, 1, PresenceState.OUT_OF_RANGE) // node 1 went out of range
        assertTrue("node 2 was never touched", log.eventsFor(2).isEmpty())
        assertEquals(1, log.eventsFor(1).size)
    }

    @Test
    fun theCapKeepsTheTwentyNewestEventsPerNode() {
        confirmOnline(7)
        // Drive 25 full transitions: out of range, then back online, each confirmed after 5 s.
        val offlineTimes = mutableListOf<Long>()
        repeat(25) {
            now += 10_000L
            offlineTimes.add(now)
            note(7, PresenceState.OUT_OF_RANGE)
            advance(5_000L, 7, PresenceState.OUT_OF_RANGE) // WENT_OUT_OF_RANGE at that time
            now += 10_000L
            note(7, PresenceState.ONLINE_DIRECT)
            advance(5_000L, 7, PresenceState.ONLINE_DIRECT) // BACK_ONLINE
        }
        val events = log.eventsFor(7)
        assertEquals(PresenceEventLog.MAX_EVENTS_PER_NODE, events.size)
        // 25 transitions x 2 events = 50 logged; the first thirty (transitions 1..15) are
        // dropped, so the oldest kept event is transition #16's WENT_OUT_OF_RANGE.
        assertEquals(PresenceTransition.WENT_OUT_OF_RANGE, events.first().transition)
        assertEquals(offlineTimes[15], events.first().atMs)
        assertEquals(PresenceTransition.BACK_ONLINE, events.last().transition)
    }

    @Test
    fun theSnapshotIsImmutableAndComplete() {
        confirmOnline(1)
        confirmOnline(2) // node 2 must be seen online first: a first sighting is adopted silently
        now += 10_000L
        note(2, PresenceState.OUT_OF_RANGE)
        advance(5_000L, 2, PresenceState.OUT_OF_RANGE)
        val first = log.snapshot()
        assertTrue(first[1].isNullOrEmpty())
        assertEquals(1, first[2]?.size)
        // Each snapshot returns fresh list copies: later notes must not leak into an old one.
        now += 10_000L
        note(2, PresenceState.ONLINE_DIRECT)
        advance(5_000L, 2, PresenceState.ONLINE_DIRECT)
        val second = log.snapshot()
        assertEquals(1, first[2]?.size) // the old snapshot is frozen
        assertEquals(2, second[2]?.size)
    }

    @Test
    fun theDefaultsAreFiveSecondsAndTwentyEvents() {
        assertEquals(5_000L, PresenceEventLog.DEBOUNCE_MS)
        assertEquals(20, PresenceEventLog.MAX_EVENTS_PER_NODE)
    }
}
