package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** JVM tests for the owner merge decision (TASK M1 part 3). */
class MergeDeciderTest {

    private var now = 0L

    private fun decider(myId: Int, myToken: Int = 0x1000, seed: Long = 1) =
        MergeDecider(myId, myToken, Random(seed))

    /** A foreign owner seen "now" unless [lastSeenMs] says otherwise. */
    private fun sighting(id: Int, token: Int = 0x2000, clients: Int, lastSeenMs: Long? = null) =
        MergeOwnerSighting("BB:00:00:00:00:$id", id, token, clients, lastSeenMs ?: now)

    /** Step the clock forward one second at a time until [decide] yields (or 12 s pass). */
    private fun settleToYield(d: MergeDecider, myClients: Int, other: () -> MergeOwnerSighting): Pair<MergeDecision, Long> {
        var decision = d.decide(now, myClients, null, other())
        while (decision !is MergeDecision.Yield && now <= 12_000L) {
            now += 1_000L
            decision = d.decide(now, myClients, null, other())
        }
        return decision to now
    }

    @Test
    fun `the bigger group stays even with the lower rank`() {
        val d = decider(5) // I have the lower id but more clients
        now = 0L
        assertEquals(MergeDecision.Stay, d.decide(now, myClients = 3, null, sighting(9, clients = 1)))
        now = 6_000L
        assertEquals("still stays after a second sighting", MergeDecision.Stay,
            d.decide(now, myClients = 3, null, sighting(9, clients = 1)))
    }

    @Test
    fun `the smaller group yields to the bigger one after settling`() {
        val d = decider(9) // I have the higher id but fewer clients
        now = 0L
        assertEquals(MergeDecision.Wait("seen in one scan only"),
            d.decide(now, myClients = 1, null, sighting(5, clients = 3)))

        now = 6_000L // second sighting, 6 s apart: preconditions pass, settle starts
        assertTrue(d.decide(now, myClients = 1, null, sighting(5, clients = 3)) is MergeDecision.Wait)
        now = 7_999L
        assertTrue("no yield before the 2 s minimum settle",
            d.decide(now, myClients = 1, null, sighting(5, clients = 3)) is MergeDecision.Wait)

        val (decision, yieldedAt) = settleToYield(d, 1) { sighting(5, clients = 3) }
        assertEquals(MergeDecision.Yield("BB:00:00:00:00:5", 5), decision)
        assertTrue("yielded within the 2–6 s settle window", yieldedAt in 8_000L..12_000L)
    }

    @Test
    fun `equal counts go to the higher node id`() {
        val lower = decider(5) // same client count, lower id: I yield
        now = 0L
        assertTrue(lower.decide(now, myClients = 2, null, sighting(9, clients = 2)) is MergeDecision.Wait)
        now = 6_000L
        val (decision, _) = settleToYield(lower, 2) { sighting(9, clients = 2) }
        assertEquals(MergeDecision.Yield("BB:00:00:00:00:9", 9), decision)

        val higher = decider(9) // same client count, higher id: I stay
        now = 0L
        assertEquals(MergeDecision.Stay, higher.decide(now, myClients = 2, null, sighting(5, clients = 2)))
    }

    @Test
    fun `a stale advert never yields`() {
        val d = decider(9) // fewer clients: would yield if the ad were fresh
        now = 100_000L
        assertTrue(d.decide(now, myClients = 1, null, sighting(5, clients = 3)) is MergeDecision.Wait)

        now = 219_999L // ad is 119.999 s old: still fresh, settle starts
        assertEquals(MergeDecision.Wait("settling before yield"),
            d.decide(now, myClients = 1, null, sighting(5, clients = 3, lastSeenMs = 100_000L)))

        now = 220_000L // exactly 120 s old: stale — the settle is cancelled
        assertEquals(MergeDecision.Wait("stale advert"),
            d.decide(now, myClients = 1, null, sighting(5, clients = 3, lastSeenMs = 100_000L)))
    }

    @Test
    fun `one sighting is not enough and near-duplicates count once`() {
        val d = decider(9)
        now = 0L
        assertTrue(d.decide(now, myClients = 1, null, sighting(5, clients = 3)) is MergeDecision.Wait)

        now = 5_999L // less than 6 s after the first: still one sighting
        assertEquals(MergeDecision.Wait("seen in one scan only"),
            d.decide(now, myClients = 1, null, sighting(5, clients = 3)))

        now = 6_000L // exactly 6 s after the first: second sighting, settle starts
        assertEquals(MergeDecision.Wait("settling before yield"),
            d.decide(now, myClients = 1, null, sighting(5, clients = 3)))
    }

    @Test
    fun `an unstable client count delays the yield`() {
        val d = decider(9)
        now = 0L
        assertTrue(d.decide(now, myClients = 1, null, sighting(5, clients = 3)) is MergeDecision.Wait)

        now = 6_000L // second sighting, but my count changed only 2 s ago
        assertEquals(MergeDecision.Wait("client count unstable"),
            d.decide(now, myClients = 1, myClientsChangedAtMs = 4_000L, other = sighting(5, clients = 3)))

        now = 8_999L // changed 4.999 s ago: still unstable
        assertEquals(MergeDecision.Wait("client count unstable"),
            d.decide(now, myClients = 1, myClientsChangedAtMs = 4_000L, other = sighting(5, clients = 3)))

        now = 9_000L // changed exactly 5 s ago: stable, settle starts
        assertEquals(MergeDecision.Wait("settling before yield"),
            d.decide(now, myClients = 1, myClientsChangedAtMs = 4_000L, other = sighting(5, clients = 3)))
    }

    @Test
    fun `the yield cooldown blocks a second yield for 120 s`() {
        val d = decider(9)
        now = 0L
        assertTrue(d.decide(now, myClients = 1, null, sighting(5, clients = 3)) is MergeDecision.Wait)
        now = 6_000L
        val (_, yieldedAt) = settleToYield(d, 1) { sighting(5, clients = 3) }

        // A different, bigger owner appears right after the yield: still cooling down.
        now = yieldedAt + 1_000L
        val cooled = d.decide(now, myClients = 1, null, sighting(7, clients = 4)) as MergeDecision.Wait
        assertTrue(cooled.reason.contains("yield cooldown"))

        // Exactly 120 s after the yield: allowed again (settle starts for the new target).
        now = yieldedAt + MergeDecider.YIELD_COOLDOWN_MS
        assertEquals(MergeDecision.Wait("settling before yield"),
            d.decide(now, myClients = 1, null, sighting(7, clients = 4)))
    }

    @Test
    fun `a changed picture cancels the settle`() {
        val d = decider(9) // I have 1 client; the other has 3
        now = 0L
        assertTrue(d.decide(now, myClients = 1, null, sighting(5, clients = 3)) is MergeDecision.Wait)
        now = 6_000L
        assertEquals(MergeDecision.Wait("settling before yield"),
            d.decide(now, myClients = 1, null, sighting(5, clients = 3)))

        // A client joins me while settling: the picture changed, start over.
        now = 7_000L
        assertEquals(MergeDecision.Wait("picture changed during settle"),
            d.decide(now, myClients = 2, null, sighting(5, clients = 3)))

        // The next call restarts the settle for the new picture.
        now = 8_000L
        assertEquals(MergeDecision.Wait("settling before yield"),
            d.decide(now, myClients = 2, null, sighting(5, clients = 3)))
    }

    @Test
    fun `no foreign owner or a self-sighting means stay`() {
        val d = decider(7)
        now = 0L
        assertEquals(MergeDecision.Stay, d.decide(now, myClients = 3, null, other = null))
        assertEquals("my own ad is never a merge target", MergeDecision.Stay,
            d.decide(now, myClients = 3, null, sighting(7, clients = 9)))
    }

    @Test
    fun `strongestForeignOwner picks the biggest group then the highest rank`() {
        val me = 50
        val smallLow = AirMeshPeer("AA:00:00:00:00:01", 10, true, 0x1000, clients = 1)
        val bigMid = AirMeshPeer("BB:00:00:00:00:02", 20, true, 0x2000, clients = 3)
        val bigHigh = AirMeshPeer("CC:00:00:00:00:03", 30, true, 0x3000, clients = 3)
        val client = AirMeshPeer("DD:00:00:00:00:04", 40, false, 0x4000, clients = 9) // not an owner
        val self = AirMeshPeer("EE:00:00:00:00:05", me, true, 0x5000, clients = 99)   // myself

        assertEquals(bigHigh, strongestForeignOwner(listOf(smallLow, bigMid, bigHigh, client, self), me))
        assertNull(strongestForeignOwner(listOf(client, self), me))
    }
}
