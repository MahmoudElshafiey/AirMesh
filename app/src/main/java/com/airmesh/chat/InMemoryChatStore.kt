package com.airmesh.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [ChatStore] for JVM tests (TASK D2 part 1). Mirrors the Room
 * semantics exactly: unique key on (originNode, originSeq, kind), status updates
 * that never downgrade, per-conversation retention. Every mutation is guarded by
 * one lock and republished as an immutable snapshot.
 */
class InMemoryChatStore : ChatStore {

    private val lock = Any()
    private var rows: List<ChatMessage> = emptyList()
    private var contactsById: Map<Int, Contact> = emptyMap()
    private var nextId = 1L

    /** All rows as an immutable snapshot; every mutation republishes it. */
    private val allRows = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val contactList = MutableStateFlow<List<Contact>>(emptyList())

    override fun insert(message: ChatMessage): Boolean = synchronized(lock) {
        val duplicate = rows.any {
            it.originNode == message.originNode &&
                it.originSeq == message.originSeq &&
                it.kind == message.kind
        }
        if (duplicate) return@synchronized false
        rows = rows + message.copy(id = nextId++)
        allRows.value = rows
        true
    }

    override fun updateOutgoingStatus(conversationId: Int, originSeq: Int, status: ChatStatus): Boolean =
        synchronized(lock) {
            val index = rows.indexOfFirst {
                it.conversationId == conversationId &&
                    it.direction == ChatDirection.OUT &&
                    it.originSeq == originSeq
            }
            if (index < 0) return@synchronized false
            val current = rows[index]
            // Never downgrade: QUEUED < SENT < DELIVERED.
            if (status.ordinal <= current.status.ordinal) return@synchronized false
            val updated = rows.toMutableList()
            updated[index] = current.copy(status = status)
            rows = updated
            allRows.value = rows
            true
        }

    override fun markRead(conversationId: Int) {
        synchronized(lock) {
            if (rows.none { it.conversationId == conversationId && !it.isRead }) return@synchronized
            rows = rows.map { row ->
                if (row.conversationId == conversationId && !row.isRead) row.copy(isRead = true) else row
            }
            allRows.value = rows
        }
    }

    override val summaries: Flow<List<ConversationSummary>> = allRows.map { all ->
        all.groupByTo(LinkedHashMap(), ChatMessage::conversationId).map { (id, msgs) ->
            // Ties on createdAt break by row id: the later insert is "last".
            val last = msgs.maxWith(compareBy({ it.createdAt }, { it.id }))
            ConversationSummary(
                conversationId = id,
                lastMessageAt = last.createdAt,
                lastDirection = last.direction,
                lastKind = last.kind,
                lastText = last.text,
                unreadCount = msgs.count { it.direction == ChatDirection.IN && !it.isRead },
            )
        }.sortedBy { it.conversationId }
    }

    override fun messages(conversationId: Int): Flow<List<ChatMessage>> = allRows.map { all ->
        all.filter { it.conversationId == conversationId }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    override fun prune(maxPerConversation: Int) {
        synchronized(lock) {
            val kept = rows.groupByTo(LinkedHashMap(), ChatMessage::conversationId).values
                .flatMap { msgs ->
                    msgs.sortedWith(compareBy({ it.createdAt }, { it.id })).takeLast(maxPerConversation)
                }
            if (kept.size != rows.size) {
                rows = kept
                allRows.value = rows
            }
        }
    }

    override fun clearConversation(conversationId: Int) {
        synchronized(lock) {
            val kept = rows.filterNot { it.conversationId == conversationId }
            if (kept.size != rows.size) {
                rows = kept
                allRows.value = rows
            }
        }
    }

    override fun upsertContact(nodeId: Int, displayName: String?, colorIndex: Int) {
        synchronized(lock) {
            val existing = contactsById[nodeId]
            // A null name or a zero color never erases an already-known value.
            val merged = Contact(
                nodeId = nodeId,
                displayName = displayName ?: existing?.displayName,
                colorIndex = if (colorIndex != 0) colorIndex else existing?.colorIndex ?: 0,
            )
            contactsById += nodeId to merged
            contactList.value = contactsById.values.sortedBy { it.nodeId }
        }
    }

    override fun contact(nodeId: Int): Contact? = synchronized(lock) { contactsById[nodeId] }

    override val contacts: Flow<List<Contact>> = contactList
}
