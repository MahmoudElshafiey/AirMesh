package com.airmesh.storage

import com.airmesh.chat.ChatDirection
import com.airmesh.chat.ChatMessage
import com.airmesh.chat.ChatStatus
import com.airmesh.chat.ChatStore
import com.airmesh.chat.Contact
import com.airmesh.chat.ConversationSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [ChatStore] (TASK D2 part 2). The unique index on
 * (originNode, originSeq, kind) is the dedup gate: a re-delivered frame simply
 * fails to insert and callers upgrade the stored row instead.
 */
class RoomChatStore(private val database: ChatDatabase) : ChatStore {

    private val messageDao = database.chatMessageDao()
    private val contactDao = database.contactDao()

    override suspend fun insert(message: ChatMessage): Boolean {
        val id = messageDao.insert(
            ChatMessageEntity(
                // 0 lets Room's autoGenerate assign the real row id.
                id = 0L,
                conversationId = message.conversationId,
                direction = message.direction,
                kind = message.kind,
                text = message.text,
                latitudeE6 = message.latitudeE6,
                longitudeE6 = message.longitudeE6,
                accuracyMeters = message.accuracyMeters,
                originNode = message.originNode,
                originSeq = message.originSeq,
                createdAt = message.createdAt,
                status = message.status,
                isRead = message.isRead,
            ),
        )
        return id != -1L
    }

    override suspend fun updateOutgoingStatus(conversationId: Int, originSeq: Int, status: ChatStatus): Boolean {
        // The SQL only touches rows with a strictly lower ordinal: never downgrade.
        return messageDao.upgradeOutgoingStatus(conversationId, originSeq, status) > 0
    }

    override suspend fun markRead(conversationId: Int) {
        messageDao.markRead(conversationId)
    }

    /**
     * Derived from one newest-first query so every summary is built from a single
     * consistent snapshot; the table is capped by retention, so this stays cheap.
     */
    override val summaries: Flow<List<ConversationSummary>> = messageDao.newestFirst().map { rows ->
        // Rows arrive newest first, so the first row per conversation is its last message.
        rows.groupByTo(LinkedHashMap(), ChatMessageEntity::conversationId).values.map { msgs ->
            val last = msgs.first()
            ConversationSummary(
                conversationId = last.conversationId,
                lastMessageAt = last.createdAt,
                lastDirection = last.direction,
                lastKind = last.kind,
                lastText = last.text,
                unreadCount = msgs.count { it.direction == ChatDirection.IN && !it.isRead },
            )
        }.sortedBy { it.conversationId }
    }

    override fun messages(conversationId: Int): Flow<List<ChatMessage>> = messageDao.messages(conversationId).map { entities ->
        entities.map { it.toModel() }
    }

    override suspend fun prune(maxPerConversation: Int) {
        for (conversationId in messageDao.distinctConversationIds()) {
            messageDao.pruneConversation(conversationId, maxPerConversation)
        }
    }

    override suspend fun clearConversation(conversationId: Int) {
        messageDao.clearConversation(conversationId)
    }

    override suspend fun upsertContact(nodeId: Int, displayName: String?, colorIndex: Int) {
        val existing = contactDao.find(nodeId)
        if (existing == null) {
            contactDao.insert(ContactEntity(id = 0L, nodeId = nodeId, displayName = displayName, colorIndex = colorIndex))
        } else {
            // A null name or a zero color never erases an already-known value.
            val mergedName = displayName ?: existing.displayName
            val mergedColor = if (colorIndex != 0) colorIndex else existing.colorIndex
            contactDao.update(nodeId, mergedName, mergedColor)
        }
    }

    override suspend fun contact(nodeId: Int): Contact? = contactDao.find(nodeId)?.toModel()

    override val contacts: Flow<List<Contact>> = contactDao.all().map { entities ->
        entities.map { it.toModel() }
    }
}

private fun ChatMessageEntity.toModel(): ChatMessage = ChatMessage(
    id = id,
    conversationId = conversationId,
    direction = direction,
    kind = kind,
    text = text,
    latitudeE6 = latitudeE6,
    longitudeE6 = longitudeE6,
    accuracyMeters = accuracyMeters,
    originNode = originNode,
    originSeq = originSeq,
    createdAt = createdAt,
    status = status,
    isRead = isRead,
)

private fun ContactEntity.toModel(): Contact = Contact(
    nodeId = nodeId,
    displayName = displayName,
    colorIndex = colorIndex,
)
