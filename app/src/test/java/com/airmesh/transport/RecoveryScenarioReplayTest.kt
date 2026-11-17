package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * Scenario replay tests S1–S7 (TASK R3 part 4): drive the real [AutoMeshController] and
 * [RecoverySupervisor] together exactly as MeshService wires them — fake clock, a fake peer
 * directory (visible adverts with last-seen stamps), simulated connector effects, seeded Random.
 * Each second mirrors the service's 1 s loop: controller Tick first, then supervisor.tick.
 *
 * Every scenario states its recovery bound in the assertions (how long after the trigger until
 * the phone is connected again) and asserts the cross-cutting invariants: no oscillation, no
 * double group creation, the election rule intact (the highest visible id creates; everyone else
 * joins or waits), and that the supervisor never acts while a connect is still in flight and its
 * levels fire only with no live neighbor. mergeEnabled stays off except in S7 so each scenario
 * isolates one failure mode.
 */
class RecoveryScenarioReplayTest {

    /** One simulated phone: controller + supervisor + fake directory/connector, driven second by second. */
    private class Phone(
        val myNodeId: Int,
        token: Int,
        seed: Long,
        mergeEnabled: Boolean = false,
    ) {
        var t = 0L
            private set
        private val clock: () -> Long = { t }
        private val random = Random(seed)
        val controller = AutoMeshController(myNodeId, token, clock, random, true, 0L, mergeEnabled)
        val supervisor = RecoverySupervisor(clock, random)

        var p2pEnabled = true
            private set
        var groupFormed = false
            private set
        var role: P2pRole = P2pRole.NONE
            private set
        var neighbors: Set<Int> = emptySet()
            private set
        val visiblePeers = mutableListOf<AirMeshPeer>()

        /** One record per second: what the supervisor saw and did. */
        data class TickRecord(
            val tMs: Long,
            val state: AutoMeshState,
            val neighbors: Set<Int>,
            val role: P2pRole,
            val recoveryActions: List<RecoveryAction>,
        )

        val tickLog = mutableListOf<TickRecord>()
        val createTimes = mutableListOf<Long>()
        val removeTimes = mutableListOf<Long>()
        var connectToCount = 0
            private set
        val tcpRestartTimes = mutableListOf<Long>()

        /** A ConnectTo issued last tick; completes on the next step if the target is still visible. */
        private var pendingJoinAddress: String? = null

        /** Auto-mesh turns on at startup. */
        fun start() {
            execute(controller.onEvent(AutoMeshEvent.PermissionsChanged(true)))
        }

        /** Publish a new visible-peer list (the directory's StateFlow emission). */
        fun publishPeers(peers: List<AirMeshPeer>) {
            visiblePeers.clear()
            visiblePeers.addAll(peers)
            execute(controller.onEvent(AutoMeshEvent.PeersChanged(visiblePeers.toList())))
        }

        /** Set the live TCP neighbors; mirrors the service's neighbor collector, incl. the keepalive-close restart. */
        fun setNeighbors(next: Set<Int>) {
            val wasEmpty = neighbors.isEmpty()
            neighbors = next
            if (!wasEmpty && next.isEmpty() && groupFormed && role == P2pRole.CLIENT) tcpRestartTimes += t
        }

        /** Report our live client count (feeds the merge decision while we own a group). */
        fun reportMyClients(clients: Int) {
            controller.onEvent(AutoMeshEvent.MyClientsChanged(clients))
        }

        /** The connector reports our group died on its own (owner app stopped / force-killed). */
        fun loseGroupUnexpectedly() {
            groupFormed = false
            role = P2pRole.NONE
            setNeighbors(emptySet())
            execute(controller.onEvent(AutoMeshEvent.GroupGone))
        }

        /** Airplane mode: radio off — group gone, nothing visible, P2P disabled. */
        fun disableP2p() {
            p2pEnabled = false
            execute(controller.onEvent(AutoMeshEvent.PermissionsChanged(false, "Wi-Fi Direct is off")))
            loseGroupUnexpectedly()
            publishPeers(emptyList())
        }

        /** Wi-Fi Direct came back from off. */
        fun enableP2p() {
            p2pEnabled = true
            execute(controller.onEvent(AutoMeshEvent.PermissionsChanged(true)))
        }

        /** Advance one second, exactly as the service's 1 s loop does: controller tick first, then supervisor. */
        fun step() {
            t += 1_000L
            sweepAgingPeers()
            settlePendingJoin()
            execute(controller.onEvent(AutoMeshEvent.Tick))
            val recovery = supervisor.tick(snapshot())
            executeRecovery(recovery)
            tickLog += TickRecord(t, controller.state, neighbors.toSet(), role, recovery)
        }

        private fun snapshot() = RecoverySupervisor.Snapshot(
            autoMeshOn = true,
            p2pEnabled = p2pEnabled,
            liveNeighborCount = neighbors.size,
            state = controller.state,
            groupFormed = groupFormed,
            myClients = if (role == P2pRole.GROUP_OWNER) neighbors.size else 0,
        )

        /** The directory's stale sweep: entries older than the group-aware threshold drop out. */
        private fun sweepAgingPeers() {
            val kept = visiblePeers.filterNot { FreshnessRules.shouldExpirePeerNow(it.lastSeenMs, t, groupFormed) }
            if (kept.size != visiblePeers.size) publishPeers(kept)
        }

        /** A connect issued last tick completes now — but only if the target is still visible. */
        private fun settlePendingJoin() {
            val addr = pendingJoinAddress ?: return
            val target = visiblePeers.firstOrNull { it.deviceAddress == addr }
            if (target != null && p2pEnabled) {
                pendingJoinAddress = null
                formClient(target.nodeId)
            }
            // Target vanished: the join hangs; the controller's 40 s timeout cancels it.
        }

        private fun execute(actions: List<AutoMeshAction>) {
            for (a in actions) when (a) {
                is AutoMeshAction.ConnectTo -> {
                    pendingJoinAddress = a.deviceAddress
                    connectToCount++
                }
                is AutoMeshAction.CreateGroup -> if (p2pEnabled) formOwner() else execute(
                    controller.onEvent(AutoMeshEvent.ConnectFailed(AutoFailureReason.OTHER)),
                )
                // The service marks the controller's own removal intentional before executing it.
                is AutoMeshAction.RemoveGroup -> {
                    controller.markIntentionalGroupRemoval()
                    removeOurGroup()
                }
                is AutoMeshAction.StartDiscovery, is AutoMeshAction.StopDiscovery -> Unit // not asserted here
            }
        }

        private fun formOwner() {
            createTimes += t
            groupFormed = true
            role = P2pRole.GROUP_OWNER
            execute(controller.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.OWNER)))
        }

        private fun formClient(targetNodeId: Int) {
            groupFormed = true
            role = P2pRole.CLIENT
            setNeighbors(neighbors + targetNodeId)
            execute(controller.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT)))
        }

        /** Our own group is removed (the intentional flag was already set by the caller). */
        private fun removeOurGroup() {
            removeTimes += t
            groupFormed = false
            role = P2pRole.NONE
            setNeighbors(emptySet())
            execute(controller.onEvent(AutoMeshEvent.GroupGone))
        }

        private fun executeRecovery(actions: List<RecoveryAction>) {
            for (a in actions) when (a) {
                // Mirrors WifiDirectConnector.forceRestartConnectLoop's guard.
                is RecoveryAction.ResetTcpRetry -> if (role == P2pRole.CLIENT && groupFormed) tcpRestartTimes += t
                is RecoveryAction.RemoveOwnGroup -> {
                    controller.markIntentionalGroupRemoval()
                    removeOurGroup()
                }
                // ClearStalePeers / ReRegisterService / FreshScanBurst / ResetGroupPresence / FullCycle:
                // directory-level effects with no state to track here.
                else -> Unit
            }
        }
    }

    /** A fake DNS-SD advert for node [id]; [owner] sets the o1 flag, [seenAtMs] stamps the last scan. */
    private fun peer(id: Int, owner: Boolean = false, clients: Int = 0, seenAtMs: Long): AirMeshPeer =
        AirMeshPeer("AA:00:00:00:${id.toString(16).padStart(2, '0')}", id, owner, TOKEN_B, clients, seenAtMs)

    private fun Phone.runTo(tMs: Long) {
        while (t < tMs) step()
    }

    /** First moment after [afterMs] when we are a client with [target] as a live neighbor. */
    private fun Phone.recoveredAtMs(target: Int, afterMs: Long): Long? =
        tickLog.firstOrNull { it.tMs > afterMs && it.role == P2pRole.CLIENT && target in it.neighbors }?.tMs

    private fun actionTicks(p: Phone): List<Phone.TickRecord> = p.tickLog.filter { it.recoveryActions.isNotEmpty() }

    private fun levelOneActions(): List<RecoveryAction> = listOf(
        RecoveryAction.ClearStalePeers,
        RecoveryAction.ReRegisterService,
        RecoveryAction.FreshScanBurst,
    )

    /**
     * The supervisor must never act while a connect is still in flight. A Joining/Yielding that has
     * reached the 40 s timeout counts as stuck (the controller cancels it within the next second),
     * so actions on such an old record are allowed — exactly the gate's documented boundary.
     */
    private fun assertNeverActedWhileInFlight(p: Phone) {
        for (r in p.tickLog) {
            val young = when (val s = r.state) {
                is AutoMeshState.Joining -> r.tMs - s.sinceMs < AUTO_JOIN_TIMEOUT_MS
                is AutoMeshState.Yielding -> r.tMs - s.sinceMs < AUTO_JOIN_TIMEOUT_MS
                is AutoMeshState.Creating -> true // Creating has no timestamp; never observed across a tick here
                else -> false
            }
            if (young && r.recoveryActions.isNotEmpty()) {
                fail("supervisor acted at ${r.tMs}ms while a connect was still in flight: ${r.recoveryActions}")
            }
        }
    }

    /** Levels fire only with no live neighbor — even when the peer table is empty. */
    private fun assertLevelsOnlyWithNoNeighbor(p: Phone) {
        for (r in p.tickLog) {
            if (r.recoveryActions.isNotEmpty() && r.neighbors.isNotEmpty()) {
                fail("supervisor acted at ${r.tMs}ms with live neighbors ${r.neighbors}")
            }
        }
    }

    // --- S1: the owner app is stopped (Stop or removeGroup) while clients are connected ---

    @Test
    fun s1OwnerAppStoppedClientRejoinsWithinTwoSecondsOfTheFreshAdvert() {
        val p = Phone(5, TOKEN_A, seed = 1) // we are #5; the owner is #9
        p.start() // t=0: Searching
        p.publishPeers(listOf(peer(9, owner = true, seenAtMs = 0))) // trusted owner visible -> join
        p.runTo(30_000L) // joined as CLIENT of #9 at t=1

        // The owner app is stopped at t=30: its advert vanishes and our group dies.
        p.publishPeers(emptyList())
        p.loseGroupUnexpectedly()

        // Isolated until the owner restarts at t=60 with a fresh o1 advert (re-confirmed by a new scan).
        p.runTo(60_000L)
        p.publishPeers(listOf(peer(9, owner = true, seenAtMs = 60_000L)))
        p.runTo(70_000L)

        val rejoinedAt = p.recoveredAtMs(9, afterMs = 30_000L)
        assertTrue("rejoined within 2 s of the fresh advert at t=60s (was ${rejoinedAt}ms)", rejoinedAt != null && rejoinedAt <= 62_000L)
        assertEquals("never created a group — we are the lower id and the owner came back", emptyList<Long>(), p.createTimes)
        assertEquals("no oscillation: one loss, one rejoin", 2, p.connectToCount)
        assertEquals(emptyList<Long>(), p.removeTimes)

        val actions = actionTicks(p)
        assertEquals("exactly one supervisor level fired during the isolation (level 1)", 1, actions.size)
        assertTrue("level 1 lands ~20 s after the episode started at t=30s", actions[0].tMs in 49_000L..52_000L)
        assertEquals(levelOneActions(), actions[0].recoveryActions)

        assertNeverActedWhileInFlight(p)
        assertLevelsOnlyWithNoNeighbor(p)
    }

    // --- S2: the owner process is force-killed (stale advert lingers) ---

    @Test
    fun s2OwnerForceKilledTakeoverCreatesOnceAndLevelTwoDissolvesTheClientlessGroup() {
        val p = Phone(5, TOKEN_A, seed = 2) // we are #5; the owner is #9 (higher id)
        p.start()
        p.publishPeers(listOf(peer(9, owner = true, seenAtMs = 0)))
        p.runTo(30_000L) // CLIENT of #9

        // The owner process is force-killed at t=30: the group dies but its stale o1 advert
        // (last seen at t=25s) lingers in the peer table.
        p.loseGroupUnexpectedly()
        p.publishPeers(listOf(peer(9, owner = true, seenAtMs = 25_000L)))

        p.runTo(140_000L)

        // The freshness gate blocks joining the stale advert; after the grace window we are not the
        // creator (#9 > #5), so the takeover deadline fires and we create exactly one group.
        assertEquals("took over with exactly one CreateGroup (no double creation)", 1, p.createTimes.size)
        assertTrue(
            "takeover lands between the end of grace (~t=43s) and +25 s (was ${p.createTimes[0]}ms)",
            p.createTimes[0] in 60_000L..70_000L,
        )
        assertEquals("level 2 dissolved our clientless group exactly once", 1, p.removeTimes.size)
        assertTrue(
            "the removal is the level-2 escalation ~45 s into the isolation (was ${p.removeTimes[0]}ms)",
            p.removeTimes[0] in 74_000L..80_000L,
        )
        assertEquals("never joined the stale pre-loss advert", 1, p.connectToCount)

        val all = actionTicks(p)
        assertEquals(3, all.size)
        assertTrue(all[0].tMs in 49_000L..52_000L)
        assertEquals(levelOneActions(), all[0].recoveryActions)
        assertTrue(all[1].tMs in 74_000L..80_000L)
        assertEquals(
            listOf<RecoveryAction>(
                RecoveryAction.ResetTcpRetry,
                RecoveryAction.ResetGroupPresence,
                RecoveryAction.RemoveOwnGroup,
            ),
            all[1].recoveryActions,
        )
        // After the stale advert ages out (60 s without a group) only level-3 full cycles remain.
        assertEquals(listOf<RecoveryAction>(RecoveryAction.FullCycle), all[2].recoveryActions)
        assertTrue(all[2].tMs in 120_000L..125_000L)

        assertNeverActedWhileInFlight(p)
        assertLevelsOnlyWithNoNeighbor(p)
    }

    // --- S3: a client leaves range / toggles Wi-Fi off and on (we are the owner) ---

    @Test
    fun s3ClientLeavesRangeOwnerReformsWithinTwoSecondsOfTheFreshAdvert() {
        val p = Phone(9, TOKEN_A, seed = 3) // we are #9 (owner); the client is #5
        p.start()
        p.publishPeers(listOf(peer(5, seenAtMs = 0))) // no owner visible and we are the highest id -> create now
        p.runTo(2_000L)
        assertEquals("created our group at startup", listOf(0L), p.createTimes)

        p.setNeighbors(setOf(5)) // client connects at t=2
        p.reportMyClients(1)
        p.runTo(30_000L)

        // The client toggles Wi-Fi off at t=30: its advert vanishes; the TCP link survives until keepalive drops it.
        p.publishPeers(emptyList())
        p.runTo(54_000L)
        p.setNeighbors(emptySet()) // keepalive closes the last link at t=54

        // Isolated owner: level 1 at ~t=75, level 2 at ~t=100 dissolves our clientless group.
        p.runTo(120_000L)
        assertEquals("level 2 removed exactly one (clientless) group", listOf(100_000L), p.removeTimes)

        // The client is back at t=120 with a fresh advert; we are the creator again -> re-form immediately.
        p.publishPeers(listOf(peer(5, seenAtMs = 120_000L)))
        p.setNeighbors(setOf(5))
        p.reportMyClients(1)
        p.runTo(130_000L)

        assertEquals("exactly two creations: startup and the re-takeover (no double creation)", listOf(0L, 120_000L), p.createTimes)
        assertTrue(
            "owner group re-formed within 2 s of the fresh advert at t=120s",
            p.groupFormed && p.role == P2pRole.GROUP_OWNER && p.neighbors == setOf(5),
        )

        val all = actionTicks(p)
        assertEquals("exactly two supervisor levels fired (no full cycle before t=130)", 2, all.size)
        assertTrue(all[0].tMs in 74_000L..76_000L)
        assertEquals(levelOneActions(), all[0].recoveryActions)
        assertTrue(all[1].tMs in 99_000L..101_000L)
        assertEquals(
            listOf<RecoveryAction>(
                RecoveryAction.ResetTcpRetry,
                RecoveryAction.ResetGroupPresence,
                RecoveryAction.RemoveOwnGroup,
            ),
            all[1].recoveryActions,
        )

        assertNeverActedWhileInFlight(p)
        assertLevelsOnlyWithNoNeighbor(p)
    }

    // --- S4: airplane mode / Wi-Fi Direct disabled then enabled (P2P state changed events) ---

    @Test
    fun s4P2pDisabledThenReenabledSupervisorStaysQuietUntilTheRadioIsBack() {
        val p = Phone(5, TOKEN_A, seed = 4) // we are #5; the owner is #9
        p.start()
        p.publishPeers(listOf(peer(9, owner = true, seenAtMs = 0)))
        p.runTo(30_000L) // CLIENT of #9

        // Airplane mode at t=30: radio off — group gone, nothing visible.
        p.disableP2p()
        p.runTo(90_000L)
        assertTrue("no supervisor action while Wi-Fi Direct is down (60 s of isolation)", actionTicks(p).isEmpty())

        // Radio back at t=90; the owner re-advertises fresh at t=95.
        p.enableP2p()
        p.runTo(95_000L)
        p.publishPeers(listOf(peer(9, owner = true, seenAtMs = 95_000L)))
        p.runTo(110_000L)

        val rejoinedAt = p.recoveredAtMs(9, afterMs = 30_000L)
        assertTrue("rejoined within 2 s of the fresh advert at t=95s (was ${rejoinedAt}ms)", rejoinedAt != null && rejoinedAt <= 97_000L)
        assertEquals(emptyList<Long>(), p.createTimes)
        assertEquals(2, p.connectToCount)
        assertTrue("the supervisor never acted in this scenario", actionTicks(p).isEmpty())

        assertNeverActedWhileInFlight(p)
        assertLevelsOnlyWithNoNeighbor(p)
    }

    // --- S5: TCP link closed by keepalive while the group is still formed ---

    @Test
    fun s5KeepaliveClosesTcpLinkRestartHappensImmediatelyNotAtLevelTwo() {
        val p = Phone(5, TOKEN_A, seed = 5) // we are #5; owner #9 stays alive and advertising
        p.start()
        p.publishPeers(listOf(peer(9, owner = true, seenAtMs = 0)))
        p.runTo(30_000L) // CLIENT of #9

        // Keepalive closes our TCP link at t=30; the P2P group stays formed.
        p.setNeighbors(emptySet())
        assertEquals("the client's TCP retry loop restarts immediately on a keepalive close", listOf(30_000L), p.tcpRestartTimes)

        // Isolated (no live neighbor) until the fresh retry loop reconnects at t=60.
        p.runTo(60_000L)
        p.setNeighbors(setOf(9))
        p.runTo(75_000L)

        val all = actionTicks(p)
        assertEquals("exactly one supervisor level fired (level 1); recovery beat level 2", 1, all.size)
        assertTrue(all[0].tMs in 49_000L..52_000L)
        assertEquals(levelOneActions(), all[0].recoveryActions)
        assertEquals("no group removal and no creation", emptyList<Long>(), p.removeTimes + p.createTimes)
        assertTrue(p.groupFormed && p.role == P2pRole.CLIENT && p.neighbors == setOf(9))

        assertNeverActedWhileInFlight(p)
        assertLevelsOnlyWithNoNeighbor(p)
    }

    // --- S6: both phones lose the group at the same moment ---

    @Test
    fun s6BothPhonesLoseTheGroupAtOnceLowerIdWaitsForTheHigherIdsTakeover() {
        val p = Phone(5, TOKEN_A, seed = 6) // we are #5; #9 (higher id) was the owner
        p.start()
        p.publishPeers(listOf(peer(9, owner = true, seenAtMs = 0)))
        p.runTo(30_000L) // CLIENT of #9

        // Both phones lose the group at t=30 (power blip): our group dies and #9's advert vanishes.
        p.loseGroupUnexpectedly()
        p.publishPeers(emptyList())

        // #9 re-advertises at t=35 as a plain peer (it lost its group too); it flips to o1 at t=50 when it takes over.
        p.runTo(35_000L)
        p.publishPeers(listOf(peer(9, seenAtMs = 35_000L)))
        p.runTo(50_000L)
        p.publishPeers(listOf(peer(9, owner = true, seenAtMs = 50_000L)))
        p.runTo(60_000L)

        val rejoinedAt = p.recoveredAtMs(9, afterMs = 30_000L)
        assertTrue("rejoined within 2 s of #9's fresh o1 advert at t=50s (was ${rejoinedAt}ms)", rejoinedAt != null && rejoinedAt <= 52_000L)
        assertEquals("election invariant: the lower id never creates while a higher peer is visible", emptyList<Long>(), p.createTimes)
        assertTrue("we were never in Creating", p.tickLog.none { it.state is AutoMeshState.Creating })
        assertEquals(2, p.connectToCount)
        assertTrue("recovery (<= 21 s from the loss) beat every supervisor level: none fired", actionTicks(p).isEmpty())

        assertNeverActedWhileInFlight(p)
        assertLevelsOnlyWithNoNeighbor(p)
    }

    // --- S7: a merge/yield in progress when the link drops (the target crashes mid-join) ---

    @Test
    fun s7YieldInFlightWhenTheTargetCrashesSupervisorWaitsOutTheJoinThenRecovers() {
        val p = Phone(9, TOKEN_A, seed = 7, mergeEnabled = true) // owner with client #5; stronger owner is #12 (2 clients)
        p.start()
        p.publishPeers(listOf(peer(5, seenAtMs = 0))) // we are the creator -> own group at t=0
        p.runTo(2_000L)
        assertEquals(listOf(0L), p.createTimes)

        p.setNeighbors(setOf(5))
        p.reportMyClients(1)
        p.runTo(30_000L)

        // A stronger owner appears at t=30: #12 with 2 clients (we have 1). #15 is a higher non-owner,
        // so after the yield fails we are NOT the creator and must wait instead of re-creating.
        p.publishPeers(
            listOf(
                peer(5, seenAtMs = 30_000L),
                peer(12, owner = true, clients = 2, seenAtMs = 30_000L),
                peer(15, seenAtMs = 30_000L),
            ),
        )

        // The merge preconditions hold at t=36 (two sightings >= 6 s apart) plus a seeded 2-6 s settle:
        // the yield lands between t=38 and t=42. Step until it fires, then crash #12 in the same instant.
        var guard = 0
        while (p.controller.state !is AutoMeshState.Yielding && guard < 60) {
            p.step()
            guard++
        }
        assertTrue("the merge yielded to #12", p.controller.state is AutoMeshState.Yielding)
        val tY = p.t
        assertTrue("the yield lands between t=38 and t=42 (was ${tY}ms)", tY in 38_000L..42_000L)

        // The target crashes mid-join: #12's advert vanishes; the join hangs until the 40 s timeout.
        p.publishPeers(listOf(peer(5, seenAtMs = tY), peer(15, seenAtMs = tY)))

        // #12 is back with a fresh o1 at T = yield + 60; we join it as a client (we are not the creator — #15 outranks us).
        p.runTo(tY + 60_000L)
        p.publishPeers(
            listOf(
                peer(5, seenAtMs = tY + 60_000L),
                peer(12, owner = true, clients = 2, seenAtMs = tY + 60_000L),
                peer(15, seenAtMs = tY + 60_000L),
            ),
        )
        p.runTo(tY + 70_000L)

        val rejoinedAt = p.recoveredAtMs(12, afterMs = tY)
        assertTrue("rejoined #12 within 2 s of its fresh advert (was ${rejoinedAt}ms)", rejoinedAt != null && rejoinedAt <= tY + 62_000L)
        assertEquals("never re-created a group after yielding", listOf(0L), p.createTimes)
        assertEquals(listOf(tY), p.removeTimes) // the single yield's own removal — no supervisor RemoveOwnGroup ever
        assertEquals(2, p.connectToCount)

        // The supervisor stayed silent for the whole in-flight window [tY, tY+40) even though level 1's
        // threshold (episode start + 20 s) fell inside it; levels 1 and 2 ship once the join counts as stuck.
        val all = actionTicks(p)
        assertEquals("exactly two supervisor ticks acted: level 1 then level 2", 2, all.size)
        assertEquals("level 1 ships the moment the join reaches its 40 s timeout", tY + 40_000L, all[0].tMs)
        assertEquals(levelOneActions(), all[0].recoveryActions)
        assertEquals("level 2 follows at its own 45 s threshold (no RemoveOwnGroup — no group formed)", tY + 45_000L, all[1].tMs)
        assertEquals(
            listOf<RecoveryAction>(RecoveryAction.ResetTcpRetry, RecoveryAction.ResetGroupPresence),
            all[1].recoveryActions,
        )

        assertNeverActedWhileInFlight(p)
        assertLevelsOnlyWithNoNeighbor(p)
    }

    companion object {
        /** Our phone's election token; foreign phones use [TOKEN_B]. Ids decide in every scenario. */
        private const val TOKEN_A = 1
        private const val TOKEN_B = 2
    }
}
