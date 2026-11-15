package com.airmesh.chat

import androidx.compose.runtime.Immutable

/**
 * Conversation ids (TASK D2): [MESH_CONVERSATION_ID] is the broadcast room that
 * everyone shares; any other id is a peer node id, i.e. a one-to-one direct
 * conversation with that node.
 */
const val MESH_CONVERSATION_ID = 0

/** Who authored a chat row relative to this device. */
enum class ChatDirection { IN, OUT }

/** The user content kinds that can live in a conversation. */
enum class ChatKind { TEXT, LOCATION }

/**
 * Delivery state of an outgoing message: QUEUED until the first neighbor accepts
 * it (store-and-forward), SENT once handed off, DELIVERED when the recipient's
 * receipt arrives. Incoming messages are always stored as SENT — they reached us.
 */
enum class ChatStatus { QUEUED, SENT, DELIVERED }

/**
 * One persisted chat row. [id] is assigned by the store; callers pass 0 and the
 * store replaces it on insert.
 */
@Immutable // all fields are value types (TASK U4 part 4)
data class ChatMessage(
    val id: Long = 0L,
    /** 0 = mesh room, otherwise the peer node id of a direct conversation. */
    val conversationId: Int,
    val direction: ChatDirection,
    val kind: ChatKind,
    /** TEXT rows only. */
    val text: String? = null,
    /** LOCATION rows only (E6 integer degrees). */
    val latitudeE6: Int? = null,
    val longitudeE6: Int? = null,
    val accuracyMeters: Int? = null,
    /** Node id that authored the original frame. */
    val originNode: Int,
    /** Frame sequence of the authoring frame; part of the dedup key. */
    val originSeq: Int,
    val createdAt: Long,
    val status: ChatStatus,
    val isRead: Boolean,
)

/** A peer we can address: learned from PROFILE frames or any frame activity. */
@Immutable // all fields are value types (TASK U4 part 4)
data class Contact(
    val nodeId: Int,
    /** null = the node never announced a display name. */
    val displayName: String?,
    /** Avatar color 0..7; 0 also means "unknown". */
    val colorIndex: Int,
)

/** Coarse liveness of a node, derived from the last frame activity seen from it. */
enum class PresenceState { ONLINE, RECENT, UNKNOWN }

@Immutable // all fields are value types (TASK U4 part 4)
data class NodePresence(
    val state: PresenceState,
    /** True when the node is in our live Wi-Fi Direct neighbor set right now. */
    val isDirectNeighbor: Boolean,
)

/** Per-conversation rollup backing the conversation list. */
@Immutable // all fields are value types (TASK U4 part 4)
data class ConversationSummary(
    val conversationId: Int,
    val lastMessageAt: Long,
    val lastDirection: ChatDirection,
    val lastKind: ChatKind,
    /** Text of the last message; null for location rows. */
    val lastText: String?,
    val unreadCount: Int,
)

/** One rendered row of the conversation list (TASK D2 part 1). */
@Immutable // all fields are value types (TASK U4 part 4)
data class ConversationItem(
    val conversationId: Int,
    val title: String,
    val initials: String,
    val colorIndex: Int,
    val preview: String,
    /** Last message time; 0 when the contact has no messages yet. */
    val timeMs: Long,
    val unreadCount: Int,
    val presence: NodePresence,
)
