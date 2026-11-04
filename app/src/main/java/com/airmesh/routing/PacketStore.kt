package com.airmesh.routing

import com.airmesh.protocol.Frame

/**
 * A frame held for later delivery to neighbors that have not received it yet.
 *
 * @property frame outgoing copy; for a forwarded frame the TTL is already
 *   decremented, so replaying it does not give the frame extra hops.
 * @property createdAt wall-clock time when the frame entered the store, in
 *   milliseconds since the epoch.
 * @property deliveredToNeighbors node ids that already received this frame.
 */
data class StoredFrame(
    val frame: Frame,
    val createdAt: Long,
    val deliveredToNeighbors: Set<Int>,
)

/**
 * Persistence contract for store-and-forward.
 *
 * Implementations may perform disk I/O, so every operation suspends. They must
 * be safe to call from several coroutines at once. None of these methods invoke
 * application callbacks, so implementations can use their own internal locks
 * freely; callers must never hold a lock while awaiting one of them.
 *
 * Frames are identified by `(nodeId, seq)`, exactly like [DedupCache].
 */
interface PacketStore {

    /**
     * Adds [frame], replacing any existing entry with the same
     * `(nodeId, seq)`.
     *
     * [createdAt] is passed explicitly so tests can control expiry. Production
     * callers rely on the default wall clock.
     */
    suspend fun save(
        frame: Frame,
        deliveredToNeighbors: Set<Int> = emptySet(),
        createdAt: Long = System.currentTimeMillis(),
    )

    /**
     * Frames created at or after [notBefore] that [neighborId] has not received
     * yet, oldest first. This is a snapshot: other coroutines may change the
     * store immediately afterwards.
     */
    suspend fun pendingFor(neighborId: Int, notBefore: Long): List<StoredFrame>

    /** Whether [neighborId] already received the frame `(nodeId, seq)`. */
    suspend fun isDelivered(nodeId: Int, seq: Int, neighborId: Int): Boolean

    /** Records that [neighborId] received `(nodeId, seq)`. Idempotent. */
    suspend fun markDelivered(nodeId: Int, seq: Int, neighborId: Int)

    /** Removes every frame created strictly before [notBefore]. */
    suspend fun deleteOlderThan(notBefore: Long)

    /** Removes the oldest frames until at most [maxSize] remain. */
    suspend fun trimToSize(maxSize: Int)
}
