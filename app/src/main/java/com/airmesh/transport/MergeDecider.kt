package com.airmesh.transport

import java.util.Random

/** What an owner should do about another visible group owner (TASK M1 part 3). */
sealed interface MergeDecision {
    /** I keep my group; the other side (if any) is expected to make the move. */
    data object Stay : MergeDecision

    /** Every precondition held and the settle delay passed: join [targetAddress]. */
    data class Yield(val targetAddress: String, val targetNodeId: Int) : MergeDecision

    /** Not yet — [reason] explains why (the controller logs it). */
    data class Wait(val reason: String) : MergeDecision
}

/** One sighting of a foreign group owner in a scan. */
data class MergeOwnerSighting(
    val address: String,
    val nodeId: Int,
    val token: Int,
    /** Client count the other side advertises (0 = empty group). */
    val clients: Int,
    /** When this owner was last seen; its ad must be fresher than [MergeDecider.FRESH_MS]. */
    val lastSeenMs: Long,
)

/** The foreign owner worth comparing against: most clients first, then highest (node id, token). */
fun strongestForeignOwner(peers: List<AirMeshPeer>, myNodeId: Int): AirMeshPeer? =
    peers.filter { it.isGroupOwner && it.nodeId != myNodeId }
        .maxWithOrNull(compareBy({ it.clients }, { it.nodeId }, { it.tieBreakToken }))

/**
 * Decides whether an owner should yield its group to another visible owner (TASK M1 part 3).
 *
 * The rule is symmetric — both sides compute the same winner from what each
 * advertises: the side with MORE clients keeps its group; on equal counts the
 * higher (node id, token) wins, exactly like the election. The yielding side
 * must additionally satisfy every precondition before it moves:
 *
 *  - the other's ad is fresh (< [FRESH_MS] old);
 *  - it was seen in at least [MIN_SIGHTINGS] scans at least [MIN_SIGHTING_GAP_MS] apart;
 *  - my client count has been stable for [CLIENTS_STABLE_MS];
 *  - I did not yield within the last [YIELD_COOLDOWN_MS];
 *  - a random 2–6 s settle delay passes without the picture changing (so two
 *    owners deciding at the same moment end up in exactly one group).
 *
 * Pure Kotlin: explicit timestamps and an injectable seeded [Random], so every
 * rule is unit-testable on the JVM. Call [decide] whenever a new peer list
 * arrives or on each tick while owning a group; sightings closer than
 * [MIN_SIGHTING_GAP_MS] are dropped, so callback spam within one scan counts once.
 */
class MergeDecider(
    private val myNodeId: Int,
    private val myToken: Int,
    /** Seeded in tests so the settle delay is reproducible. */
    private val random: Random = Random(),
) {

    /** Recorded sighting times per foreign owner (bounded; only >= 2 matter). */
    private val sightings = HashMap<Int, MutableList<Long>>()

    /** Settle state: which target we are settling toward and until when. */
    private var pendingTargetNodeId: Int? = null
    private var settleUntilMs: Long = 0L
    /** The picture (both client counts) at the moment settling started. */
    private var pendingMyClients: Int = -1
    private var pendingOtherClients: Int = -1

    /** When I last yielded; blocks a second yield for [YIELD_COOLDOWN_MS]. */
    private var lastYieldAtMs: Long? = null

    /** Inclusive range size of the settle delay, in ms (2000..6000 → 4001 values). */
    private val settleRangeInt = (SETTLE_MAX_MS - SETTLE_MIN_MS).toInt() + 1

    fun decide(
        nowMs: Long,
        myClients: Int,
        /** When my client count last changed; null = never (stable since birth). */
        myClientsChangedAtMs: Long?,
        /** The strongest foreign owner in the latest scan, or null when none is visible. */
        other: MergeOwnerSighting?,
    ): MergeDecision {
        // No foreign owner visible (or it is us): nothing to merge with.
        if (other == null || other.nodeId == myNodeId) {
            pendingTargetNodeId = null
            return MergeDecision.Stay
        }

        recordSighting(other.nodeId, nowMs)

        if (!yieldsTo(myClients, other)) {
            // The bigger / higher-ranked group is mine: the staying side never initiates.
            pendingTargetNodeId = null
            return MergeDecision.Stay
        }

        val reasons = buildList {
            if (nowMs - other.lastSeenMs >= FRESH_MS) add("stale advert")
            if ((sightings[other.nodeId]?.size ?: 0) < MIN_SIGHTINGS) add("seen in one scan only")
            val changedAt = myClientsChangedAtMs
            if (changedAt != null && nowMs - changedAt < CLIENTS_STABLE_MS) add("client count unstable")
            val lastYield = lastYieldAtMs
            if (lastYield != null && nowMs - lastYield < YIELD_COOLDOWN_MS) add("yield cooldown")
        }
        if (reasons.isNotEmpty()) {
            pendingTargetNodeId = null
            return MergeDecision.Wait(reasons.joinToString(", "))
        }

        // All preconditions hold. Settle for a random 2–6 s in case the other side
        // is deciding at the same moment: exactly one of us may yield, and the
        // delay plus re-checking the picture breaks the tie.
        if (pendingTargetNodeId != other.nodeId) {
            pendingTargetNodeId = other.nodeId
            settleUntilMs = nowMs + SETTLE_MIN_MS + random.nextInt(settleRangeInt)
            pendingMyClients = myClients
            pendingOtherClients = other.clients
            return MergeDecision.Wait("settling before yield")
        }
        if (myClients != pendingMyClients || other.clients != pendingOtherClients) {
            // The picture changed while settling: start over from scratch.
            pendingTargetNodeId = null
            return MergeDecision.Wait("picture changed during settle")
        }
        if (nowMs < settleUntilMs) return MergeDecision.Wait("settling before yield")

        lastYieldAtMs = nowMs
        pendingTargetNodeId = null
        sightings.remove(other.nodeId) // I am about to leave this group; start fresh later
        return MergeDecision.Yield(targetAddress = other.address, targetNodeId = other.nodeId)
    }

    /** True when [other] beats my group: more clients, or equal and higher rank. */
    private fun yieldsTo(myClients: Int, other: MergeOwnerSighting): Boolean = when {
        other.clients > myClients -> true // the bigger group stays
        other.clients < myClients -> false // an owner with clients never yields to fewer
        else -> autoMeshRank(other.nodeId, other.token) > autoMeshRank(myNodeId, myToken)
    }

    /** Note that [targetNodeId] was visible at [nowMs]; near-duplicates are dropped. */
    private fun recordSighting(targetNodeId: Int, nowMs: Long) {
        val list = sightings.getOrPut(targetNodeId) { mutableListOf() }
        val last = list.lastOrNull()
        if (last == null || nowMs - last >= MIN_SIGHTING_GAP_MS) {
            list.add(nowMs)
            if (list.size > MAX_RECORDED_SIGHTINGS) list.removeAt(0)
        }
    }

    companion object {
        /** A foreign owner's ad older than this is stale and never triggers a yield. */
        const val FRESH_MS = 120_000L

        /** The owner must have been seen in at least this many scans... */
        const val MIN_SIGHTINGS = 2

        /** ...at least this far apart (callback spam within one scan counts once). */
        const val MIN_SIGHTING_GAP_MS = 6_000L

        /** My client count must not have changed within this window. */
        const val CLIENTS_STABLE_MS = 5_000L

        /** No second yield within this window (covers the join + re-form time). */
        const val YIELD_COOLDOWN_MS = 120_000L

        /** Random settle delay before a yield: [SETTLE_MIN_MS, SETTLE_MAX_MS]. */
        const val SETTLE_MIN_MS = 2_000L
        const val SETTLE_MAX_MS = 6_000L

        private const val MAX_RECORDED_SIGHTINGS = 8
    }
}

/** (Node id, token) as one comparable value — the same ordering the election uses. */
fun autoMeshRank(nodeId: Int, token: Int): Long =
    (nodeId.toLong() shl 32) or token.toUInt().toLong()
