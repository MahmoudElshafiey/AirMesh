package com.airmesh.storage

import android.content.Context
import androidx.room.withTransaction
import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.routing.PacketStore
import com.airmesh.routing.StoredFrame
import com.airmesh.routing.StoreAndForward

/**
 * Creates the only supported Room-backed store-and-forward queue.
 *
 * [RoomPacketStore] is private, so normal wiring can never hand the raw store
 * to `Router`: the returned [StoreAndForward] enforces max age, max size, and
 * neighbor replay. Call this factory with an [AirMeshDatabase] (for tests or
 * dependency injection) or with a [Context] to build the on-device database.
 */
fun createRoomBackedStore(
    database: AirMeshDatabase,
    send: suspend (neighborId: Int, frame: Frame) -> Boolean,
    clock: () -> Long = System::currentTimeMillis,
    maxAgeMs: Long = StoreAndForward.DEFAULT_MAX_AGE_MS,
    maxStoreSize: Int = StoreAndForward.DEFAULT_MAX_STORE_SIZE,
    log: (String) -> Unit = {},
): StoreAndForward = StoreAndForward(
    store = RoomPacketStore(database),
    send = send,
    clock = clock,
    maxAgeMs = maxAgeMs,
    maxStoreSize = maxStoreSize,
    log = log,
)

/** Convenience overload that builds the on-device database. */
fun createRoomBackedStore(
    context: Context,
    send: suspend (neighborId: Int, frame: Frame) -> Boolean,
    clock: () -> Long = System::currentTimeMillis,
    maxAgeMs: Long = StoreAndForward.DEFAULT_MAX_AGE_MS,
    maxStoreSize: Int = StoreAndForward.DEFAULT_MAX_STORE_SIZE,
    log: (String) -> Unit = {},
): StoreAndForward = createRoomBackedStore(
    database = AirMeshDatabase.build(context),
    send = send,
    clock = clock,
    maxAgeMs = maxAgeMs,
    maxStoreSize = maxStoreSize,
    log = log,
)

/**
 * Room-backed [PacketStore], private on purpose.
 *
 * The class deliberately has no public visibility: callers must use
 * [createRoomBackedStore], which wraps it in [StoreAndForward]. Passing a raw
 * store to `Router` would bypass expiry, size limiting, and neighbor replay.
 *
 * Frames are decoded lazily on read; a row whose bytes no longer decode is
 * deleted instead of being retried forever.
 *
 * All methods suspend and use Room's own dispatcher, so callers never block the
 * main thread. Mutations that read and then write a row run in a transaction.
 */
private class RoomPacketStore(
    private val database: AirMeshDatabase,
) : PacketStore {

    private val dao = database.storedPacketDao()

    /**
     * Inserts a new frame, or merges delivery progress into an existing row.
     *
     * The insert uses `IGNORE`, so an existing `(nodeId, seq)` row keeps its
     * primary key, insertion order, `createdAt`, and frame bytes. Only the
     * delivered set is unioned forward, and only when it actually grew. This
     * prevents a re-save (for example after dedup-cache eviction) from erasing
     * per-neighbor progress and causing a duplicate send.
     */
    override suspend fun save(
        frame: Frame,
        deliveredToNeighbors: Set<Int>,
        createdAt: Long,
    ) {
        val encoded = FrameCodec.encode(frame)
        val incoming = deliveredToNeighbors.mapTo(mutableSetOf()) { it.toString() }

        database.withTransaction {
            dao.insert(
                StoredPacket(
                    nodeId = frame.nodeId,
                    seq = frame.seq,
                    frameBytes = encoded,
                    createdAt = createdAt,
                    deliveredToNeighbors = incoming,
                ),
            )

            val existing = dao.find(frame.nodeId, frame.seq) ?: return@withTransaction
            val merged = existing.deliveredToNeighbors + incoming
            if (merged != existing.deliveredToNeighbors) {
                dao.updateDelivered(existing.id, merged)
            }
        }
    }

    override suspend fun pendingFor(neighborId: Int, notBefore: Long): List<StoredFrame> {
        val neighbor = neighborId.toString()
        return dao.recent(notBefore).mapNotNull { packet ->
            if (neighbor in packet.deliveredToNeighbors) {
                null
            } else {
                decode(packet) ?: run {
                    // Corrupt/unreadable row: drop it rather than scan it forever.
                    dao.deleteById(packet.id)
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
            dao.updateDelivered(
                id = packet.id,
                deliveredToNeighbors = packet.deliveredToNeighbors + neighbor,
            )
        }
    }

    override suspend fun deleteOlderThan(notBefore: Long) {
        dao.deleteOlderThan(notBefore)
    }

    override suspend fun count(): Int = dao.count()

    override suspend fun trimToSize(maxSize: Int) {
        require(maxSize >= 0) { "maxSize must not be negative: $maxSize" }
        database.withTransaction {
            val excess = dao.count() - maxSize
            if (excess <= 0) return@withTransaction
            // Oldest first; `id` breaks same-millisecond ties in insertion order.
            dao.oldest(excess).forEach { dao.deleteById(it.id) }
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
