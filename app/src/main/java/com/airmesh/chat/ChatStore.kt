package com.airmesh.chat

import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for chat history (TASK D2). Pure Kotlin so the same
 * interface is implemented by Room on device and by [InMemoryChatStore] in JVM
 * tests. One-shot operations are suspend because Room requires them; flows stay
 * plain. All methods are safe to call from any thread.
 */
interface ChatStore {

    /**
     * Inserts one row; the store assigns its id. Returns false (and inserts
     * nothing) when a row with the same unique key (originNode, originSeq, kind)
     * already exists — that is how frame re-deliveries are ignored.
     */
    suspend fun insert(message: ChatMessage): Boolean

    /**
     * Sets [status] on the OUT message in [conversationId] with [originSeq], but
     * never downgrades an existing status (QUEUED < SENT < DELIVERED). Returns
     * true when a row actually changed.
     */
    suspend fun updateOutgoingStatus(conversationId: Int, originSeq: Int, status: ChatStatus): Boolean

    /** Marks every unread IN message of [conversationId] as read. */
    suspend fun markRead(conversationId: Int)

    /**
     * One-time startup repair (TASK R1 part 1): marks every OUT row read and returns how many
     * rows changed. Outgoing rows are always inserted read by [ChatRepository]; this only fixes
     * legacy rows written before that rule, so it is safe to run on every start.
     */
    suspend fun repairOutgoingRead(): Int

    /** One rollup per conversation that has at least one row. */
    val summaries: Flow<List<ConversationSummary>>

    /** All rows of one conversation, oldest first. */
    fun messages(conversationId: Int): Flow<List<ChatMessage>>

    /** Deletes the oldest rows beyond [maxPerConversation] in every conversation. */
    suspend fun prune(maxPerConversation: Int = DEFAULT_RETENTION)

    /** Deletes every row of one conversation (user action). */
    suspend fun clearConversation(conversationId: Int)

    /**
     * Inserts or refreshes a contact. A null name or color 0 never erases an
     * already-known value — activity-driven upserts carry only the node id.
     */
    suspend fun upsertContact(nodeId: Int, displayName: String?, colorIndex: Int)

    /** One contact, or null when the node was never seen. */
    suspend fun contact(nodeId: Int): Contact?

    /** Every known contact, by ascending node id. */
    val contacts: Flow<List<Contact>>

    companion object {
        /** Retention cap per conversation (TASK D2). */
        const val DEFAULT_RETENTION = 500
    }
}
