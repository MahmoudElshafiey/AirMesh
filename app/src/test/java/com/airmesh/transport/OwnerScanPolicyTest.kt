package com.airmesh.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the owner merge-scan policy (TASK M1 part 2). */
class OwnerScanPolicyTest {

    private var now = 0L
    private val policy = OwnerScanPolicy()

    /** Ask the policy at [now] with an empty group unless told otherwise. */
    private fun scan(clients: Int = 0, mergeEnabled: Boolean = true, mergeInProgress: Boolean = false) =
        policy.shouldScan(clients, mergeEnabled, mergeInProgress, now)

    @Test
    fun `the first call fires immediately`() {
        assertTrue(scan())
        // The empty-group interval has not elapsed yet.
        now = 14_999L
        assertFalse(scan())
    }

    @Test
    fun `an empty owner scans every fifteen seconds`() {
        assertTrue(scan())          // t = 0
        now = 14_999L
        assertFalse(scan())
        now = 15_000L
        assertTrue(scan())
        now = 29_999L
        assertFalse(scan())
        now = 30_000L
        assertTrue(scan())
    }

    @Test
    fun `an owner with clients waits for the ninety second cycle`() {
        assertTrue(scan(clients = 2)) // t = 0, first call fires immediately
        now = 89_999L
        assertFalse("no scan before the cycle is due", scan(clients = 2))
        now = 90_000L
        assertTrue("the window opens at the cycle boundary", scan(clients = 2))
    }

    @Test
    fun `a missed window rolls forward one full cycle`() {
        assertTrue(scan(clients = 1)) // t = 0, anchor set
        now = 98_001L                 // just past the 8 s window (90 s .. 98 s)
        assertFalse("the whole window was missed", scan(clients = 1))
        now = 179_999L                // next cycle is due at 90 s + 90 s
        assertFalse(scan(clients = 1))
        now = 180_000L
        assertTrue("the rolled-forward window fires", scan(clients = 1))
    }

    @Test
    fun `a join suppresses scans for ten seconds but not the rest of the window`() {
        assertTrue(scan(clients = 1)) // t = 0, anchor set; cycle due at 90 s
        policy.noteClientJoined(85_000L)
        now = 90_000L
        assertFalse("still within ten seconds of the join", scan(clients = 1))
        now = 94_999L
        assertFalse(scan(clients = 1))
        now = 95_000L
        assertTrue("suppression expired inside the window: it still fires", scan(clients = 1))
    }

    @Test
    fun `a join covering the whole window rolls to the next cycle`() {
        assertTrue(scan(clients = 1)) // t = 0, anchor set; window is 90 s .. 98 s
        policy.noteClientJoined(93_000L) // suppression runs until 103 s — past the window
        now = 98_001L
        assertFalse("window missed while suppressed", scan(clients = 1))
        now = 179_999L
        assertFalse(scan(clients = 1))
        now = 180_000L
        assertTrue("the next cycle fires once the suppression is long over", scan(clients = 1))
    }

    @Test
    fun `a merge in progress never scans`() {
        now = 0L
        assertFalse(scan(mergeInProgress = true))
        now = 60_000L
        assertFalse(scan(mergeInProgress = true))
        // Once the merge ends, the policy is still fresh: it fires on first use.
        assertTrue(scan(mergeInProgress = false))
    }

    @Test
    fun `the flag off never scans`() {
        now = 0L
        assertFalse(scan(mergeEnabled = false))
        now = 60_000L
        assertFalse(scan(mergeEnabled = false))
    }
}
