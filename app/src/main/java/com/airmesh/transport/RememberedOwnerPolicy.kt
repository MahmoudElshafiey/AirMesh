package com.airmesh.transport

import java.util.Random

/**
 * TASK T1 part C2: decides when the auto-mesh controller may try to re-join a remembered owner
 * while DNS-SD stays silent (evidence run2-A54/A55: after a restart the client saw its owner in
 * requestPeers for 97 s without a single DNS-SD callback, so the normal join rule never fired).
 *
 * The controller consults this ONLY from its Searching decision while the DNS-SD peer list is
 * empty (rule d), with auto on and no manual pause — those preconditions hold by construction.
 * This policy checks the rest: we have been searching for at least [MIN_SEARCHING_MS], a record
 * exists, it is younger than [RECORD_MAX_AGE_MS], fewer than [MAX_ATTEMPTS_PER_RECORD] attempts
 * failed so far, the pacing stamp from the previous attempt has elapsed, and the remembered
 * address appears in the latest requestPeers list with status AVAILABLE. On success it stamps
 * the store with the next allowed time ([ATTEMPT_SPACING_MS] + jitter) and returns a [Target].
 */
class RememberedOwnerPolicy(
    private val store: RememberedOwnerStore,
    /** Injectable clock (fake in tests). */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Jitter source for the attempt spacing; seeded in tests. */
    private val random: Random = Random(),
) {

    companion object {
        /** Only consider an attempt after searching this long (the evidence advert arrived ~7 s late). */
        const val MIN_SEARCHING_MS = 12_000L

        /** A record older than this is too stale to trust. */
        const val RECORD_MAX_AGE_MS = 30 * 60_000L

        /** Minimum spacing between attempts on the same record. */
        const val ATTEMPT_SPACING_MS = 45_000L

        /** Random jitter added to the spacing so phones do not retry in lockstep. */
        const val SPACING_JITTER_MAX_MS = 2_000L

        /** The record is abandoned after this many attempts (the store clears it). */
        const val MAX_ATTEMPTS_PER_RECORD = RememberedOwnerStore.MAX_FAILED_ATTEMPTS
    }

    /** A re-join target the controller should connect to right now. */
    data class Target(
        val deviceAddress: String,
        val nodeId: Int,
        /** 1-based attempt number for logging. */
        val attemptNumber: Int,
    )

    /**
     * Returns a [Target] when every precondition holds, otherwise null. On success the store is
     * stamped with the next allowed time so the caller cannot re-attempt before it elapses even
     * if this join fails instantly.
     */
    fun consider(nowMs: Long, searchingSinceMs: Long, radioPeers: List<RadioPeer>): Target? {
        if (nowMs - searchingSinceMs < MIN_SEARCHING_MS) return null
        val record = store.current() ?: return null
        if (nowMs - record.recordedAtMs >= RECORD_MAX_AGE_MS) return null
        if (record.failedAttempts >= MAX_ATTEMPTS_PER_RECORD) return null
        record.nextAllowedAttemptAtMs?.let { next -> if (nowMs < next) return null }
        val peer = radioPeers.firstOrNull { it.deviceAddress == record.deviceAddress && it.status == RadioPeer.AVAILABLE }
            ?: return null
        store.noteAttempt(nowMs + ATTEMPT_SPACING_MS + random.nextInt(SPACING_JITTER_MAX_MS.toInt() + 1))
        return Target(record.deviceAddress, record.nodeId, record.failedAttempts + 1)
    }

    /** A remembered-owner attempt failed; the store clears itself after the third. */
    fun noteFailure(): Unit = store.noteFailure()

    /** A remembered-owner join landed: refreshes the record (new timestamp, fresh attempts). */
    fun noteSuccess(): Unit = store.refreshSuccess()
}
