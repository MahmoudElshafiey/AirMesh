package com.airmesh.transport

import com.airmesh.service.RecoverySupervisorFlagStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * JVM tests for the TASK S1 part 1 supervisor guards: no group removal when disabled, the owner
 * grace window, the unconditional in-flight block, the pause gate, and the flag-off contract.
 * Fake clock throughout; the old defaults keep the original level schedule (covered by
 * RecoverySupervisorTest, which must pass unchanged).
 */
class RecoverySupervisorGuardTest {

    private var now = 0L
    private val clock: () -> Long = { now }

    /** A snapshot with auto on, P2P up and no live neighbors unless stated otherwise. */
    private fun snap(
        state: AutoMeshState = AutoMeshState.Searching(now),
        groupFormed: Boolean = false,
        myClients: Int = 0,
        paused: Boolean = false,
        ownerGroupAgeMs: Long? = null,
    ) = RecoverySupervisor.Snapshot(
        autoMeshOn = true,
        p2pEnabled = true,
        liveNeighborCount = 0,
        state = state,
        groupFormed = groupFormed,
        myClients = myClients,
        paused = paused,
        ownerGroupAgeMs = ownerGroupAgeMs,
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

    // --- group removal disabled ------------------------------------------------------

    @Test
    fun levelTwoNeverRemovesOwnGroupWhenRemovalIsDisabled() {
        val s = RecoverySupervisor(clock, Random(1), allowGroupRemoval = false)
        tick(s, 0L) { snap(state = AutoMeshState.Owner, groupFormed = true) } // start the episode
        now += 50_000
        val actions = s.tick(snap(state = AutoMeshState.Owner, groupFormed = true))
        assertTrue("level-2 resets still fire", RecoveryAction.ResetTcpRetry in actions)
        assertTrue(RecoveryAction.ResetGroupPresence in actions)
        assertFalse("no RemoveOwnGroup when removal is disabled", RecoveryAction.RemoveOwnGroup in actions)
    }

    @Test
    fun levelTwoStillRemovesWithTheOldDefaults() {
        val s = RecoverySupervisor(clock, Random(1)) // allowGroupRemoval defaults to true
        tick(s, 0L) { snap(state = AutoMeshState.Owner, groupFormed = true) }
        now += 50_000
        val actions = s.tick(snap(state = AutoMeshState.Owner, groupFormed = true))
        assertTrue("old defaults keep the removal", RecoveryAction.RemoveOwnGroup in actions)
    }

    // --- owner grace -------------------------------------------------------------------

    @Test
    fun noActionWhileOwnerOfAGroupYoungerThanTheGrace() {
        val s = RecoverySupervisor(clock, Random(1), ownerGraceMs = 600_000L)
        // The episode starts while we do not own a group yet (no grace applies).
        tick(s, 0L) { snap(state = AutoMeshState.Searching(now)) }

        // We become the owner of a fresh group: the level clock must stop running.
        now += 25_000
        assertTrue(
            "level 1 held back by the grace",
            s.tick(snap(state = AutoMeshState.Owner, groupFormed = true, ownerGroupAgeMs = 30_000)).isEmpty(),
        )

        now += 24_000 // t=49s; without the reset, level 2 (45s) would have fired here
        assertTrue(
            "still inside the grace",
            s.tick(snap(state = AutoMeshState.Owner, groupFormed = true, ownerGroupAgeMs = 54_000)).isEmpty(),
        )

        // The group ages past the grace: the clock restarted at the last grace tick (t=49s), so
        // level 1 needs a fresh 20 s — not the original t=0 start.
        now += 12_000 // t=61s, age 605s → out of grace; elapsed since restart = 12s < 20s
        assertTrue(
            "grace just ended: still inside the fresh window",
            s.tick(snap(state = AutoMeshState.Owner, groupFormed = true, ownerGroupAgeMs = 605_000)).isEmpty(),
        )

        now += 21_000 // t=82s; elapsed since the restart at t=61s is 21s ≥ 20s → level 1 only
        val actions = s.tick(snap(state = AutoMeshState.Owner, groupFormed = true, ownerGroupAgeMs = 635_000))
        assertEquals(
            listOf<RecoveryAction>(
                RecoveryAction.ClearStalePeers,
                RecoveryAction.ReRegisterService,
                RecoveryAction.FreshScanBurst,
            ),
            actions,
        )
    }

    @Test
    fun theGraceDoesNotApplyWithoutAnOwnerGroup() {
        val s = RecoverySupervisor(clock, Random(1), ownerGraceMs = 600_000L)
        tick(s, 0L) { snap(state = AutoMeshState.Searching(now)) } // no group: age is null
        now += 25_000
        val actions = s.tick(snap(state = AutoMeshState.Searching(now)))
        assertTrue("no owner group: level 1 fires on schedule", RecoveryAction.ClearStalePeers in actions)
    }

    // --- unconditional in-flight block ---------------------------------------------------

    @Test
    fun noActionWhileJoiningCreatingOrYieldingWhenBlocking() {
        val s = RecoverySupervisor(clock, Random(1), blockWhileInFlight = true)
        tick(s, 0L) { snap(state = AutoMeshState.Searching(now)) } // start the episode at t=0

        now = 50_000 // older than the controller's own 40 s join timeout
        val joining = s.tick(
            snap(state = AutoMeshState.Joining("AA:BB:CC:DD:EE:FF", 7, sinceMs = 10_000)),
        )
        assertTrue("Joining blocks even past the old stuck threshold", joining.isEmpty())

        now = 60_000
        val creating = s.tick(snap(state = AutoMeshState.Creating))
        assertTrue("Creating blocks too", creating.isEmpty())

        now = 70_000
        val yielding = s.tick(
            snap(state = AutoMeshState.Yielding("AA:BB:CC:DD:EE:FF", 7, targetPhones = 3, sinceMs = 65_000)),
        )
        assertTrue("Yielding (merge in progress) blocks too", yielding.isEmpty())

        // As soon as the attempt settles, every level that was due ships together.
        now = 71_000
        val settled = s.tick(snap(state = AutoMeshState.Backoff(untilMs = 80_000, reason = "join failed")))
        assertEquals(
            listOf<RecoveryAction>(
                RecoveryAction.ClearStalePeers,
                RecoveryAction.ReRegisterService,
                RecoveryAction.FreshScanBurst,
                RecoveryAction.ResetTcpRetry,
                RecoveryAction.ResetGroupPresence,
            ),
            settled,
        )
    }

    @Test
    fun theOldDefaultsStillFirePastTheJoinTimeout() {
        val s = RecoverySupervisor(clock, Random(1)) // blockWhileInFlight defaults to false
        tick(s, 0L) { snap(state = AutoMeshState.Searching(now)) }
        now = 45_000
        val actions = s.tick(snap(state = AutoMeshState.Joining("AA:BB:CC:DD:EE:FF", 7, sinceMs = 0)))
        assertTrue("old defaults treat a 45 s join as stuck and act", RecoveryAction.ClearStalePeers in actions)
    }

    // --- pause gate -----------------------------------------------------------------------

    @Test
    fun noActionWhilePausedAndTheClockRestartsAfter() {
        val s = RecoverySupervisor(clock, Random(1))
        // Start the episode while running (no pause), so levels would be due on schedule.
        tick(s, 0L) { snap(state = AutoMeshState.Searching(now)) }

        // A manual action pauses auto-mesh at t=5s; nothing may fire for as long as it lasts.
        now += 5_000
        assertTrue("paused: level 1 (due at 20 s) is held back", s.tick(snap(paused = true)).isEmpty())
        now += 45_000 // t=50s, still paused — well past every level threshold
        assertTrue(
            "still paused at 50 s of isolation: nothing fires",
            !s.didAct && s.tick(snap(paused = true)).isEmpty(),
        )

        // Unpause at t=69s: the episode clock restarted there, so level 1 needs a fresh 20 s.
        now += 19_000
        assertTrue(
            "just after unpausing: still inside the fresh window",
            s.tick(snap(state = AutoMeshState.Searching(now))).isEmpty(),
        )
        now += 20_000 // t=89s; elapsed since unpause = 20s → level 1 fires
        val actions = s.tick(snap(state = AutoMeshState.Searching(now)))
        assertTrue("level 1 fires 20 s after the pause ended", RecoveryAction.ClearStalePeers in actions)
    }

    // --- flag-off contract -------------------------------------------------------------------

    /** In-memory fake of the service's flag store (the SharedPreferences impl is Android-only). */
    private class FakeFlagStore(var enabled: Boolean = RecoverySupervisorFlagStore.DEFAULT_ENABLED) :
        RecoverySupervisorFlagStore {
        override fun load(): Boolean = enabled
        override fun save(value: Boolean) { enabled = value }
    }

    @Test
    fun theSupervisorFlagDefaultsToOff() {
        assertFalse("the real service starts with the supervisor off", RecoverySupervisorFlagStore.DEFAULT_ENABLED)
    }

    @Test
    fun theServiceNeverTicksTheSupervisorWhileTheFlagIsOff() {
        // Mirrors the MeshService wiring: tick only when store.load() is true.
        val store = FakeFlagStore()
        val s = RecoverySupervisor(
            clock,
            Random(1),
            allowGroupRemoval = false,
            ownerGraceMs = 600_000L,
            blockWhileInFlight = true,
        )
        var all = emptyList<RecoveryAction>()
        for (step in 1..12) { // two minutes of isolation with the flag off
            now += 10_000
            if (store.load()) all += s.tick(snap(state = AutoMeshState.Owner, groupFormed = true))
        }
        assertTrue("flag off: no actions over two minutes", all.isEmpty())
        assertFalse(s.didAct)

        // The user opts in from the Debug card; the episode starts fresh at that moment.
        store.enabled = true
        now += 10_000
        if (store.load()) all += s.tick(snap(state = AutoMeshState.Owner, groupFormed = true))
        assertTrue("first tick after enabling only starts the clock", all.isEmpty())
        now += 21_000 // elapsed since enabling = 31s ≥ 20s → level 1 is due
        if (store.load()) all += s.tick(snap(state = AutoMeshState.Owner, groupFormed = true))
        assertTrue("flag on: level 1 fires on the fresh schedule", RecoveryAction.ClearStalePeers in all)
    }
}
