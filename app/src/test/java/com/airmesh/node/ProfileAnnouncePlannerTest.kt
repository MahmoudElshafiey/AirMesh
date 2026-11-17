package com.airmesh.node

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Fake-clock tests for the PROFILE announcement planner (TASK R2 part 1): burst
 * timing, early cancel on receiving the peer's profile, the per-minute cap, the
 * new-origin cooldown and jitter bounds, and that nothing is scheduled without a
 * live neighbor. The virtual scheduler makes every delay exact; advances always
 * go one ms past each deadline because [TestCoroutineScheduler.advanceTimeBy]
 * only runs tasks whose deadline lies strictly before the final time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileAnnouncePlannerTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(UnconfinedTestDispatcher(scheduler))
    private val clock: () -> Long = { scheduler.currentTime }

    /** Virtual time of every announce that really went out. */
    private val firedAt = ArrayList<Long>()

    /** When false, the next [onAnnounce] reports "nothing was sent" (no name yet). */
    private var announceSucceeds = true

    /** The immediate path never suspends in these tests, so runBlocking is exact. */
    private fun tick(planner: ProfileAnnouncePlanner, hasLiveNeighbor: Boolean) {
        runBlocking { planner.considerPeriodicAnnounce(hasLiveNeighbor) }
    }

    private fun planner(
        burstDelaysMs: List<Long> = ProfileAnnouncePlanner.DEFAULT_BURST_DELAYS_MS,
        jitterRange: LongRange = 1_000L..3_000L,
        cooldownMs: Long = 10_000L,
        periodicIntervalMs: Long = ProfileAnnouncePlanner.DEFAULT_PERIODIC_INTERVAL_MS,
        capPerMinute: Int = ProfileAnnouncePlanner.MAX_ANNOUNCES_PER_MINUTE,
    ) = ProfileAnnouncePlanner(
        scope = scope,
        clock = clock,
        random = Random(42),
        onAnnounce = {
            val ok = announceSucceeds
            if (ok) firedAt += clock() // only real floods are recorded
            ok
        },
        burstDelaysMs = burstDelaysMs,
        newOriginJitterRangeMs = jitterRange,
        newOriginCooldownMs = cooldownMs,
        periodicIntervalMs = periodicIntervalMs,
        maxAnnouncesPerMinute = capPerMinute,
    )

    @Test
    fun theNeighborUpBurstFiresAtTheSpecifiedDelays() {
        val planner = planner()
        scheduler.advanceTimeBy(1) // t=1: link comes up
        planner.onNeighborUp(7)
        assertTrue(firedAt.isEmpty())

        scheduler.advanceTimeBy(301) // one past the +300 ms step
        assertEquals(listOf(301L), firedAt)
        scheduler.advanceTimeBy(1_700) // one past the +2 s step (absolute offsets)
        assertEquals(listOf(301L, 2_001L), firedAt)
        scheduler.advanceTimeBy(4_000)
        assertEquals(listOf(301L, 2_001L, 6_001L), firedAt)
        scheduler.advanceTimeBy(9_000)
        assertEquals(listOf(301L, 2_001L, 6_001L, 15_001L), firedAt)
    }

    @Test
    fun receivingThePeersProfileCancelsTheRestOfItsBurst() {
        val planner = planner()
        scheduler.advanceTimeBy(1)
        planner.onNeighborUp(7)
        scheduler.advanceTimeBy(301) // first shot went out
        assertEquals(listOf(301L), firedAt)

        planner.onProfileReceivedFrom(7)
        scheduler.advanceTimeBy(20_000) // covers the +2s/+6s/+15s steps
        assertEquals(listOf(301L), firedAt)
    }

    @Test
    fun aProfileFromAnotherNodeDoesNotCancelTheBurst() {
        val planner = planner()
        scheduler.advanceTimeBy(1)
        planner.onNeighborUp(7)
        planner.onProfileReceivedFrom(9) // different origin: no effect on 7's burst

        scheduler.advanceTimeBy(2_001)
        assertEquals(listOf(301L, 2_001L), firedAt)
    }

    @Test
    fun thePerMinuteCapLimitsAnnouncesAndSlidesWithTheWindow() {
        val planner = planner(capPerMinute = 2)
        scheduler.advanceTimeBy(1)
        runBlocking { planner.onProfileSetOrChanged() } // #1 at t=1
        scheduler.advanceTimeBy(1)
        runBlocking { planner.onProfileSetOrChanged() } // #2 at t=2
        runBlocking { planner.onProfileSetOrChanged() } // blocked: cap reached

        assertEquals(listOf(1L, 2L), firedAt)
        scheduler.advanceTimeBy(60_000) // both old entries leave the window (t=60_002)
        runBlocking { planner.onProfileSetOrChanged() } // #3 allowed again
        assertEquals(listOf(1L, 2L, 60_002L), firedAt)
    }

    @Test
    fun theCapAppliesToBurstStepsToo() {
        val planner = planner(capPerMinute = 1)
        scheduler.advanceTimeBy(1)
        planner.onNeighborUp(7)
        scheduler.advanceTimeBy(20_000) // whole burst inside one cap window

        assertEquals(listOf(301L), firedAt) // only the first step went out
    }

    @Test
    fun newOriginTriggersOneReannouncePerCooldown() {
        val planner = planner()
        scheduler.advanceTimeBy(1)
        planner.onNewOriginSeen(5)
        scheduler.advanceTimeBy(3_002) // one past the max jitter (deadline <= t=3_001)
        assertEquals(1, firedAt.size)

        planner.onNewOriginSeen(6) // within 10 s of the first schedule: ignored
        scheduler.advanceTimeBy(7_997)
        assertEquals(1, firedAt.size)

        planner.onNewOriginSeen(7) // cooldown elapsed (t=11_000): scheduled again
        scheduler.advanceTimeBy(3_001)
        assertEquals(2, firedAt.size)
    }

    @Test
    fun theJitterStaysWithinTheConfiguredBounds() {
        val planner = planner(jitterRange = 1_500L..2_500L)
        repeat(5) { i ->
            val before = clock()
            planner.onNewOriginSeen(i + 1)
            scheduler.advanceTimeBy(2_501) // one past the max jitter
            assertEquals("iteration $i", i + 1, firedAt.size)
            val delta = firedAt.last() - before
            assertTrue("jitter $delta outside bounds", delta in 1_500L..2_500L)
            scheduler.advanceTimeBy(10_000) // clear the cooldown for the next iteration
        }
    }

    @Test
    fun nothingIsScheduledWithoutALiveNeighbor() {
        val planner = planner()
        repeat(60) {
            tick(planner, hasLiveNeighbor = false)
            scheduler.advanceTimeBy(5_000) // three virtual minutes of ticks, no neighbor
        }
        assertTrue(firedAt.isEmpty())

        tick(planner, hasLiveNeighbor = true) // first tick with a live neighbor: gate open
        assertEquals(1, firedAt.size)
    }

    @Test
    fun thePeriodicGateHonorsTheConfiguredInterval() {
        val planner = planner(periodicIntervalMs = 30_000L)
        scheduler.advanceTimeBy(30_000) // lastAnnounceAt=0, so the gate is open at t=30s
        tick(planner, hasLiveNeighbor = true)
        assertEquals(listOf(30_000L), firedAt)

        tick(planner, hasLiveNeighbor = true) // same instant: still inside the interval
        assertEquals(listOf(30_000L), firedAt)
        scheduler.advanceTimeBy(29_999)
        tick(planner, hasLiveNeighbor = true)
        assertEquals(listOf(30_000L), firedAt)

        scheduler.advanceTimeBy(1) // exactly 30 s after the last real announce
        tick(planner, hasLiveNeighbor = true)
        assertEquals(listOf(30_000L, 60_000L), firedAt)
    }

    @Test
    fun aNoOpAnnounceDoesNotConsumeCapBudget() {
        val planner = planner(capPerMinute = 1)
        scheduler.advanceTimeBy(1)
        announceSucceeds = false // no name set yet: the flood is a no-op
        runBlocking { planner.onProfileSetOrChanged() }
        assertTrue(firedAt.isEmpty())

        announceSucceeds = true // name arrives at the same instant: slot was released
        runBlocking { planner.onProfileSetOrChanged() }
        assertEquals(listOf(1L), firedAt)
    }
}
