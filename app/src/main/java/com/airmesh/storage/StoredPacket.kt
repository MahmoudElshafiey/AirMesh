package com.airmesh.storage

import androidx.room.Entity
import androidx.room.Index

/**
 * One queued frame for store-and-forward.
 *
 * `(nodeId, seq)` is the frame identity used by the routing layer, so it is the
 * composite primary key. The encoded frame is stored as bytes: a Room schema
 * does not need to mirror every protocol header field, and the codec remains
 * the single source of truth for the wire format.
 *
 * [deliveredToNeighbors] holds node ids as decimal strings because Room stores
 * this column as text through [NeighborSetConverter].
 */
@Entity(
    tableName = "stored_packets",
    primaryKeys = ["nodeId", "seq"],
    indices = [Index("createdAt")],
)
data class StoredPacket(
    val nodeId: Int,
    val seq: Int,
    val frameBytes: ByteArray,
    val createdAt: Long,
    val deliveredToNeighbors: Set<String> = emptySet(),
)
