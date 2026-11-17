package com.airmesh.ui

import com.airmesh.chat.ConversationSummary
import com.airmesh.chat.NodePresence
import com.airmesh.chat.PresenceState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure text helpers for the conversation list (TASK U1 part 1). No Android imports,
 * so every rule is unit-testable on the JVM. Time formatting takes an injectable
 * [ZoneId] and explicit timestamps so tests are deterministic in any timezone.
 */

private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val dayMonthFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val fullDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

/**
 * WhatsApp-style list timestamp: "12:41" for today, "Yesterday" for the previous
 * day, a short date ("Nov 3") within this year, and a full date otherwise. Returns
 * an empty string when there is no time to show ([timestampMs] <= 0).
 */
fun formatListTime(timestampMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (timestampMs <= 0L) return ""
    val then = Instant.ofEpochMilli(timestampMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    return when {
        then.toLocalDate() == now.toLocalDate() -> then.format(clockFormatter)
        then.toLocalDate() == now.toLocalDate().minusDays(1) -> "Yesterday"
        then.year == now.year -> then.format(dayMonthFormatter)
        else -> then.format(fullDateFormatter)
    }
}

/**
 * A node's presence line: "Online" when live, otherwise "Last seen <span>" when a
 * last-seen time is known ([lastSeenMs] > 0), or the coarser "Last seen recently"
 * for RECENT nodes without one. UNKNOWN with no timestamp yields "" (nothing to show).
 */
fun presenceText(presence: NodePresence, nowMs: Long, lastSeenMs: Long = 0L): String {
    return when (presence.state) {
        PresenceState.ONLINE -> "Online"
        else -> if (lastSeenMs > 0L && nowMs >= lastSeenMs) {
            "Last seen ${relativeSpan(nowMs - lastSeenMs)}"
        } else if (presence.state == PresenceState.RECENT) {
            "Last seen recently"
        } else {
            ""
        }
    }
}

/** Human span for a non-negative age: minutes, hours or days. */
private fun relativeSpan(agoMs: Long): String = when {
    agoMs < 60_000L -> "just now"
    agoMs < 3_600_000L -> "${agoMs / 60_000L} min ago"
    agoMs < 86_400_000L -> "${agoMs / 3_600_000L} h ago"
    else -> "${agoMs / 86_400_000L} d ago"
}

/**
 * Display formatting for a conversation preview: collapse runs of whitespace (including
 * newlines) to single spaces, trim the ends, and truncate with an ellipsis past
 * [maxChars] so long messages never overflow the row.
 */
fun conversationPreviewText(raw: String, maxChars: Int = 120): String {
    val collapsed = raw.replace(Regex("\\s+"), " ").trim()
    if (collapsed.length <= maxChars) return collapsed
    return collapsed.substring(0, maxChars - 1).trimEnd() + "…"
}

/** Unread badge label: hidden at zero, the count up to 99, then capped at "99+". */
fun unreadBadgeText(count: Int): String = when {
    count <= 0 -> ""
    count > 99 -> "99+"
    else -> count.toString()
}

/**
 * Bottom-bar Chats tab badge (TASK R1 part 1): the NUMBER OF CONVERSATIONS that have unread
 * messages — never a message total, so one chat with five unreads still shows "1".
 */
fun chatsTabBadgeCount(summaries: List<ConversationSummary>): Int = summaries.count { it.unreadCount > 0 }
