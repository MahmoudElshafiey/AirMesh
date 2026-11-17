package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * JVM tests for the escalating recovery supervisor (TASK R3 part 3): fake clock, seeded Random.
 * Levels fire at 20/45/90 s only while auto-mesh is on, Wi-Fi Direct is up and no live neighbor
 * exists; nothing fires while a connect/createGroup is in flight; level 3 repeats every 90 s plus
 * seeded jitter; an episode resets when the situation ends. Every test starts its episode with a
 * tick at t=0 so the thresholds are absolute.
 */
class RecoverySupervisorTest {

    private var now = 0L
    private val clock: () -> Long = { now }

    private fun supervisor(seed: Long = 1) = RecoverySupervisor(clock, Random(seed))

    /** A snapshot with [state] and the given group/client facts; auto on, P2P up, no neighbors. */
    private fun snap(
        state: AutoMeshState = AutoMeshState.Searching(now),
        autoOn: Boolean = true,
        p2pUp: Boolean = true,
        neighbors: Int = 0,
        groupFormed: Boolean = false,
        myClients: Int = 0,
    ) = RecoverySupervisor.Snapshot(
        autoMeshOn = autoOn,
        p2pEnabled = p2pUp,
        liveNeighborCount = neighbors,
        state = state,
        groupFormed = groupFormed,
        myClients = myClients,
    )

    /** Advances the fake clock by [ms] and ticks. */
    private fun tick(
        s: RecoverySupervisor,
        ms: Long,
        snapshot: () -> RecoverySupervisor.Snapshot,
    ): List<RecoveryAction> {
        now += ms
        return s.tick(snapshot())
    }

    // --- activity gate -----------------------------------------------------------

    @Test
    fun theSupervisorIsInactiveWhileAutoMeshIsOff() {
        val s = supervisor()
        repeat(200) { tick(s, 1_000L) { snap(autoOn = false) } }
        assertTrue("no actions while auto-mesh is off", !s.didAct)
    }

    @Test
    fun theSupervisorIsInactiveWhileWifiDirectIsDown() {
        val s = supervisor()
        repeat(200) { tick(s, 1_000L) { snap(p2pUp = false) } }
        assertTrue("no actions while Wi-Fi Direct is down", !s.didAct)
    }

    @Test
    fun aLiveNeighborKeepsTheSupervisorQuietAndResetsTheEpisode() {
        val s = supervisor()
        s.tick(snap()) // episode starts at t=0
        tick(s, 30_000L) { snap() } // level 1 fired at 20 s inside this isolation
        assertTrue("level 1 fired during the isolation", s.didAct)
        repeat(5) { tick(s, 1_000L) { snap(neighbors = 1) } } // a neighbor appears
        assertFalse("episode reset when the neighbor appeared", s.didAct)
        // The next isolation starts fresh: no level may fire before its own threshold.
        tick(s, 19_000L) { snap() }
        assertTrue("no level within 19 s of a fresh episode", !s.didAct)
    }

    // --- level 1 -----------------------------------------------------------------

    @Test
    fun levelOneFiresAt20sAndOnlyOnce() {
        val s = supervisor()
        s.tick(snap()) // episode starts at t=0
        tick(s, 19_000L) { snap() }
        assertEquals("nothing before 20 s", emptyList<RecoveryAction>(), s.tick(snap()))
        now += 1_000L // exactly 20 s
        assertEquals(
            listOf<RecoveryAction>(
                RecoveryAction.ClearStalePeers,
                RecoveryAction.ReRegisterService,
                RecoveryAction.FreshScanBurst,
            ),
            s.tick(snap()),
        )
        now += 1_000L
        assertEquals("level 1 does not repeat", emptyList<RecoveryAction>(), s.tick(snap()))
    }

    // --- in-flight gate ----------------------------------------------------------

    @Test
    fun noLevelFiresWhileAConnectIsInFlight() {
        val s = supervisor()
        s.tick(snap(state = AutoMeshState.Joining("AA:00", 7, sinceMs = 0))) // a join is in flight from t=0
        tick(s, 25_000L) { snap(state = AutoMeshState.Joining("AA:00", 7, sinceMs = 0)) } // join is 25 s old at t=25s
        assertTrue(
            "a young in-flight connect blocks every level past the 20 s threshold",
            !s.didAct,
        )
    }

    @Test
    fun levelOneWaitsOutAYoungJoinAndFiresOnceItAgesPastTheTimeout() {
        val s = supervisor()
        s.tick(snap()) // t=0, Searching
        tick(s, 10_000L) { snap(state = AutoMeshState.Joining("AA:00", 7, sinceMs = 10_000L)) } // join starts at t=10s
        tick(s, 35_000L) { snap(state = AutoMeshState.Joining("AA:00", 7, sinceMs = 10_000L)) } // t=45s, join is 35 s old
        assertTrue("held back by the in-flight join at t=45s", !s.didAct)
        // At t=50s the join is 40 s old — stuck; level 1 and level 2 ship together.
        now += 5_000L
        assertEquals(
            listOf<RecoveryAction>(
                RecoveryAction.ClearStalePeers,
                RecoveryAction.ReRegisterService,
                RecoveryAction.FreshScanBurst,
                RecoveryAction.ResetTcpRetry,
                RecoveryAction.ResetGroupPresence,
            ),
            s.tick(snap(state = AutoMeshState.Joining("AA:00", 7, sinceMs = 10_000L))),
        )
    }

    @Test
    fun aCreatingWithoutATimestampIsTrackedInternallyForTheGate() {
        val s = supervisor()
        s.tick(snap(state = AutoMeshState.Creating)) // Creating from t=0; the state has no timestamp of its own
        tick(s, 25_000L) { snap(state = AutoMeshState.Creating) } // internal age 25 s < 40 s: blocked
        assertTrue("a young Creating blocks every level", !s.didAct)
        now += 16_000L // t=41s: the internally tracked Creating age is past the 40 s timeout — level 1 unblocks
        assertEquals(
            listOf<RecoveryAction>(
                RecoveryAction.ClearStalePeers,
                RecoveryAction.ReRegisterService,
                RecoveryAction.FreshScanBurst,
            ),
            s.tick(snap(state = AutoMeshState.Creating)),
        )
        // Level 2 keeps its own 45 s threshold: it ships on the first tick past it.
        now += 5_000L
        assertEquals(
            listOf<RecoveryAction>(RecoveryAction.ResetTcpRetry, RecoveryAction.ResetGroupPresence),
            s.tick(snap(state = AutoMeshState.Creating)),
        )
    }

    @Test
    fun leavingTheInFlightStateUnblocksImmediately() {
        val s = supervisor()
        s.tick(snap()) // t=0, Searching
        tick(s, 10_000L) { snap(state = AutoMeshState.Joining("AA:00", 7, sinceMs = 10_000L)) }
        tick(s, 15_000L) { snap(state = AutoMeshState.Joining("AA:00", 7, sinceMs = 10_000L)) } // t=25s, still in flight
        assertTrue("still held back at t=25s", !s.didAct)
        // The join fails into Backoff at t=26s — no longer in flight; level 1 fires.
        now += 1_000L
        assertEquals(
            listOf<RecoveryAction>(
                RecoveryAction.ClearStalePeers,
                RecoveryAction.ReRegisterService,
                RecoveryAction.FreshScanBurst,
            ),
            s.tick(snap(state = AutoMeshState.Backoff(untilMs = now + 2_000L, reason = "join failed"))),
        )
    }

    // --- level 2 -----------------------------------------------------------------

    @Test
    fun levelTwoFiresAt45sWithItsOwnResets() {
        val s = supervisor()
        s.tick(snap()) // t=0; level 1 fires at 20 s inside the advance below
        tick(s, 44_000L) { snap() }
        assertEquals("nothing before 45 s", emptyList<RecoveryAction>(), s.tick(snap()))
        now += 1_000L // exactly 45 s: level 2 adds its resets (level 1 already fired at 20 s)
        assertEquals(
            listOf<RecoveryAction>(RecoveryAction.ResetTcpRetry, RecoveryAction.ResetGroupPresence),
            s.tick(snap()),
        )
    }

    @Test
    fun levelTwoRemovesAnOwnClientlessGroup() {
        val s = supervisor()
        s.tick(snap(state = AutoMeshState.Owner, groupFormed = true)) // isolated owner with no clients
        tick(s, 44_000L) { snap(state = AutoMeshState.Owner, groupFormed = true) }
        now += 1_000L
        assertEquals(
            listOf<RecoveryAction>(
                RecoveryAction.ResetTcpRetry,
                RecoveryAction.ResetGroupPresence,
                RecoveryAction.RemoveOwnGroup,
            ),
            s.tick(snap(state = AutoMeshState.Owner, groupFormed = true)),
        )
    }

    @Test
    fun levelTwoOmitsTheRemovalWhenTheGroupHasClients() {
        val s = supervisor()
        s.tick(snap(state = AutoMeshState.Owner, groupFormed = true, myClients = 2))
        tick(s, 44_000L) { snap(state = AutoMeshState.Owner, groupFormed = true, myClients = 2) }
        now += 1_000L
        assertEquals(
            listOf<RecoveryAction>(RecoveryAction.ResetTcpRetry, RecoveryAction.ResetGroupPresence),
            s.tick(snap(state = AutoMeshState.Owner, groupFormed = true, myClients = 2)),
        )
    }

    @Test
    fun levelTwoOmitsTheRemovalWhenNoGroupIsFormed() {
        val s = supervisor()
        s.tick(snap()) // t=0, Searching, no group at all
        tick(s, 45_000L) { snap() }
        assertTrue("level 2 still resets the TCP loop and tracker", s.didAct)
    }

    // --- level 3 -----------------------------------------------------------------

    @Test
    fun levelThreeFiresAt90sAndRepeatsEvery90sPlusJitter() {
        val s = supervisor(seed = 7)
        s.tick(snap()) // t=0; levels 1 and 2 fire at 20/45 s inside the advance below
        tick(s, 89_000L) { snap() }
        assertEquals("nothing before 90 s", emptyList<RecoveryAction>(), s.tick(snap()))
        now += 1_000L // exactly 90 s: level 3 fires on its own
        assertEquals(listOf<RecoveryAction>(RecoveryAction.FullCycle), s.tick(snap()))

        // Each repeat lands between +90 s and +100 s after the previous firing.
        var lastFiring = now
        var repeats = 0
        while (repeats < 3) {
            val actions = tick(s, 1_000L) { snap() }
            if (actions == listOf<RecoveryAction>(RecoveryAction.FullCycle)) {
                val gap = now - lastFiring
                assertTrue("repeat too soon: ${gap}ms", gap >= RecoverySupervisor.LEVEL3_REPEAT_MS)
                // The 1 s tick granularity can push a firing to the next whole second, so the
                // observed gap may reach repeat + max jitter exactly.
                assertTrue(
                    "repeat too late: ${gap}ms",
                    gap <= RecoverySupervisor.LEVEL3_REPEAT_MS + RecoverySupervisor.LEVEL3_JITTER_MAX_MS,
                )
                lastFiring = now
                repeats++
            }
        }
    }

    @Test
    fun theSameSeedReproducesTheSameLevelThreeSchedule() {
        val timesA = levelThreeTimes(seed = 42)
        val timesB = levelThreeTimes(seed = 42)
        assertEquals("same seed -> same schedule", timesA, timesB)
        assertTrue("at least two repeats observed", timesA.size >= 3)
    }

    /** Runs a fresh supervisor from t=0 until t=400 s and records every FullCycle firing time. */
    private fun levelThreeTimes(seed: Long): List<Long> {
        val s = supervisor(seed)
        now = 0L
        val firings = mutableListOf<Long>()
        repeat(400) {
            now += 1_000L
            if (s.tick(snap()) == listOf<RecoveryAction>(RecoveryAction.FullCycle)) firings += now
        }
        return firings
    }

    // --- didAct ------------------------------------------------------------------

    @Test
    fun didActStaysTrueForTheRestOfTheEpisode() {
        val s = supervisor()
        s.tick(snap()) // t=0
        tick(s, 20_000L) { snap() } // level 1 fires at exactly 20 s
        assertTrue("level 1 marked the episode", s.didAct)
        now += 5_000L
        assertEquals(emptyList<RecoveryAction>(), s.tick(snap())) // nothing new due (next is level 2 at 45 s)
        assertTrue("didAct is sticky within the episode", s.didAct)
    }
}
