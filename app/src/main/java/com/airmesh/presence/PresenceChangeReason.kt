package com.airmesh.presence

/**
 * Why a node's presence state changed between two recomputations (TASK U5 part 4). The label is
 * what goes into the event-log line: "presence: #id OLD -> NEW reason=directLinkDropped".
 */
enum class PresenceChangeReason(val label: String) {
    /** They were our direct neighbor and that socket went away while we stayed connected. */
    DIRECT_LINK_DROPPED("directLinkDropped"),

    /** Our whole link went down, so every peer became NOT_CONNECTED at once. */
    OWN_LINK_LOST("ownLinkLost"),

    /** No new frame arrived in time; the state aged out (e.g. checking -> last seen). */
    EVIDENCE_EXPIRED("evidenceExpired"),

    /** A fresh frame from them arrived and moved their state forward. */
    FRESH_EVIDENCE("freshEvidence"),

    /** Our own link came back and a peer that was NOT_CONNECTED is reachable again. */
    LINK_RESTORED("linkRestored"),
}

/** What changed this recompute round, per node; the service fills it from its bookkeeping. */
data class PresenceChangeContext(
    /** The neighbor set went non-empty -> empty since the previous recompute. */
    val ownLinkWentDown: Boolean = false,
    /** The neighbor set went empty -> non-empty since the previous recompute. */
    val ownLinkCameUp: Boolean = false,
    /** This node left our direct neighbor set while we are still connected to somebody. */
    val directLinkDropped: Boolean = false,
    /** At least one frame from this node arrived since the previous recompute. */
    val freshFrameArrived: Boolean = false,
)

/**
 * The reason for a state change, or null when nothing changed (no log line). Priority follows
 * causality: our own link explains mass changes first, then the peer's direct drop, then frame
 * evidence; whatever is left over is plain aging.
 */
fun presenceChangeReason(
    previous: PresenceState,
    next: PresenceState,
    context: PresenceChangeContext,
): PresenceChangeReason? {
    if (previous == next) return null
    return when {
        context.ownLinkWentDown && next == PresenceState.NOT_CONNECTED -> PresenceChangeReason.OWN_LINK_LOST
        context.ownLinkCameUp && previous == PresenceState.NOT_CONNECTED -> PresenceChangeReason.LINK_RESTORED
        context.directLinkDropped && previous == PresenceState.ONLINE_DIRECT -> PresenceChangeReason.DIRECT_LINK_DROPPED
        context.freshFrameArrived -> PresenceChangeReason.FRESH_EVIDENCE
        else -> PresenceChangeReason.EVIDENCE_EXPIRED
    }
}

/** Stable name for log lines: "LastSeen" instead of the data class's toString with its timestamp. */
fun presenceLogName(state: PresenceState): String = when (state) {
    is PresenceState.LastSeen -> "LastSeen"
    else -> state::class.simpleName ?: state.toString()
}

/** The full event-log line for one transition, or null when the state did not change. */
fun presenceTransitionLine(
    nodeId: Int,
    previous: PresenceState,
    next: PresenceState,
    context: PresenceChangeContext,
): String? {
    val reason = presenceChangeReason(previous, next, context) ?: return null
    return "presence: #$nodeId ${presenceLogName(previous)} -> ${presenceLogName(next)} reason=${reason.label}"
}
