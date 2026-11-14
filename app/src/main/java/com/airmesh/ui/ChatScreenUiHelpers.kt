package com.airmesh.ui

import com.airmesh.chat.ChatMessage
import com.airmesh.chat.ChatStatus
import com.airmesh.chat.Contact
import com.airmesh.chat.MESH_CONVERSATION_ID
import com.airmesh.chat.NodePresence
import com.airmesh.chat.PresenceState
import com.airmesh.profile.ProfileInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Pure presentation logic for the chat screen (TASK U2 part 1). No Android imports:
 * everything here is JVM-testable with a fixed [nowMs] and an injectable [ZoneId], so the
 * Compose layer only renders what these functions decide.
 */

/** How long consecutive messages from the same sender stay in one visual group (WhatsApp uses ~5 min; 2 min keeps groups tight on a mesh). */
const val CHAT_GROUP_WINDOW_MS = 120_000L

/** Outgoing direct message older than this without DELIVERED shows an exclamation instead of ticks. */
const val NOT_DELIVERED_AFTER_MS = 600_000L // 10 minutes

/** The glyph drawn next to an outgoing direct message's time. */
enum class DeliveryGlyph { CLOCK, ONE_TICK, TWO_TICKS, EXCLAMATION }

/**
 * Clock = QUEUED (still in the store-and-forward queue), one tick = SENT (handed to a
 * neighbor), two ticks = DELIVERED (receipt from the recipient). After 10 minutes without
 * delivery the ticks are replaced by an exclamation so the user can tap and resend.
 */
fun deliveryGlyph(
    status: ChatStatus,
    ageMs: Long,
    notDeliveredAfterMs: Long = NOT_DELIVERED_AFTER_MS,
): DeliveryGlyph = when {
    status == ChatStatus.DELIVERED -> DeliveryGlyph.TWO_TICKS
    ageMs > notDeliveredAfterMs -> DeliveryGlyph.EXCLAMATION
    status == ChatStatus.SENT -> DeliveryGlyph.ONE_TICK
    else -> DeliveryGlyph.CLOCK
}

/**
 * WhatsApp-style grouping: a message starts a new group when it is the first one, comes
 * from a different direction (mine vs. theirs), has a different author (matters in the
 * mesh room where many senders share one conversation), or arrives more than
 * [groupWindowMs] after the previous row.
 */
fun startsNewGroup(
    previous: ChatMessage?,
    current: ChatMessage,
    groupWindowMs: Long = CHAT_GROUP_WINDOW_MS,
): Boolean {
    if (previous == null) return true
    if (previous.direction != current.direction) return true
    if (previous.originNode != current.originNode) return true
    return current.createdAt - previous.createdAt > groupWindowMs
}

/** "Today", "Yesterday" or the localized date, computed in [zone] at [nowMs]. */
fun daySeparatorLabel(timestampMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = LocalDate.ofInstant(Instant.ofEpochMilli(timestampMs), zone)
    val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DAY_FORMATTER)
    }
}

private val DAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** True when a sticky date separator must be drawn above [current]. */
fun needsDaySeparator(previous: ChatMessage?, current: ChatMessage, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (previous == null) return true
    val prevDay = LocalDate.ofInstant(Instant.ofEpochMilli(previous.createdAt), zone)
    val curDay = LocalDate.ofInstant(Instant.ofEpochMilli(current.createdAt), zone)
    return prevDay != curDay
}

/** Banner shown above a direct conversation when the peer is not in range right now. */
const val OFFLINE_CONTACT_BANNER = "Not nearby right now. Your message will be delivered when you are in range."

/** Banner shown in any conversation while this phone has no mesh group at all. */
const val NOT_CONNECTED_BANNER = "Not connected to the mesh yet."

/**
 * The warning banner a conversation should show, or null for none:
 * - not connected to any group -> [NOT_CONNECTED_BANNER] (applies everywhere);
 * - direct chat with a peer that is not ONLINE right now -> [OFFLINE_CONTACT_BANNER].
 * Messages still queue in store-and-forward either way; the banner only explains why
 * delivery will be delayed.
 */
fun chatBannerText(conversationId: Int, peerPresence: NodePresence?, connected: Boolean): String? = when {
    !connected -> NOT_CONNECTED_BANNER
    conversationId != MESH_CONVERSATION_ID && peerPresence?.state != PresenceState.ONLINE -> OFFLINE_CONTACT_BANNER
    else -> null
}

/**
 * Display name of a message sender: the learned contact name when non-blank, otherwise
 * the mesh profile name, otherwise "User #id" (same fallback as the rest of the UI).
 */
fun senderNameFor(nodeId: Int, contact: Contact?, profiles: Map<Int, ProfileInfo>): String =
    contact?.displayName?.takeIf { it.isNotBlank() } ?: displayName(nodeId, profiles)

/**
 * Avatar color of a message sender: the contact's own pick when set (0 means "unknown"),
 * otherwise a deterministic color derived from the node id so unknown senders stay
 * distinguishable in the mesh room.
 */
fun senderColorIndexFor(nodeId: Int, contact: Contact?, profiles: Map<Int, ProfileInfo>): Int =
    (contact?.colorIndex ?: 0).takeIf { it != 0 } ?: avatarColorIndex(nodeId, profiles)

/** One rendered row of the chat list: a sticky date separator or a message. */
sealed interface ChatUiRow {
    data class Day(val label: String) : ChatUiRow
    data class Message(val message: ChatMessage, val newGroup: Boolean) : ChatUiRow
}

/**
 * Turns an oldest-first message list into render rows: inserts a [ChatUiRow.Day] whenever
 * the calendar day changes and flags each message with whether it starts a new visual group.
 */
fun buildChatUiRows(
    messages: List<ChatMessage>,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ChatUiRow> {
    val rows = mutableListOf<ChatUiRow>()
    var previous: ChatMessage? = null
    for (message in messages) {
        if (needsDaySeparator(previous, message, zone)) {
            rows += ChatUiRow.Day(daySeparatorLabel(message.createdAt, nowMs, zone))
        }
        rows += ChatUiRow.Message(message, startsNewGroup(previous, message))
        previous = message
    }
    return rows
}
