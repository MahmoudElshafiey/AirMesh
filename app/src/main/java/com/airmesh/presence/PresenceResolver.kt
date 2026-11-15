package com.airmesh.presence

/**
 * Truthful presence states (TASK U5 part 1). Unlike the legacy `chat.PresenceState`
 * ("any frame within 100 s"), these distinguish a live direct socket from relayed evidence,
 * react immediately to a dropped direct link, and go NOT_CONNECTED as soon as we have zero
 * neighbors. The old enum stays for its tests; the UI switches to this one in TASK U5 part 2.
 */
sealed interface PresenceState {
    /** We hold an open socket to this node right now. */
    object ONLINE_DIRECT : PresenceState

    /** No direct link, but a frame from them arrived via a relay recently (< 60 s). */
    object ONLINE_RELAYED : PresenceState

    /** Evidence is aging, or our own link just came back: not confirmed either way yet. */
    object CHECKING : PresenceState

    /** They were our direct neighbor and that link dropped with no newer relayed frame. */
    object OUT_OF_RANGE : PresenceState

    /** We have zero live neighbors: nothing can be delivered right now, for anybody. */
    object NOT_CONNECTED : PresenceState

    /** Last evidence is older than the checking window; [atMs] is when it arrived. */
    data class LastSeen(val atMs: Long) : PresenceState

    /** Never seen and not a neighbor: no data to judge from (UI keeps its own fallback). */
    object UNKNOWN : PresenceState
}

/** Snapshot of our own mesh link, tracked by the service (TASK U5 part 1). */
data class OwnLink(
    val liveNeighborCount: Int,
    /** When our neighbor set last went empty; null while connected or if it never emptied. */
    val linkLostAt: Long? = null,
    /** When our neighbor set became non-empty again after a loss. */
    val linkRestoredAt: Long? = null,
)

/** Everything the resolver knows about one contact (TASK U5 part 1). */
data class ContactEvidence(
    val isDirectNeighbor: Boolean = false,
    /** When this node stopped being our direct neighbor; null if it never was. */
    val directLostAt: Long? = null,
    /** Last frame of any kind from this origin (direct or relayed); null if none ever. */
    val lastActivityAt: Long? = null,
    /** Last frame received while they were a direct neighbor; null if none. */
    val lastDirectActivityAt: Long? = null,
    /** Whether the previously published state was ONLINE_DIRECT/ONLINE_RELAYED. */
    val previousStateWasOnline: Boolean = false,
)

/**
 * Pure presence rules (TASK U5 part 1). No Android imports; the clock is injected so tests
 * drive time explicitly. Every threshold is a constructor parameter with the production
 * default, so tests can shrink them without touching these values.
 */
class PresenceResolver(
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Relayed evidence younger than this means "online via relay". Default 60 s. */
    private val relayedOnlineMs: Long = DEFAULT_RELAYED_ONLINE_MS,
    /** Relayed evidence up to this age is still "checking"; older becomes LastSeen. Default 100 s. */
    private val checkingWindowEndMs: Long = DEFAULT_CHECKING_WINDOW_END_MS,
    /** How often nodes announce themselves; bounds the grace after our own link restores. Default 45 s. */
    private val announceIntervalMs: Long = DEFAULT_ANNOUNCE_INTERVAL_MS,
) {
    companion object {
        const val DEFAULT_RELAYED_ONLINE_MS = 60_000L
        const val DEFAULT_CHECKING_WINDOW_END_MS = 100_000L
        const val DEFAULT_ANNOUNCE_INTERVAL_MS = 45_000L

        /** Extra slack after a restore: the peer's next announce may be up to this late. */
        const val RESTORE_GRACE_SLACK_MS = 5_000L
    }

    fun resolve(ownLink: OwnLink, evidence: ContactEvidence): PresenceState {
        // Rule 1: with zero live neighbors we are off the mesh entirely; nothing is reachable.
        if (ownLink.liveNeighborCount == 0) return PresenceState.NOT_CONNECTED
        // Rule 2: a live direct socket is the strongest possible signal.
        if (evidence.isDirectNeighbor) return PresenceState.ONLINE_DIRECT

        val lastActivity = evidence.lastActivityAt
        val directLostAt = evidence.directLostAt
        // Rule 3: they were our direct neighbor and that link dropped. Only a relayed frame
        // STRICTLY after the drop proves they are still out there; otherwise it is over now,
        // without waiting for any timer to age out. No frames at all also counts as "over":
        // we held the socket, then lost it — nothing says they are anywhere else.
        if (directLostAt != null && (lastActivity == null || lastActivity <= directLostAt)) {
            return PresenceState.OUT_OF_RANGE
        }
        if (lastActivity == null) return PresenceState.UNKNOWN

        // Rule 4a: our own link just came back and we have no frame from them since the
        // restore, so their pre-loss evidence may be stale (we were deaf during the outage).
        // Give it one announce cycle of grace — only if they were online before the loss;
        // after that the old evidence is simply LastSeen.
        val restoredAt = ownLink.linkRestoredAt
        if (restoredAt != null && lastActivity <= restoredAt) {
            val sinceRestoreMs = clock() - restoredAt
            if (evidence.previousStateWasOnline &&
                sinceRestoreMs < announceIntervalMs + RESTORE_GRACE_SLACK_MS
            ) {
                return PresenceState.CHECKING
            }
            return PresenceState.LastSeen(lastActivity)
        }

        // Rule 4b: plain relayed evidence, bucketed by age. Boundaries are inclusive on the
        // checking side: exactly 60 s is already "checking", exactly 100 s still is.
        val ageMs = clock() - lastActivity
        return when {
            ageMs < relayedOnlineMs -> PresenceState.ONLINE_RELAYED
            ageMs <= checkingWindowEndMs -> PresenceState.CHECKING
            else -> PresenceState.LastSeen(lastActivity)
        }
    }
}
