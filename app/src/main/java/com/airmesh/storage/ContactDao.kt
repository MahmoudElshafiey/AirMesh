package com.airmesh.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** DAO for [ContactEntity] (TASK D2 part 2). */
@Dao
interface ContactDao {

    /** Returns -1 when the unique nodeId index rejects the insert. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ContactEntity): Long

    @Query("UPDATE chat_contacts SET displayName = :displayName, colorIndex = :colorIndex WHERE nodeId = :nodeId")
    suspend fun update(nodeId: Int, displayName: String?, colorIndex: Int)

    @Query("SELECT * FROM chat_contacts WHERE nodeId = :nodeId")
    suspend fun find(nodeId: Int): ContactEntity?

    @Query("SELECT * FROM chat_contacts ORDER BY nodeId ASC")
    fun all(): Flow<List<ContactEntity>>
}
