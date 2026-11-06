package com.airmesh.routing

import com.airmesh.protocol.Frame
import java.util.concurrent.ConcurrentHashMap

/**
 * Store-and-forward queue and replay engine.
 *
 * The router writes through this facade, so every [save] immediately applies
 * the expiry and capacity policy instead of waiting for the next replay. When a
 * neighbor appears, [onNeighborAppeared] sends it every stored frame it has not
 * received yet.
 *
 * Thread-safety: the in-flight claim set is backed by a concurrent set. No lock
 * is held while [send] or any [PacketStore] operation runs, so a slow socket
 * write or a Room transaction never blocks other threads. Concurrent
 * [onNeighborAppeared] calls for the same neighbor are safe: each
 * `(neighbor, frame)` pair is claimed by exactly one caller before sending, and
 * delivery is re-checked after the claim because another caller may have
 * finished the same send in the meantime.
 *
 * Best-effort semantics: a successful send is marked delivered, but a crash
 * between send and [PacketStore.markDelivered] can cause one duplicate later.
 * The receiver's [DedupCache] absorbs that duplicate.
 *
 * @property store durable or in-memory backing store.
 * @property send hands one stored frame to one neighbor; returns false when the
 *   neighbor is unreachable, so the frame stays queued for a later retry.
 * @property clock current wall-clock time in milliseconds, injectable for tests.
 * @property maxAgeMs frames older than this are discarded (10 minutes default).
 * @property maxStoreSize maximum queued frames; oldest are dropped first.
 * @property log receives one line per replay trigger and per frame attempted,
 *   so the on-device event log shows exactly what a replay did. No-op by
 *   default, which keeps pure-Kotlin callers (simulator, tests) quiet.
 */
class StoreAndForward(
    private val store: PacketStore,
    private val send: suspend (neighborId: Int, frame: Frame) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxStoreSize: Int = DEFAULT_MAX_STORE_SIZE,
    private val log: (String) -> Unit = {},
) : PacketStore by store {

    companion object {
        /** Default maximum age of a queued frame: 10 minutes. */
        const val DEFAULT_MAX_AGE_MS = 10 * 60 * 1000L

        /** Default maximum number of queued frames. */
        const val DEFAULT_MAX_STORE_SIZE = 500
    }

    init {
        require(maxAgeMs > 0) { "maxAgeMs must be positive: $maxAgeMs" }
        require(maxStoreSize > 0) { "maxStoreSize must be positive: $maxStoreSize" }
    }

    /** Identity of one pending `(neighbor, frame)` send. */
    private data class DeliveryKey(val neighborId: Int, val nodeId: Int, val seq: Int)

    /**
     * Claims currently being sent. A claim is added before the send starts and
     * removed afterwards; concurrent replays that lose the claim skip the
     * frame and leave it to the winner.
     */
    private val inFlight: MutableSet<DeliveryKey> = ConcurrentHashMap.newKeySet()

    /**
     * Stores [frame] after pruning expired and excess entries.
     *
     * Frames at their last hop (`ttl <= 1`) are not queued: they can never be
     * forwarded again, so keeping them would only waste capacity.
     */
    override suspend fun save(
        frame: Frame,
        deliveredToNeighbors: Set<Int>,
        createdAt: Long,
    ) {
        if (frame.ttl <= 1) return

        // Enforce the age bound before inserting, so even a node that stays
        // isolated forever cannot keep expired frames.
        store.deleteOlderThan(createdAt - maxAgeMs)
        store.save(frame, deliveredToNeighbors, createdAt)
        store.trimToSize(maxStoreSize)
    }

    /**
     * Replays every stored frame that [neighborId] has not received yet.
     *
     * Expired frames are deleted first. A frame is sent only by the caller that
     * successfully claims it in [inFlight]. After claiming, delivery is checked
     * again: another thread may have sent it between [pendingFor] and the
     * claim. Failed sends are left queued, so the next neighbor event retries
     * them.
     */
    suspend fun onNeighborAppeared(neighborId: Int) {
        val notBefore = clock() - maxAgeMs
        store.deleteOlderThan(notBefore)

        // One snapshot per trigger; the log line reports both the total queue
        // size and what is actually pending for this neighbor, so a silent
        // replay (trigger fired but nothing sent) is visible in the event log.
        val pending = store.pendingFor(neighborId, notBefore)
        log("replay: neighbor $neighborId: ${store.count()} stored, ${pending.size} pending for this neighbor")

        for (stored in pending) {
            val frame = stored.frame
            if (frame.ttl <= 1) continue

            val key = DeliveryKey(neighborId, frame.nodeId, frame.seq)
            if (!inFlight.add(key)) {
                log("replay TX seq=${frame.seq} -> $neighborId skipped(in-flight)")
                continue
            }
            try {
                // Re-check after claiming: a concurrent replay may have marked
                // this frame delivered after pendingFor() took its snapshot.
                if (store.isDelivered(frame.nodeId, frame.seq, neighborId)) {
                    log("replay TX seq=${frame.seq} -> $neighborId skipped(already-delivered)")
                    continue
                }

                val sent = send(neighborId, frame)
                if (sent) {
                    store.markDelivered(frame.nodeId, frame.seq, neighborId)
                }
                log("replay TX seq=${frame.seq} -> $neighborId ${if (sent) "ok" else "failed"}")
            } finally {
                inFlight.remove(key)
            }
        }
    }
}
