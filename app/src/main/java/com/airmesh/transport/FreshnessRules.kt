package com.airmesh.transport

/**
 * Freshness rules for automatic recovery after an unexpected group loss (TASK R3 part 2).
 * Pure Kotlin so they are unit-testable on the JVM with a fake clock.
 *
 * After our own group is lost unexpectedly, the peer table may still carry adverts that were
 * last seen BEFORE the loss: those describe a world that no longer exists (the owner we would
 * join may be the very phone whose group just died). Such adverts are not trusted for joining
 * until a new scan re-confirms them; independently, any advert older than [OWNER_ADVERT_MAX_AGE_MS]
 * is stale. While we have no group of our own the peer table also ages out faster
 * ([PEER_STALE_NO_GROUP_MS] instead of [PEER_STALE_MS]) so a dead mesh does not linger for two minutes.
 */
object FreshnessRules {

    /** Owner adverts older than this are never trusted for joining, even right after re-confirmation. */
    const val OWNER_ADVERT_MAX_AGE_MS = 30_000L

    /** While no group of our own exists, peer entries not refreshed within this window age out. */
    const val PEER_STALE_NO_GROUP_MS = 60_000L

    /**
     * Whether an owner advert may be joined right now. [unexpectedLossAtMs] is null when we have
     * never lost a group unexpectedly (fresh startup): every advert is trusted. After a loss, an
     * advert is trusted only if it was seen at or after the loss — i.e. re-confirmed by a new scan —
     * and is not older than [OWNER_ADVERT_MAX_AGE_MS]. A peer with no timestamp ([lastSeenMs] == 0,
     * the data-class default used for synthetic peers) carries no evidence of being pre-loss and
     * stays trusted; real directory entries are always stamped by the ingest.
     */
    fun ownerAdvertTrustedForJoin(peer: AirMeshPeer, nowMs: Long, unexpectedLossAtMs: Long?): Boolean {
        if (unexpectedLossAtMs == null) return true
        if (peer.lastSeenMs == 0L) return true // no timestamp: nothing to gate on
        if (peer.lastSeenMs < unexpectedLossAtMs) return false // seen before the loss: wait for a re-confirming scan
        return nowMs - peer.lastSeenMs <= OWNER_ADVERT_MAX_AGE_MS
    }

    /** How long a peer-table entry may go without refresh before it ages out. */
    fun peerStaleThresholdMs(ourGroupPresent: Boolean): Long =
        if (ourGroupPresent) PEER_STALE_MS else PEER_STALE_NO_GROUP_MS

    /** Group-aware variant of [shouldExpirePeer]: 120 s with a group, 60 s without one. */
    fun shouldExpirePeerNow(lastSeenMs: Long, nowMs: Long, ourGroupPresent: Boolean): Boolean =
        nowMs - lastSeenMs > peerStaleThresholdMs(ourGroupPresent)

    /** The owner flag our own service advert must carry right now (o1 only while we own a live group). */
    fun desiredOwnerFlag(groupFormed: Boolean, role: P2pRole): Boolean =
        groupFormed && role == P2pRole.GROUP_OWNER
}
