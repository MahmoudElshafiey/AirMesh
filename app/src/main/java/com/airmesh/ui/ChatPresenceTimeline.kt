package com.airmesh.ui

import com.airmesh.chat.ChatMessage
import com.airmesh.presence.PresenceEvent
import com.airmesh.presence.PresenceTransition
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure presentation logic for the presence system rows in a chat timeline (TASK U5 part 3).
 * No Android imports: everything here is JVM-testable with a fixed [nowMs] and an injectable
 * [ZoneId], so the Compose layer only renders what these functions decide.
 */

/** How long the transient "Back online" chip stays visible after a confirmed BACK_ONLINE. */
const val BACK_ONLINE_CHIP_MS = 3_000L

private val PRESENCE_ROW_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "14:32" for presence system rows; [zone] is injectable so tests are deterministic. */
fun formatPresenceRowTime(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(timeMs).atZone(zone).format(PRESENCE_ROW_TIME_FORMATTER)

/**
 * Text of the centered system row for one confirmed presence transition:
 * "Ali went out of range - 14:32" / "Ali is back - 14:35". The time is when the new state
 * STARTED holding (PresenceEvent.atMs), not when it was confirmed after the debounce.
 */
fun presenceRowText(displayName: String, event: PresenceEvent, zone: ZoneId = ZoneId.systemDefault()): String =
    when (event.transition) {
        PresenceTransition.WENT_OUT_OF_RANGE -> "$displayName went out of range - ${formatPresenceRowTime(event.atMs, zone)}"
        PresenceTransition.BACK_ONLINE -> "$displayName is back - ${formatPresenceRowTime(event.atMs, zone)}"
    }

/** One rendered row of the chat timeline: a day separator, a message, or a presence system row. */
sealed interface ChatTimelineRow {
    data class Day(val label: String) : ChatTimelineRow
    data class Message(val message: ChatMessage, val newGroup: Boolean) : ChatTimelineRow
    /** Centered "X went out of range - 14:32" row; not a message and never persisted. */
    data class PresenceNote(val event: PresenceEvent, val text: String) : ChatTimelineRow
}

/**
 * Merges the peer's confirmed presence transitions into the chat timeline by timestamp
 * (TASK U5 part 3). A day separator is inserted whenever the calendar day changes between two
 * consecutive time items (message or system row); message grouping still only looks at messages,
 * so a system row in between does not split a visual group. On equal timestamps the system row
 * comes first ("Ali is back - 14:32" reads naturally right before their next message). With no
 * events this returns exactly what [buildChatUiRows] would (Day + Message rows, same grouping).
 */
fun buildChatTimelineRows(
    messages: List<ChatMessage>,
    presenceEvents: List<PresenceEvent>,
    displayName: String,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ChatTimelineRow> {
    val events = presenceEvents.sortedBy { it.atMs }
    var eventIndex = 0
    val rows = mutableListOf<ChatTimelineRow>()
    var previousMessage: ChatMessage? = null
    var lastItemAtMs: Long? = null

    fun emitDayIfNeeded(atMs: Long) {
        // Copy to a local first: the var is mutated in this capturing closure, so no smart cast.
        val last = lastItemAtMs
        if (last == null || !sameDay(last, atMs, zone)) {
            rows += ChatTimelineRow.Day(daySeparatorLabel(atMs, nowMs, zone))
        }
        lastItemAtMs = atMs
    }

    fun emitPresenceNote(event: PresenceEvent) {
        emitDayIfNeeded(event.atMs)
        rows += ChatTimelineRow.PresenceNote(event, presenceRowText(displayName, event, zone))
    }

    for (message in messages) {
        // Every transition that happened before this message goes above it.
        while (eventIndex < events.size && events[eventIndex].atMs <= message.createdAt) {
            emitPresenceNote(events[eventIndex])
            eventIndex++
        }
        emitDayIfNeeded(message.createdAt)
        rows += ChatTimelineRow.Message(message, startsNewGroup(previousMessage, message))
        previousMessage = message
    }
    // Transitions after the last message (or with no messages at all).
    while (eventIndex < events.size) {
        emitPresenceNote(events[eventIndex])
        eventIndex++
    }
    return rows
}

/** Where the "N unread" divider goes: [position] is the index in the row list to insert it before. */
data class UnreadDividerInfo(val position: Int?, val count: Int)

/**
 * Position and count for the "N unread messages" divider (TASK R1 part 2). [unreadMessageIds] is
 * the set of IN rows that were UNREAD WHEN THIS CHAT WAS OPENED — captured once, so it neither
 * grows with arrivals while open nor shrinks when markRead flips them. The divider goes right
 * before the first such row still visible in [rows]; [count] is how many of the captured ids are
 * visible (older ones may have been pruned away). Position is null when none is visible.
 */
fun unreadDivider(rows: List<ChatTimelineRow>, unreadMessageIds: Set<Long>): UnreadDividerInfo {
    if (unreadMessageIds.isEmpty()) return UnreadDividerInfo(null, 0)
    var firstUnreadIndex = -1
    var visible = 0
    rows.forEachIndexed { index, row ->
        if (row is ChatTimelineRow.Message && row.message.id in unreadMessageIds) {
            visible++
            if (firstUnreadIndex < 0) firstUnreadIndex = index
        }
    }
    return UnreadDividerInfo(if (visible > 0) firstUnreadIndex else null, visible)
}

private fun sameDay(aMs: Long, bMs: Long, zone: ZoneId): Boolean =
    LocalDate.ofInstant(Instant.ofEpochMilli(aMs), zone) == LocalDate.ofInstant(Instant.ofEpochMilli(bMs), zone)
