package com.airmesh.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.airmesh.chat.ChatStatus
import kotlinx.coroutines.flow.Flow

/** DAO for [ChatMessageEntity] (TASK D2 part 2). */
@Dao
interface ChatMessageDao {

    /** Returns -1 when the unique (originNode, originSeq, kind) index rejects the insert. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ChatMessageEntity): Long

    /**
     * Upgrades an outgoing row only while its stored status ordinal is strictly lower;
     * returns the number of changed rows (0 = miss or refused downgrade). SQLite checks
     * WHERE against the old value, so `status < :status` means "not yet at this level".
     */
    @Query(
        "UPDATE chat_messages SET status = :status " +
            "WHERE conversationId = :conversationId AND direction = 'OUT' AND originSeq = :originSeq " +
            "AND status < :status",
    )
    suspend fun upgradeOutgoingStatus(conversationId: Int, originSeq: Int, status: ChatStatus): Int

    @Query(
        "UPDATE chat_messages SET isRead = 1 " +
            "WHERE conversationId = :conversationId AND direction = 'IN' AND isRead = 0",
    )
    suspend fun markRead(conversationId: Int)

    /** One conversation, oldest first (ties broken by row id). */
    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun messages(conversationId: Int): Flow<List<ChatMessageEntity>>

    /** All rows newest first; the store derives per-conversation summaries from this. */
    @Query("SELECT * FROM chat_messages ORDER BY createdAt DESC, id DESC")
    fun newestFirst(): Flow<List<ChatMessageEntity>>

    @Query("SELECT DISTINCT conversationId FROM chat_messages")
    suspend fun distinctConversationIds(): List<Int>

    /** Keeps only the [keep] newest rows of one conversation. */
    @Query(
        "DELETE FROM chat_messages WHERE conversationId = :conversationId AND id NOT IN (" +
            "SELECT id FROM chat_messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun pruneConversation(conversationId: Int, keep: Int)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: Int)
}
