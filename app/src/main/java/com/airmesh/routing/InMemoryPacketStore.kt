package com.airmesh.routing

import com.airmesh.protocol.Frame

/**
 * Thread-safe in-memory [PacketStore] for JVM tests and the simulator.
 *
 * One lock guards the map. No callback or suspend function runs while the lock
 * is held, matching the production rule that store I/O must not happen under a
 * caller's lock.
 */
class InMemoryPacketStore : PacketStore {

    private data class Key(val nodeId: Int, val seq: Int)

    private val lock = Any()
    private val packets = LinkedHashMap<Key, StoredFrame>()

    override suspend fun save(
        frame: Frame,
        deliveredToNeighbors: Set<Int>,
        createdAt: Long,
    ) {
        synchronized(lock) {
            val key = Key(frame.nodeId, frame.seq)
            val existing = packets[key]
            if (existing == null) {
                packets[key] = StoredFrame(
                    frame = frame,
                    createdAt = createdAt,
                    deliveredToNeighbors = deliveredToNeighbors.toSet(),
                )
            } else {
                // Mirror RoomPacketStore: re-saving must not reset age/bytes or
                // erase delivery progress; it only unions newly delivered ids.
                val merged = existing.deliveredToNeighbors + deliveredToNeighbors
                if (merged != existing.deliveredToNeighbors) {
                    packets[key] = existing.copy(deliveredToNeighbors = merged)
                }
            }
        }
    }

    override suspend fun pendingFor(neighborId: Int, notBefore: Long): List<StoredFrame> =
        synchronized(lock) {
            packets.values
                .filter { it.createdAt >= notBefore && neighborId !in it.deliveredToNeighbors }
                .toList()
        }

    override suspend fun isDelivered(nodeId: Int, seq: Int, neighborId: Int): Boolean =
        synchronized(lock) {
            packets[Key(nodeId, seq)]?.deliveredToNeighbors?.contains(neighborId) == true
        }

    override suspend fun markDelivered(nodeId: Int, seq: Int, neighborId: Int) {
        synchronized(lock) {
            val key = Key(nodeId, seq)
            val existing = packets[key] ?: return@synchronized
            packets[key] = existing.copy(
                deliveredToNeighbors = existing.deliveredToNeighbors + neighborId,
            )
        }
    }

    override suspend fun deleteOlderThan(notBefore: Long) {
        synchronized(lock) {
            packets.values.removeAll { it.createdAt < notBefore }
        }
    }

    override suspend fun trimToSize(maxSize: Int) {
        synchronized(lock) {
            while (packets.size > maxSize) {
                val oldest = packets.minByOrNull { it.value.createdAt } ?: return@synchronized
                packets.remove(oldest.key)
            }
        }
    }

    /** Snapshot of everything held, oldest first. Test helper. */
    val all: List<StoredFrame>
        get() = synchronized(lock) { packets.values.sortedBy { it.createdAt } }
}
