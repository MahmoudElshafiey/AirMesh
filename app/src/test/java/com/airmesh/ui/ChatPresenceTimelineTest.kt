package com.airmesh.ui

import com.airmesh.chat.ChatDirection
import com.airmesh.chat.ChatKind
import com.airmesh.chat.ChatMessage
import com.airmesh.chat.ChatStatus
import com.airmesh.presence.PresenceEvent
import com.airmesh.presence.PresenceTransition
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for the presence system rows in the chat timeline (TASK U5 part 3).
 * A fixed UTC zone and a fixed base day keep "Today"/"Yesterday" deterministic.
 */
class ChatPresenceTimelineTest {
    private val zone = ZoneId.of("UTC")

    // 2026-01-01T00:00:00Z — the "today" of every test below.
    private val dayOneMs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()

    /** Epoch millis at [dayOffset] days after 2026-01-01, at the given hour/minute UTC. */
    private fun at(dayOffset: Int, hour: Int, minute: Int): Long =
        dayOneMs + dayOffset * 86_400_000L + hour * 3_600_000L + minute * 60_000L

    private var nextId = 1L

    private fun message(direction: ChatDirection, atMs: Long, originNode: Int = 2): ChatMessage {
        val id = nextId++
        return ChatMessage(
            id = id,
            conversationId = 2,
            direction = direction,
            kind = ChatKind.TEXT,
            text = "m$id",
            originNode = originNode,
            originSeq = id.toInt(),
            createdAt = atMs,
            status = if (direction == ChatDirection.OUT) ChatStatus.DELIVERED else ChatStatus.QUEUED,
            isRead = true,
        )
    }

    private fun event(transition: PresenceTransition, atMs: Long): PresenceEvent =
        PresenceEvent(nodeId = 2, transition = transition, atMs = atMs)

    /** Compact row description so assertions read like the rendered timeline. */
    private fun List<ChatTimelineRow>.kinds(): List<String> = map { row -> when (row) {
        is ChatTimelineRow.Day -> "day:${row.label}"
        is ChatTimelineRow.Message -> "msg:${row.message.id}:${if (row.newGroup) "new" else "same"}"
        is ChatTimelineRow.PresenceNote -> "presence:${row.event.transition}@${row.event.atMs}"
    } }

    @Test
    fun rowTextMatchesTheSpecExamples() {
        assertEquals(
            "Ali went out of range - 14:32",
            presenceRowText("Ali", event(PresenceTransition.WENT_OUT_OF_RANGE, at(0, 14, 32)), zone),
        )
        assertEquals(
            "Ali is back - 14:35",
            presenceRowText("Ali", event(PresenceTransition.BACK_ONLINE, at(0, 14, 35)), zone),
        )
    }

    @Test
    fun noEventsGivesExactlyTheMessageRows() {
        val nowMs = at(0, 18, 0)
        val messages = listOf(
            message(ChatDirection.IN, at(-1, 9, 0)), // Yesterday
            message(ChatDirection.OUT, at(0, 9, 0)), // Today, new group (direction change)
            message(ChatDirection.IN, at(0, 9, 1)),
            message(ChatDirection.IN, at(0, 9, 2)), // same sender within the window: one group
        )
        val expected = buildChatUiRows(messages, nowMs, zone).map { row -> when (row) {
            is ChatUiRow.Day -> ChatTimelineRow.Day(row.label)
            is ChatUiRow.Message -> ChatTimelineRow.Message(row.message, row.newGroup)
        } }
        assertEquals(expected, buildChatTimelineRows(messages, emptyList(), "Ali", nowMs, zone))
    }

    @Test
    fun eventBetweenMessagesLandsInBetween() {
        val m1 = message(ChatDirection.IN, at(0, 9, 0))
        // Five minutes apart: beyond the 2-minute group window, so m2 starts a new group.
        val m2 = message(ChatDirection.IN, at(0, 9, 5))
        val e = event(PresenceTransition.WENT_OUT_OF_RANGE, at(0, 9, 2))
        val rows = buildChatTimelineRows(listOf(m1, m2), listOf(e), "Ali", at(0, 18, 0), zone)
        assertEquals(
            listOf("day:Today", "msg:${m1.id}:new", "presence:${e.transition}@${e.atMs}", "msg:${m2.id}:new"),
            rows.kinds(),
        )
    }

    @Test
    fun eventsBeforeFirstAndAfterLastMessage() {
        val m1 = message(ChatDirection.IN, at(0, 10, 0))
        val eBefore = event(PresenceTransition.WENT_OUT_OF_RANGE, at(0, 8, 0))
        val eAfter = event(PresenceTransition.BACK_ONLINE, at(0, 12, 0))
        val rows = buildChatTimelineRows(listOf(m1), listOf(eBefore, eAfter), "Ali", at(0, 18, 0), zone)
        assertEquals(
            listOf(
                "day:Today",
                "presence:${eBefore.transition}@${eBefore.atMs}",
                "msg:${m1.id}:new",
                "presence:${eAfter.transition}@${eAfter.atMs}",
            ),
            rows.kinds(),
        )
    }

    @Test
    fun equalTimestampPutsTheEventRowFirst() {
        val m1 = message(ChatDirection.IN, at(0, 9, 0))
        val e = event(PresenceTransition.BACK_ONLINE, at(0, 9, 0)) // same second as the message
        val rows = buildChatTimelineRows(listOf(m1), listOf(e), "Ali", at(0, 18, 0), zone)
        assertEquals(
            listOf("day:Today", "presence:${e.transition}@${e.atMs}", "msg:${m1.id}:new"),
            rows.kinds(),
        )
    }

    @Test
    fun daySeparatorIsInsertedWhenAnEventCrossesDays() {
        val m1 = message(ChatDirection.IN, at(-1, 20, 0)) // Yesterday
        val e = event(PresenceTransition.WENT_OUT_OF_RANGE, at(0, 9, 0)) // Today
        val rows = buildChatTimelineRows(listOf(m1), listOf(e), "Ali", at(0, 18, 0), zone)
        assertEquals(
            listOf("day:Yesterday", "msg:${m1.id}:new", "day:Today", "presence:${e.transition}@${e.atMs}"),
            rows.kinds(),
        )
    }

    @Test
    fun aSystemRowDoesNotSplitAMessageGroup() {
        val m1 = message(ChatDirection.IN, at(0, 9, 0))
        val e = event(PresenceTransition.WENT_OUT_OF_RANGE, at(0, 9, 1))
        val m2 = message(ChatDirection.IN, at(0, 9, 2)) // same sender, one minute later
        val rows = buildChatTimelineRows(listOf(m1, m2), listOf(e), "Ali", at(0, 18, 0), zone)
        assertEquals(
            listOf("day:Today", "msg:${m1.id}:new", "presence:${e.transition}@${e.atMs}", "msg:${m2.id}:same"),
            rows.kinds(),
        )
    }
}
