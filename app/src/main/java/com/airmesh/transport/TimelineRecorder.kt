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

    /** Record the first entry into [AutoMeshState.Searching]. */
    fun onSearching(nowMs: Long = clock()) {
        if (searchingMs == null) searchingMs = nowMs
    }

    /** Record the first DNS-SD service scan issued in this session. */
    fun onFirstScan(nowMs: Long = clock()) {
        if (firstScanMs == null) firstScanMs = nowMs
    }

    /** Record the first non-empty AirMesh peer sighting in this session. */
    fun onFirstPeer(nowMs: Long = clock()) {
        if (firstPeerMs == null) firstPeerMs = nowMs
    }

    /** Record the first election decision (Creating or Joining) in this session. */
    fun onDecision(nowMs: Long = clock()) {
        if (decisionMs == null) decisionMs = nowMs
    }

    /** Record the first Wi-Fi Direct group formation in this session. */
    fun onGroupFormed(nowMs: Long = clock()) {
        if (groupFormedMs == null) groupFormedMs = nowMs
    }

    /**
     * Record the first neighbor coming up in this session and return the timeline
     * log line. Subsequent calls in the same session return null so the neighbor-up
     * timeline line is logged only once per session.
     */
    fun onNeighborUp(nowMs: Long = clock()): String? {
        if (neighborUpMs != null) return null
        neighborUpMs = nowMs
        return formatLine(nowMs)
    }

    /**
     * Observe a controller transition from [previous] to [current]; updates the
     * matching milestone and returns the formatted timeline line when the state
     * changed, or null when the state stayed the same.
     */
    fun onAutoStateChanged(
        previous: AutoMeshState,
        current: AutoMeshState,
        nowMs: Long = clock(),
    ): String? {
        when (current) {
            is AutoMeshState.Searching -> onSearching(nowMs)
            is AutoMeshState.Creating, is AutoMeshState.Joining -> onDecision(nowMs)
            is AutoMeshState.Owner, is AutoMeshState.Client -> onGroupFormed(nowMs)
            else -> Unit
        }
        return if (previous != current) formatLine(nowMs) else null
    }

    /** Format the current timeline snapshot, reporting missing phases as `n/a`. */
    fun formatLine(nowMs: Long = clock()): String {
        val startToSearching = delta(startMs, searchingMs)
        val searchingToFirstScan = delta(searchingMs, firstScanMs)
        val firstScanToFirstPeer = delta(firstScanMs, firstPeerMs)
        val firstPeerToDecision = delta(firstPeerMs, decisionMs)
        val decisionToGroupFormed = delta(decisionMs, groupFormedMs)
        val groupFormedToNeighborUp = delta(groupFormedMs, neighborUpMs)
        val endMs = neighborUpMs ?: nowMs
        val total = "${endMs - startMs}ms"
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
        if (fromMs != null && toMs != null) "${toMs - fromMs}ms" else "n/a"
}
