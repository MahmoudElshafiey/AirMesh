package com.airmesh.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Database access for the store-and-forward queue. */
@Dao
interface StoredPacketDao {

    /**
     * Inserts a new packet. A packet with the same `(nodeId, seq)` is ignored
     * so an existing row keeps its primary key, insertion order, createdAt, and
     * delivered-neighbor set.
     *
     * @return the new row id, or -1 when the unique index rejected the insert.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(packet: StoredPacket): Long

    /** Replaces only the delivered set; identity, bytes and age are untouched. */
    @Query("UPDATE stored_packets SET deliveredToNeighbors = :deliveredToNeighbors WHERE id = :id")
    suspend fun updateDelivered(id: Long, deliveredToNeighbors: Set<String>)

    /** Non-expired packets, oldest first; [id] breaks same-millisecond ties. */
    @Query(
        "SELECT * FROM stored_packets " +
            "WHERE createdAt >= :notBefore ORDER BY createdAt ASC, id ASC",
    )
    suspend fun recent(notBefore: Long): List<StoredPacket>

    @Query("SELECT * FROM stored_packets WHERE nodeId = :nodeId AND seq = :seq")
    suspend fun find(nodeId: Int, seq: Int): StoredPacket?

    @Query("DELETE FROM stored_packets WHERE createdAt < :notBefore")
    suspend fun deleteOlderThan(notBefore: Long): Int

    @Query("SELECT COUNT(*) FROM stored_packets")
    suspend fun count(): Int

    /** Oldest packets by time, then by insertion order for equal timestamps. */
    @Query("SELECT * FROM stored_packets ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<StoredPacket>

    @Query("DELETE FROM stored_packets WHERE id = :id")
    suspend fun deleteById(id: Long)
}
