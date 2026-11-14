package com.airmesh.chat

import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for chat history (TASK D2). Pure Kotlin so the same
 * interface is implemented by Room on device and by [InMemoryChatStore] in JVM
 * tests. All methods are safe to call from any thread.
 */
interface ChatStore {

    /**
     * Inserts one row; the store assigns its id. Returns false (and inserts
     * nothing) when a row with the same unique key (originNode, originSeq, kind)
     * already exists — that is how frame re-deliveries are ignored.
     */
    fun insert(message: ChatMessage): Boolean

    /**
     * Sets [status] on the OUT message in [conversationId] with [originSeq], but
     * never downgrades an existing status (QUEUED < SENT < DELIVERED). Returns
     * true when a row actually changed.
     */
    fun updateOutgoingStatus(conversationId: Int, originSeq: Int, status: ChatStatus): Boolean

    /** Marks every unread IN message of [conversationId] as read. */
    fun markRead(conversationId: Int)

    /** One rollup per conversation that has at least one row. */
    val summaries: Flow<List<ConversationSummary>>

    /** All rows of one conversation, oldest first. */
    fun messages(conversationId: Int): Flow<List<ChatMessage>>

    /** Deletes the oldest rows beyond [maxPerConversation] in every conversation. */
    fun prune(maxPerConversation: Int = DEFAULT_RETENTION)

    /** Deletes every row of one conversation (user action). */
    fun clearConversation(conversationId: Int)

    /**
     * Inserts or refreshes a contact. A null name or color 0 never erases an
     * already-known value — activity-driven upserts carry only the node id.
     */
    fun upsertContact(nodeId: Int, displayName: String?, colorIndex: Int)

    /** One contact, or null when the node was never seen. */
    fun contact(nodeId: Int): Contact?

    /** Every known contact, by ascending node id. */
    val contacts: Flow<List<Contact>>

    companion object {
        /** Retention cap per conversation (TASK D2). */
        const val DEFAULT_RETENTION = 500
    }
}
