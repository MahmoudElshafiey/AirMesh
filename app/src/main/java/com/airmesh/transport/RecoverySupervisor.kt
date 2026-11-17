package com.airmesh.transport

import java.util.Random

/**
 * Escalating recovery for "auto-mesh on, Wi-Fi Direct up, but no live neighbor" (TASK R3 part 3).
 * Pure Kotlin with an injectable clock and a seeded [Random] so scenarios replay deterministically.
 *
 * The supervisor only triggers actions the system already knows how to do — it NEVER creates a
 * group itself:
 *  - Level 1 after [LEVEL1_AFTER_MS]: clear stale peer-table entries, re-register our service with
 *    the correct flags, and fire a fresh scan burst.
 *  - Level 2 after [LEVEL2_AFTER_MS]: additionally reset the TCP retry loop (fresh backoff) and the
 *    group-presence tracker; if we own a formed group that has no clients, remove it (intentional).
 *  - Level 3 at [LEVEL3_AFTER_MS], then every [LEVEL3_REPEAT_MS] plus seeded jitter: full cycle —
 *    clear coordinator state, remove+re-add our local service, restart discovery with the default
 *    strategy order.
 *
 * No level fires while a connect/createGroup is in flight (a Joining/Creating/Yielding younger than
 * [AUTO_JOIN_TIMEOUT_MS]); an older one counts as stuck and unblocks us. An episode resets whenever
 * a live neighbor appears, auto-mesh turns off, or Wi-Fi Direct goes down.
 */
class RecoverySupervisor(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random(),
) {

    /** One input snapshot per tick; the service builds it from controller + connector state. */
    data class Snapshot(
        val autoMeshOn: Boolean,
        val p2pEnabled: Boolean,
        val liveNeighborCount: Int,
        val state: AutoMeshState,
        val groupFormed: Boolean,
        /** Our client count (phones in our group); 0 unless we are the owner. */
        val myClients: Int,
    )

    /** True after any level fired in the current episode; cleared when the episode resets. */
    var didAct: Boolean = false
        private set

    private var episodeStartMs: Long? = null
    private var level1Fired = false
    private var level2Fired = false
    private var nextLevel3AtMs: Long? = null
    /** [AutoMeshState.Creating] carries no timestamp of its own; remember when we first saw it. */
    private var creatingSinceMs: Long? = null

    /**
     * Advance the episode by one tick and return the actions due now (possibly empty). Levels are
     * cumulative: if level 1 was held back by an in-flight connect, its actions ship together with
     * level 2's when the gate finally opens.
     */
    fun tick(snapshot: Snapshot): List<RecoveryAction> {
        val now = clock()
        if (!snapshot.autoMeshOn || !snapshot.p2pEnabled || snapshot.liveNeighborCount > 0) {
            resetEpisode() // not our situation (or it just ended): start clean next time
            return emptyList()
        }

        val start = episodeStartMs ?: now.also {
            episodeStartMs = it
            level1Fired = false
            level2Fired = false
            nextLevel3AtMs = null
            creatingSinceMs = null
        }
        creatingSinceMs = if (snapshot.state is AutoMeshState.Creating) creatingSinceMs ?: now else null

        // Never act while a connect/createGroup is in flight; the controller's own 40 s timeout
        // bounds that, so an older one counts as stuck and unblocks us.
        val inFlightYoung = when (val s = snapshot.state) {
            is AutoMeshState.Joining -> now - s.sinceMs < AUTO_JOIN_TIMEOUT_MS
            is AutoMeshState.Yielding -> now - s.sinceMs < AUTO_JOIN_TIMEOUT_MS
            is AutoMeshState.Creating -> creatingSinceMs?.let { now - it < AUTO_JOIN_TIMEOUT_MS } ?: false
            else -> false
        }
        if (inFlightYoung) return emptyList()

        val elapsed = now - start
        val actions = mutableListOf<RecoveryAction>()

        // Level 1: once per episode, at 20 s.
        if (!level1Fired && elapsed >= LEVEL1_AFTER_MS) {
            level1Fired = true
            actions += RecoveryAction.ClearStalePeers
            actions += RecoveryAction.ReRegisterService
            actions += RecoveryAction.FreshScanBurst
        }

        // Level 2: once per episode, at 45 s — its own resets on top of everything from level 1.
        if (!level2Fired && elapsed >= LEVEL2_AFTER_MS) {
            level2Fired = true
            actions += RecoveryAction.ResetTcpRetry
            actions += RecoveryAction.ResetGroupPresence
            // Only a client-less group of our own may be removed; never one with clients in it.
            if (snapshot.groupFormed && snapshot.liveNeighborCount == 0 && snapshot.myClients == 0) {
                actions += RecoveryAction.RemoveOwnGroup
            }
        }

        // Level 3: first at 90 s, then every 90 s plus seeded jitter.
        val nextLevel3 = nextLevel3AtMs ?: start + LEVEL3_AFTER_MS
        if (now >= nextLevel3) {
            actions += RecoveryAction.FullCycle
            nextLevel3AtMs = now + LEVEL3_REPEAT_MS + random.nextInt(LEVEL3_JITTER_MAX_MS.toInt())
        }

        if (actions.isNotEmpty()) {
            didAct = true // an action fired in this episode: mark it for the recovery recorder
        }
        return actions
    }

    private fun resetEpisode() {
        episodeStartMs = null
        level1Fired = false
        level2Fired = false
        nextLevel3AtMs = null
        creatingSinceMs = null
        didAct = false
    }

    companion object {
        /** Level 1 (stale peers + re-register + scan burst) fires after this much isolation. */
        const val LEVEL1_AFTER_MS = 20_000L

        /** Level 2 (TCP retry reset, presence tracker reset, client-less group removal). */
        const val LEVEL2_AFTER_MS = 45_000L

        /** First level-3 full cycle after this much isolation. */
        const val LEVEL3_AFTER_MS = 90_000L

        /** Level-3 repeats every this long... */
        const val LEVEL3_REPEAT_MS = 90_000L

        /** ...plus up to this much seeded jitter, so phones do not cycle in lockstep. */
        const val LEVEL3_JITTER_MAX_MS = 10_000L
    }
}

/** Actions the service executes on behalf of [RecoverySupervisor]; each maps to an existing call site. */
sealed interface RecoveryAction {
    /** Level 1: drop peer-table entries that are stale by the group-aware threshold, right now. */
    data object ClearStalePeers : RecoveryAction

    /** Level 1: re-register our DNS-SD service with the owner flag it should carry right now. */
    data object ReRegisterService : RecoveryAction

    /** Level 1: arm a fresh startup scan burst (+0.5 s, +6 s, +14 s). */
    data object FreshScanBurst : RecoveryAction

    /** Level 2: restart the client's TCP retry loop with a fresh backoff schedule. */
    data object ResetTcpRetry : RecoveryAction

    /** Level 2: drop the group-presence debounce so the next report re-arms it from scratch. */
    data object ResetGroupPresence : RecoveryAction

    /** Level 2: remove our own formed group — only ever issued when it has no clients. */
    data object RemoveOwnGroup : RecoveryAction

    /** Level 3: full cycle — clear coordinator state, remove+re-add the local service, and restart
     * discovery with the default strategy order. */
    data object FullCycle : RecoveryAction
}
