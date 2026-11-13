package com.airmesh.transport

/**
 * Scan policy for a group owner that wants to merge with other groups (TASK M1).
 * Pure Kotlin, driven by an explicit [nowMs] so every rule is testable on the JVM.
 *
 * - Empty owner (0 clients): scan every 15 s — cheap, and finds a peer quickly.
 * - Owner with at least one client: one scan per 90 s cycle, limited to an
 *   8 s window, and never within 10 s after a client joined (the radio is busy
 *   stabilizing the new TCP connection).
 * - While a merge is in progress, or when merging is disabled: no scans at all.
 */
class OwnerScanPolicy {

    /** When the last scan was issued; null until the first firing. */
    private var anchorMs: Long? = null

    /** When the most recent client joined; suppresses scans shortly after it. */
    private var lastClientJoinedAtMs: Long? = null

    /**
     * Whether an owner merge-scan should be issued right now. A firing advances
     * [anchorMs]; a post-join suppression does not, so the same window can still
     * fire later once the suppression expires.
     */
    fun shouldScan(clients: Int, mergeEnabled: Boolean, mergeInProgress: Boolean, nowMs: Long): Boolean {
        if (!mergeEnabled || mergeInProgress) return false
        val anchor = anchorMs
        if (anchor == null) {
            // First call after the policy was armed: scan immediately.
            anchorMs = nowMs
            return true
        }
        if (clients <= 0) {
            if (nowMs - anchor < EMPTY_GROUP_INTERVAL_MS) return false
            anchorMs = nowMs
            return true
        }
        // Owner with clients: one scan per cycle, only inside the window.
        val dueAt = anchor + CLIENTS_CYCLE_MS
        when {
            nowMs < dueAt -> return false
            nowMs > dueAt + SCAN_WINDOW_MS -> {
                // The whole window was missed (e.g. suppressed by a join): roll
                // forward one cycle instead of scanning late.
                anchorMs = dueAt
                return false
            }
        }
        val joinedAt = lastClientJoinedAtMs
        if (joinedAt != null && nowMs - joinedAt < POST_JOIN_SUPPRESS_MS) return false // still settling
        anchorMs = nowMs
        return true
    }

    /** A client just joined: suppress scans for [POST_JOIN_SUPPRESS_MS]. */
    fun noteClientJoined(nowMs: Long) {
        lastClientJoinedAtMs = nowMs
    }

    companion object {
        /** Scan interval while the group has no clients. */
        const val EMPTY_GROUP_INTERVAL_MS = 15_000L

        /** Cycle length once at least one client is in the group. */
        const val CLIENTS_CYCLE_MS = 90_000L

        /** How long a scan window stays open inside each cycle. */
        const val SCAN_WINDOW_MS = 8_000L

        /** No scans this soon after a client joined (TCP is still settling). */
        const val POST_JOIN_SUPPRESS_MS = 10_000L
    }
}
