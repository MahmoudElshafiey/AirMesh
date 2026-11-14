package com.airmesh.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.airmesh.chat.ChatDirection
import com.airmesh.chat.ChatKind
import com.airmesh.chat.ChatStatus

/** One chat row (TASK D2 part 2). */
@Entity(
    tableName = "chat_messages",
    indices = [
        // Dedup key: a frame is identified by its origin and sequence; kind is
        // included so the index matches the ChatStore contract exactly.
        Index(value = ["originNode", "originSeq", "kind"], unique = true),
        // Conversation queries (list, markRead, prune) all filter on this pair.
        Index(value = ["conversationId", "createdAt"]),
    ],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val conversationId: Int,
    val direction: ChatDirection,
    val kind: ChatKind,
    val text: String?,
    val latitudeE6: Int?,
    val longitudeE6: Int?,
    val accuracyMeters: Int?,
    val originNode: Int,
    val originSeq: Int,
    val createdAt: Long,
    val status: ChatStatus,
    val isRead: Boolean,
)
