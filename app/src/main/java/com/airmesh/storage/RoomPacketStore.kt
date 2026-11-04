package com.airmesh.storage

import androidx.room.withTransaction
import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.routing.PacketStore
import com.airmesh.routing.StoredFrame

/**
 * Room-backed [PacketStore].
 *
 * Rows are keyed by frame identity. Frames are decoded lazily on read; a row
 * whose bytes no longer decode is deleted instead of being retried forever.
 *
 * All methods suspend and use Room's own dispatcher, so callers never block the
 * main thread. Mutations that read and then write a row run in a transaction.
 */
class RoomPacketStore(
    private val database: AirMeshDatabase,
) : PacketStore {

    private val dao = database.storedPacketDao()

    override suspend fun save(
        frame: Frame,
        deliveredToNeighbors: Set<Int>,
        createdAt: Long,
    ) {
        dao.upsert(
            StoredPacket(
                nodeId = frame.nodeId,
                seq = frame.seq,
                frameBytes = FrameCodec.encode(frame),
                createdAt = createdAt,
                deliveredToNeighbors = deliveredToNeighbors.mapTo(mutableSetOf()) { it.toString() },
            ),
        )
    }

    override suspend fun pendingFor(neighborId: Int, notBefore: Long): List<StoredFrame> {
        val neighbor = neighborId.toString()
        return dao.recent(notBefore).mapNotNull { packet ->
            if (neighbor in packet.deliveredToNeighbors) {
                null
            } else {
                decode(packet) ?: run {
                    // Corrupt/unreadable row: drop it rather than scan it forever.
                    dao.delete(packet.nodeId, packet.seq)
                    null
                }
            }
        }
    }

    override suspend fun isDelivered(nodeId: Int, seq: Int, neighborId: Int): Boolean =
        dao.find(nodeId, seq)?.deliveredToNeighbors?.contains(neighborId.toString()) == true

    override suspend fun markDelivered(nodeId: Int, seq: Int, neighborId: Int) {
        database.withTransaction {
            val packet = dao.find(nodeId, seq) ?: return@withTransaction
            val neighbor = neighborId.toString()
            if (neighbor in packet.deliveredToNeighbors) return@withTransaction
            dao.upsert(
                packet.copy(
                    deliveredToNeighbors = packet.deliveredToNeighbors + neighbor,
                ),
            )
        }
    }

    override suspend fun deleteOlderThan(notBefore: Long) {
        dao.deleteOlderThan(notBefore)
    }

    override suspend fun trimToSize(maxSize: Int) {
        require(maxSize >= 0) { "maxSize must not be negative: $maxSize" }
        database.withTransaction {
            val excess = dao.count() - maxSize
            if (excess <= 0) return@withTransaction
            // Oldest first: those are the least useful after a long outage.
            dao.oldest(excess).forEach { dao.delete(it.nodeId, it.seq) }
        }
    }

    private fun decode(packet: StoredPacket): StoredFrame? {
        val frame = FrameCodec.decode(packet.frameBytes).getOrNull() ?: return null
        return StoredFrame(
            frame = frame,
            createdAt = packet.createdAt,
            deliveredToNeighbors = packet.deliveredToNeighbors.mapTo(mutableSetOf()) { it.toInt() },
        )
    }
}
