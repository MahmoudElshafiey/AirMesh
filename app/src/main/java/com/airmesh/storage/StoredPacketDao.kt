package com.airmesh.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Database access for the store-and-forward queue. */
@Dao
interface StoredPacketDao {

    /** Inserts or replaces the packet with the same `(nodeId, seq)`. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(packet: StoredPacket)

    /** Non-expired packets, oldest first. */
    @Query("SELECT * FROM stored_packets WHERE createdAt >= :notBefore ORDER BY createdAt ASC")
    suspend fun recent(notBefore: Long): List<StoredPacket>

    @Query("SELECT * FROM stored_packets WHERE nodeId = :nodeId AND seq = :seq")
    suspend fun find(nodeId: Int, seq: Int): StoredPacket?

    @Query("DELETE FROM stored_packets WHERE createdAt < :notBefore")
    suspend fun deleteOlderThan(notBefore: Long): Int

    @Query("SELECT COUNT(*) FROM stored_packets")
    suspend fun count(): Int

    @Query("SELECT * FROM stored_packets ORDER BY createdAt ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<StoredPacket>

    @Query("DELETE FROM stored_packets WHERE nodeId = :nodeId AND seq = :seq")
    suspend fun delete(nodeId: Int, seq: Int)
}
