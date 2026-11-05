package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the "group gone" debounce logic (pure, no real clock). */
class GroupPresenceTrackerTest {

    @Test
    fun transientFalseWithinGraceDoesNotClose() {
        val tracker = GroupPresenceTracker() // 2000ms grace
        assertFalse(tracker.onReport(false, nowMs = 0))
        assertFalse("a blip inside the grace period must not close", tracker.onReport(false, nowMs = 500))
    }

    @Test
    fun persistentFalseClosesExactlyAtTheGraceBoundary() {
        val tracker = GroupPresenceTracker()
        assertFalse(tracker.onReport(false, nowMs = 0))
        assertFalse("just before the grace period must keep the link", tracker.onReport(false, nowMs = 1_999))
        assertTrue("at the grace boundary the group counts as gone", tracker.onReport(false, nowMs = 2_000))
    }

    @Test
    fun repeatedFalsesDoNotRestartTheWindow() {
        val tracker = GroupPresenceTracker()
        assertFalse(tracker.onReport(false, nowMs = 0))
        assertFalse(tracker.onReport(false, nowMs = 1_500))
        // Anchored to the first report at t=0: confirmed at t=2500, not t=3500.
        assertTrue(tracker.onReport(false, nowMs = 2_500))
    }

    @Test
    fun formedReportCancelsThePendingEpisode() {
        val tracker = GroupPresenceTracker()
        assertFalse(tracker.onReport(false, nowMs = 0))
        assertFalse(tracker.onReport(true, nowMs = 1_000))

        // A new episode starts fresh at t=1001 and needs its own full grace period.
        assertFalse(tracker.onReport(false, nowMs = 1_001))
        assertFalse(tracker.onReport(false, nowMs = 3_000))
        assertTrue(tracker.onReport(false, nowMs = 3_001))
    }

    @Test
    fun pendingGraceRemainingCountsDownAndClears() {
        val tracker = GroupPresenceTracker()
        assertNull(tracker.pendingGraceRemaining(nowMs = 0))

        tracker.onReport(false, nowMs = 0)
        assertEquals(2_000L, tracker.pendingGraceRemaining(nowMs = 0))
        assertEquals(500L, tracker.pendingGraceRemaining(nowMs = 1_500))
        assertEquals(0L, tracker.pendingGraceRemaining(nowMs = 2_500))

        tracker.onReport(true, nowMs = 3_000)
        assertNull(tracker.pendingGraceRemaining(nowMs = 3_000))
    }

    @Test
    fun resetClearsThePendingEpisode() {
        val tracker = GroupPresenceTracker()
        tracker.onReport(false, nowMs = 0)
        tracker.reset()
        assertNull(tracker.pendingGraceRemaining(nowMs = 1_000))
        // The next episode starts fresh.
        assertFalse(tracker.onReport(false, nowMs = 1_000))
        assertTrue(tracker.onReport(false, nowMs = 3_000))
    }

    @Test
    fun customGracePeriodIsRespected() {
        val tracker = GroupPresenceTracker(graceMs = 500)
        assertFalse(tracker.onReport(false, nowMs = 0))
        assertFalse(tracker.onReport(false, nowMs = 499))
        assertTrue(tracker.onReport(false, nowMs = 500))
    }

    @Test
    fun pureFunctionChecksTheBoundary() {
        assertFalse(groupGoneConfirmed(firstFalseMs = 1_000, nowMs = 2_999))
        assertTrue(groupGoneConfirmed(firstFalseMs = 1_000, nowMs = 3_000))
        // Custom grace overrides the default.
        assertFalse(groupGoneConfirmed(firstFalseMs = 0, nowMs = 500, graceMs = 2_000))
        assertTrue(groupGoneConfirmed(firstFalseMs = 0, nowMs = 1_000, graceMs = 500))
    }
}
