package com.airmesh.transport

/**
 * Pure monotonic-clock recorder for startup-to-neighbor-up latency phases (Part 3).
 *
 * Records the first occurrence of each phase in a session and formats:
 * `auto: timeline start->searching=Xms searching->firstScan=Xms firstScan->firstPeer=Xms firstPeer->decision=Xms decision->groupFormed=Xms groupFormed->neighborUp=Xms total=Xms`
 *
 * Any phase whose start or end milestone has not occurred yet is reported as `n/a`.
 */
class TimelineRecorder(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    var startMs: Long = clock()
        private set

    var searchingMs: Long? = null
        private set

    var firstScanMs: Long? = null
        private set

    var firstPeerMs: Long? = null
        private set

    var decisionMs: Long? = null
        private set

    var groupFormedMs: Long? = null
        private set

    var neighborUpMs: Long? = null
        private set

    /** Reset all milestones and anchor the session start to [nowMs]. */
    fun onStart(nowMs: Long = clock()) {
        startMs = nowMs
        searchingMs = null
        firstScanMs = null
        firstPeerMs = null
        decisionMs = null
        groupFormedMs = null
        neighborUpMs = null
    }

    /** Record the first entry into [AutoMeshState.Searching] (ignored when [stale] is true). */
    fun onSearching(nowMs: Long = clock(), stale: Boolean = false) {
        if (stale) return
        if (searchingMs == null) searchingMs = nowMs
    }

    /** Record the first DNS-SD service scan issued in this session (ignored when [stale] is true). */
    fun onFirstScan(nowMs: Long = clock(), stale: Boolean = false) {
        if (stale) return
        if (firstScanMs == null) firstScanMs = nowMs
    }

    /** Record the first non-empty AirMesh peer sighting in this session (ignored when [stale] is true). */
    fun onFirstPeer(nowMs: Long = clock(), stale: Boolean = false) {
        if (stale) return
        if (firstPeerMs == null) firstPeerMs = nowMs
    }

    /** Record the first election decision (Creating or Joining) in this session (ignored when [stale] is true). */
    fun onDecision(nowMs: Long = clock(), stale: Boolean = false) {
        if (stale) return
        if (decisionMs == null) {
            decisionMs = nowMs
            // Any groupFormed recorded before the first decision came from a stale group; clear it.
            val formed = groupFormedMs
            if (formed != null && formed < nowMs) {
                groupFormedMs = null
            }
        }
    }

    /** Record the first Wi-Fi Direct group formation in this session (ignored when [stale] is true). */
    fun onGroupFormed(nowMs: Long = clock(), stale: Boolean = false) {
        if (stale) return
        if (groupFormedMs == null) groupFormedMs = nowMs
    }

    /**
     * Record the first neighbor coming up in this session and return the timeline
     * log line. Subsequent calls in the same session (or calls marked [stale])
     * return null so the neighbor-up timeline line is logged only once per session.
     */
    fun onNeighborUp(nowMs: Long = clock(), stale: Boolean = false): String? {
        if (stale || neighborUpMs != null) return null
        neighborUpMs = nowMs
        return formatLine(nowMs)
    }

    /**
     * Observe a controller transition from [previous] to [current]; updates the
     * matching milestone (unless [stale] is true) and returns the formatted timeline
     * line when the state changed, or null when [stale] is true or the state stayed the same.
     */
    fun onAutoStateChanged(
        previous: AutoMeshState,
        current: AutoMeshState,
        nowMs: Long = clock(),
        stale: Boolean = false,
    ): String? {
        if (stale) return null
        when (current) {
            is AutoMeshState.Searching -> onSearching(nowMs)
            is AutoMeshState.Creating, is AutoMeshState.Joining -> onDecision(nowMs)
            is AutoMeshState.Owner, is AutoMeshState.Client -> onGroupFormed(nowMs)
            else -> Unit
        }
        return if (previous != current) formatLine(nowMs) else null
    }

    /** Format the current timeline snapshot, reporting missing or negative phases as `n/a`. */
    fun formatLine(nowMs: Long = clock()): String {
        val startToSearching = delta(startMs, searchingMs)
        val searchingToFirstScan = delta(searchingMs, firstScanMs)
        val firstScanToFirstPeer = delta(firstScanMs, firstPeerMs)
        val firstPeerToDecision = delta(firstPeerMs, decisionMs)
        val decisionToGroupFormed = delta(decisionMs, groupFormedMs)
        val groupFormedToNeighborUp = delta(groupFormedMs, neighborUpMs)
        val endMs = neighborUpMs ?: nowMs
        val total = if (endMs >= startMs) "${endMs - startMs}ms" else "n/a"
        return "auto: timeline " +
            "start->searching=$startToSearching " +
            "searching->firstScan=$searchingToFirstScan " +
            "firstScan->firstPeer=$firstScanToFirstPeer " +
            "firstPeer->decision=$firstPeerToDecision " +
            "decision->groupFormed=$decisionToGroupFormed " +
            "groupFormed->neighborUp=$groupFormedToNeighborUp " +
            "total=$total"
    }

    private fun delta(fromMs: Long?, toMs: Long?): String =
        if (fromMs != null && toMs != null && toMs >= fromMs) "${toMs - fromMs}ms" else "n/a"
}
