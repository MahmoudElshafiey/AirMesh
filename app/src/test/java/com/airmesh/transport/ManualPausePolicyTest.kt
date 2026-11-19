package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ManualPausePolicy] (TASK S1 part 2). The policy is pure: every method takes an
 * explicit timestamp, so no fake clock object is needed — the test just advances a number.
 */
class ManualPausePolicyTest {

    @Test
    fun `discover pauses for exactly 45 seconds`() {
        val p = ManualPausePolicy()
        assertTrue("DISCOVER must start a pause", p.noteManualAction(ManualPauseAction.DISCOVER, 0L))
        assertEquals("deadline is +45 s", 45_000L, p.pauseUntilMs)
        assertTrue("paused at +44.999 s", p.isPaused(44_999L))
        assertFalse("not paused at exactly +45 s", p.isPaused(45_000L))
        assertEquals("full countdown at t=0", 45_000L, p.pauseRemainingMs(0L))
        assertEquals("1 ms left at +44.999 s", 1L, p.pauseRemainingMs(44_999L))
        assertEquals("no countdown after expiry", 0L, p.pauseRemainingMs(45_000L))
    }

    @Test
    fun `disconnect pauses for 120 seconds`() {
        val p = ManualPausePolicy()
        assertTrue(p.noteManualAction(ManualPauseAction.DISCONNECT, 0L))
        assertEquals("deadline is +120 s", 120_000L, p.pauseUntilMs)
        assertTrue("paused at +119.999 s", p.isPaused(119_999L))
        assertFalse("not paused at exactly +120 s", p.isPaused(120_000L))
    }

    @Test
    fun `repeated actions extend the deadline but never past the 3 minute cap`() {
        val p = ManualPausePolicy()
        // Each action lands just before the previous deadline, so one episode keeps running.
        assertTrue(p.noteManualAction(ManualPauseAction.DISCOVER, 0L))          // until 45 s
        assertEquals("second action extends", true, p.noteManualAction(ManualPauseAction.CONNECT, 44_999L))
        assertEquals("extended to +89.999 s", 89_999L, p.pauseUntilMs)
        assertTrue(p.noteManualAction(ManualPauseAction.RETRY_TCP, 89_998L))    // until 134.998 s
        assertEquals(134_998L, p.pauseUntilMs)
        assertTrue(p.noteManualAction(ManualPauseAction.CREATE_GROUP, 134_997L)) // until 179.997 s
        assertEquals(179_997L, p.pauseUntilMs)
        // The next action would reach +224.996 s; the cap must clamp it to exactly +180 s.
        assertTrue(p.noteManualAction(ManualPauseAction.DISCOVER, 179_996L))
        assertEquals("cap clamps the deadline", MANUAL_PAUSE_CAP_MS, p.pauseUntilMs)
        assertTrue("paused at +179.999 s", p.isPaused(179_999L))
        assertFalse("not paused past the cap", p.isPaused(MANUAL_PAUSE_CAP_MS))
    }

    @Test
    fun `a fresh episode restarts the cap window`() {
        val p = ManualPausePolicy()
        assertTrue(p.noteManualAction(ManualPauseAction.DISCOVER, 0L)) // until 45 s
        assertFalse("expired by t=200 s", p.isPaused(200_000L))
        assertTrue(p.noteManualAction(ManualPauseAction.DISCOVER, 200_000L))
        assertEquals("episode origin restarts", 200_000L, p.originMs)
        assertEquals("new deadline +45 s from the new origin", 245_000L, p.pauseUntilMs)
        assertTrue(p.isPaused(244_999L))
        assertFalse(p.isPaused(245_000L))
    }

    @Test
    fun `an in-flight connect holds the pause past the base deadline up to the cap`() {
        val p = ManualPausePolicy()
        assertTrue(p.noteManualAction(ManualPauseAction.CONNECT, 0L)) // until 45 s
        p.noteConnectRequested(0L)
        assertTrue("base window still pauses", p.isPaused(44_999L))
        // The base deadline (45 s) has passed but the connect is still in flight: paused.
        assertTrue("in-flight connect holds past +45 s", p.isPaused(60_000L))
        assertTrue("still held at +179.999 s", p.isPaused(179_999L))
        assertFalse("the cap ends it even while in flight", p.isPaused(MANUAL_PAUSE_CAP_MS))
        assertEquals("banner stays visible while waiting for the settle", 1L, p.pauseRemainingMs(60_000L))
    }

    @Test
    fun `settling a connected connect resumes 10 seconds later`() {
        val p = ManualPausePolicy()
        assertTrue(p.noteManualAction(ManualPauseAction.CONNECT, 0L))
        p.noteConnectRequested(0L)
        assertTrue("settle reports it acted", p.noteConnectSettled(30_000L, connected = true))
        assertFalse("no longer in flight", p.connectInFlight)
        assertEquals("resume at settle +10 s", 40_000L, p.pauseUntilMs)
        assertTrue(p.isPaused(39_999L))
        assertFalse(p.isPaused(40_000L))
        assertEquals("reason is connected", "connected", p.takeResumeReason())
    }

    @Test
    fun `settling a failed connect resumes 10 seconds later with reason failed`() {
        val p = ManualPausePolicy()
        assertTrue(p.noteManualAction(ManualPauseAction.CONNECT, 0L))
        p.noteConnectRequested(0L)
        assertTrue(p.noteConnectSettled(25_000L, connected = false))
        assertEquals("resume at settle +10 s", 35_000L, p.pauseUntilMs)
        assertEquals("reason is failed", "failed", p.takeResumeReason())
    }

    @Test
    fun `the settle deadline respects the cap`() {
        val p = ManualPausePolicy()
        assertTrue(p.noteManualAction(ManualPauseAction.CONNECT, 0L))
        p.noteConnectRequested(0L)
        // Settle at +175 s: settle+10 s would be +185 s, the cap clamps it to +180 s.
        assertTrue(p.noteConnectSettled(175_000L, connected = true))
        assertEquals("settle clamped by the cap", MANUAL_PAUSE_CAP_MS, p.pauseUntilMs)
        assertTrue(p.isPaused(179_999L))
        assertFalse(p.isPaused(MANUAL_PAUSE_CAP_MS))
    }

    @Test
    fun `settle without an in-flight connect is a no-op`() {
        val p = ManualPausePolicy()
        assertTrue(p.noteManualAction(ManualPauseAction.DISCOVER, 0L)) // until 45 s
        assertFalse("nothing was in flight", p.noteConnectSettled(10_000L, connected = true))
        assertEquals("deadline untouched", 45_000L, p.pauseUntilMs)
        assertEquals("reason untouched", "timeout", p.takeResumeReason())
    }

    @Test
    fun `resumeByUser clears everything including an in-flight connect`() {
        val p = ManualPausePolicy()
        assertTrue(p.noteManualAction(ManualPauseAction.CONNECT, 0L))
        p.noteConnectRequested(0L)
        assertTrue("was active", p.resumeByUser(5_000L))
        assertEquals(0L, p.pauseUntilMs)
        assertNull(p.originMs)
        assertFalse(p.connectInFlight)
        assertFalse("nothing left to pause", p.isPaused(MANUAL_PAUSE_CAP_MS + 1L))

        val fresh = ManualPausePolicy()
        assertFalse("no active pause on a fresh policy", fresh.resumeByUser(1_000L))
    }

    @Test
    fun `takeResumeReason resets to timeout`() {
        val p = ManualPausePolicy()
        assertTrue(p.noteManualAction(ManualPauseAction.CONNECT, 0L))
        p.noteConnectRequested(0L)
        p.noteConnectSettled(30_000L, connected = true)
        assertEquals("connected", p.takeResumeReason())
        assertEquals("second read resets to timeout", "timeout", p.takeResumeReason())
    }

    @Test
    fun `an action while paused does not restart the episode origin`() {
        val p = ManualPausePolicy()
        assertTrue(p.noteManualAction(ManualPauseAction.DISCOVER, 0L))
        assertTrue("extends within the same episode", p.noteManualAction(ManualPauseAction.RETRY_TCP, 40_000L))
        assertEquals("origin stays at the first action", 0L, p.originMs)
        assertEquals("extended to +85 s (still under the cap)", 85_000L, p.pauseUntilMs)
    }

    @Test
    fun `a shorter action does not shorten a longer pause`() {
        val p = ManualPausePolicy()
        assertTrue(p.noteManualAction(ManualPauseAction.DISCONNECT, 0L)) // until 120 s
        assertFalse("45 s from now is inside the 120 s window", p.noteManualAction(ManualPauseAction.DISCOVER, 10_000L))
        assertEquals("longer deadline kept", 120_000L, p.pauseUntilMs)
    }
}
