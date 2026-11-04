package com.airmesh.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One queued frame for store-and-forward.
 *
 * [id] is an auto-generated, monotonically increasing primary key. It is not
 * part of the protocol identity; it exists so replay and eviction order can be
 * deterministic when several frames share the same [createdAt] millisecond.
 * `(nodeId, seq)` is still the unique frame identity, enforced by a unique
 * index.
 *
 * The encoded frame is stored as bytes: a Room schema does not need to mirror
 * every protocol header field, and the codec remains the single source of
 * truth for the wire format.
 *
 * [deliveredToNeighbors] holds node ids as decimal strings because Room stores
 * this column as text through [NeighborSetConverter].
 */
@Entity(
    tableName = "stored_packets",
    indices = [
        Index(value = ["nodeId", "seq"], unique = true),
        Index("createdAt"),
    ],
)
data class StoredPacket(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val nodeId: Int,
    val seq: Int,
    val frameBytes: ByteArray,
    val createdAt: Long,
    val deliveredToNeighbors: Set<String> = emptySet(),
)
