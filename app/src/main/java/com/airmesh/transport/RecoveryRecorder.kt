package com.airmesh.transport

/**
 * One nearby peer as shown in a "STILL NOT CONNECTED" diagnostic line (TASK R3 part 1).
 */
data class RecoveryPeerInfo(
    val nodeId: Int,
    val owner: Boolean,
    val clients: Int,
    /** Milliseconds since this peer was last seen. */
    val lastSeenAgoMs: Long,
)

/**
 * Live context for one "STILL NOT CONNECTED" diagnostic line (TASK R3 part 1). The service
 * builds it from the directory, coordinator, connector and advertisement state; pending
 * timers are recorded by [RecoveryRecorder] itself on controller transitions.
 */
data class RecoverySnapshot(
    val stateLine: String,
    val peers: List<RecoveryPeerInfo>,
    val discoveryActive: Boolean,
    /** Milliseconds since the last scan cycle was issued; null when none yet. */
    val lastScanAgoMs: Long?,
    val serviceAdvertOwner: Boolean,
    val serviceAdvertClients: Int,
    val groupFormed: Boolean,
    val tcpRetryRunning: Boolean,
)

/**
 * Forensic recorder for automatic reconnection episodes (TASK R3 part 1). Pure Kotlin with an
 * injectable clock so the episode lifecycle and diagnostic cadence are unit-testable on the JVM.
 *
 * An episode starts when we lose our last live neighbor or group while auto-mesh is on, and ends
 * when a neighbor comes back up:
 * - start logs `recovery: started cause=... state=... groupFormed=...`
 * - completion logs `recovery: complete after Xms path=<join|create|reconnectTcp|merge|supervisor>`
 * - while an episode is active, [tick] emits one diagnostic line at 120 s and then every 30 s.
 *
 * Every controller transition and pending timer during the episode is recorded (bounded) so the
 * diagnostics can show what was in flight; see [transitions] and the `timers=[...]` segment.
 */
class RecoveryRecorder(
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    /** True while a recovery episode is being tracked. */
    var active: Boolean = false
        private set

    /** Cause of the current episode; null when no episode is active. */
    var cause: String? = null
        private set

    private var startedAtMs = 0L
    private var lastDiagnosticAtMs = 0L
    private var groupFormedAtStart = false
    private var roleAtStart: P2pRole = P2pRole.NONE
    private var groupLostDuringEpisode = false
    private var mergeAttempted = false

    /** Controller transitions recorded during the episode, newest last (bounded). */
    val transitions: List<String> get() = _transitions.toList()
    private val _transitions = mutableListOf<String>()

    /** Pending timers from controller transitions; name -> absolute untilMs, null = running. */
    private val pendingTimers = LinkedHashMap<String, Long?>()

    /**
     * Starts tracking a recovery episode. Returns false (and logs nothing) when an episode is
     * already active: the first loss wins and later losses only update context via [noteGroupLost].
     */
    fun onRecoveryStarted(cause: String, stateLine: String, groupFormed: Boolean, role: P2pRole): Boolean {
        if (active) return false
        active = true
        this.cause = cause
        startedAtMs = clock()
        lastDiagnosticAtMs = startedAtMs
        groupFormedAtStart = groupFormed
        roleAtStart = role
        mergeAttempted = false
        _transitions.clear()
        pendingTimers.clear()
        log("recovery: started cause=$cause state=$stateLine groupFormed=$groupFormed")
        return true
    }

    /** Notes that our own group was lost during this episode (affects the completion path). */
    fun noteGroupLost() {
        if (active) groupLostDuringEpisode = true
    }

    /** Records one controller state change; kept for forensics, bounded to [MAX_RECORDED_TRANSITIONS]. */
    fun noteControllerTransition(fromLine: String, toLine: String) {
        if (!active) return
        _transitions.add("+${(clock() - startedAtMs) / 1000}s $fromLine -> $toLine")
        while (_transitions.size > MAX_RECORDED_TRANSITIONS) _transitions.removeAt(0)
    }

    /** Replaces the pending-timer record (name -> absolute untilMs, null = running/indeterminate). */
    fun noteTimers(pending: Map<String, Long?>) {
        if (!active) return
        pendingTimers.clear()
        pendingTimers.putAll(pending)
    }

    /** Notes that a merge/yield was attempted during this episode (affects the completion path). */
    fun noteMergeAttempted() {
        if (active) mergeAttempted = true
    }

    /** Clears [noteMergeAttempted] when the yield fails and we leave it without joining. */
    fun noteMergeAbandoned() {
        if (active) mergeAttempted = false
    }

    /** Ends the episode silently: automatic recovery is no longer expected (e.g. auto-mesh paused). */
    fun onRecoveryAborted() {
        active = false
        cause = null
        _transitions.clear()
        pendingTimers.clear()
    }

    /** A neighbor came up: logs completion with the elapsed time and which path got us there. */
    fun onNeighborUp(roleNow: P2pRole, pathOverride: String? = null) {
        if (!active) return
        val elapsedMs = clock() - startedAtMs
        val path = pathOverride ?: recoveryCompletionPath(
            groupFormedAtStart, roleAtStart, !groupLostDuringEpisode, roleNow, mergeAttempted,
        )
        active = false
        cause = null
        log("recovery: complete after ${elapsedMs}ms path=$path")
    }

    /**
     * Periodic call (the service ticks about once per second): while an episode is active and at
     * least [FIRST_DIAGNOSTIC_AFTER_MS] have passed without a neighbor, emits one diagnostic line
     * every [DIAGNOSTIC_INTERVAL_MS].
     */
    fun tick(snapshot: RecoverySnapshot) {
        if (!active) return
        val now = clock()
        val elapsedMs = now - startedAtMs
        if (elapsedMs < FIRST_DIAGNOSTIC_AFTER_MS) return
        if (lastDiagnosticAtMs > 0L && now - lastDiagnosticAtMs < DIAGNOSTIC_INTERVAL_MS) return
        lastDiagnosticAtMs = now
        log(stillNotConnectedLine(elapsedMs, snapshot, pendingTimers, now))
    }

    companion object {
        // --- episode causes -----------------------------------------------------
        /** Last live neighbor lost while our group is still formed (e.g. keepalive close). */
        const val CAUSE_OWN_LINK_LOST = "ownLinkLost"
        /** Our Wi-Fi Direct group was lost. */
        const val CAUSE_GROUP_GONE = "groupGone"
        /** The transport declared the link dead after 24 s of silence (reserved for transport-level detection). */
        const val CAUSE_KEEPALIVE_CLOSE = "keepaliveClose"
        /** Wi-Fi Direct itself went off (airplane mode, toggle). */
        const val CAUSE_P2P_DISABLED = "p2pDisabled"

        // --- completion paths ---------------------------------------------------
        const val PATH_JOIN = "join"
        const val PATH_CREATE = "create"
        const val PATH_RECONNECT_TCP = "reconnectTcp"
        const val PATH_MERGE = "merge"
        const val PATH_SUPERVISOR = "supervisor"

        /** The first diagnostic appears this long after the episode started. */
        const val FIRST_DIAGNOSTIC_AFTER_MS = 120_000L
        /** Diagnostics repeat at most once per interval afterwards. */
        const val DIAGNOSTIC_INTERVAL_MS = 30_000L

        private const val MAX_RECORDED_TRANSITIONS = 50

        /**
         * Which path got us back to a neighbor: a merge attempt wins; otherwise, if the same group
         * survived (formed at start, never lost, same role), it was a plain TCP reconnect; an owner
         * now created the group; anything else joined one.
         */
        fun recoveryCompletionPath(
            groupFormedAtStart: Boolean,
            roleAtStart: P2pRole,
            groupSurvived: Boolean,
            roleNow: P2pRole,
            mergeAttempted: Boolean,
        ): String = when {
            mergeAttempted -> PATH_MERGE
            groupFormedAtStart && groupSurvived && roleAtStart != P2pRole.NONE && roleAtStart == roleNow -> PATH_RECONNECT_TCP
            roleNow == P2pRole.GROUP_OWNER -> PATH_CREATE
            else -> PATH_JOIN
        }

        /** Renders the "STILL NOT CONNECTED" diagnostic line (pure, for tests). */
        fun stillNotConnectedLine(
            elapsedMs: Long,
            snapshot: RecoverySnapshot,
            pendingTimers: Map<String, Long?>,
            nowMs: Long,
        ): String {
            val peersText = if (snapshot.peers.isEmpty()) "[]" else snapshot.peers.joinToString(prefix = "[", postfix = "]") { p ->
                "#${p.nodeId}${if (p.owner) " owner" else ""} clients=${p.clients} lastSeen=${p.lastSeenAgoMs / 1000}s"
            }
            val timersText = if (pendingTimers.isEmpty()) "[]" else pendingTimers.entries.joinToString(prefix = "[", postfix = "]") { (name, until) ->
                if (until == null) "$name:running" else "${name}:${((until - nowMs).coerceAtLeast(0L)) / 1000}s"
            }
            val lastScanText = snapshot.lastScanAgoMs?.let { "lastScanAgo=${it / 1000}s" } ?: "lastScanAgo=never"
            return "recovery: STILL NOT CONNECTED after ${elapsedMs / 1000}s: state=${snapshot.stateLine}, peers=$peersText, timers=$timersText, discovery=${if (snapshot.discoveryActive) "active" else "inactive"} $lastScanText, serviceAdvert=o${if (snapshot.serviceAdvertOwner) 1 else 0} c=${snapshot.serviceAdvertClients}, groupFormed=${snapshot.groupFormed}, tcpRetry=${if (snapshot.tcpRetryRunning) "running" else "stopped"}"
        }
    }
}
