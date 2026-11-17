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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the "N unread messages" divider (TASK R1 part 2): its position is decided
 * from the row list and the set of message ids that were UNREAD WHEN THE CHAT WAS OPENED.
 */
class UnreadDividerTest {
    private val zone = ZoneId.of("UTC")

    private var nextId = 1L
    private var nowMs = 1_700_000_000_000L

    /** One IN message one minute after the previous; [isRead] mirrors what open-time capture saw. */
    private fun message(isRead: Boolean): ChatMessage {
        val id = nextId++
        nowMs += 60_000
        return ChatMessage(
            id = id,
            conversationId = 2,
            direction = ChatDirection.IN,
            kind = ChatKind.TEXT,
            text = "m$id",
            originNode = 2,
            originSeq = id.toInt(),
            createdAt = nowMs,
            status = ChatStatus.SENT,
            isRead = isRead,
        )
    }

    private fun rowsOf(vararg messages: ChatMessage): List<ChatTimelineRow> =
        buildChatTimelineRows(messages.toList(), emptyList(), "Peer", nowMs + 1_000L, zone)

    @Test
    fun noCapturedUnreadsMeansNoDivider() {
        val read = message(isRead = true)
        val info = unreadDivider(rowsOf(read), emptySet())
        assertEquals(null, info.position)
        assertEquals(0, info.count)
    }

    @Test
    fun dividerGoesBeforeTheFirstUnreadAndCountsAllVisibleOnes() {
        val read1 = message(isRead = true)
        val unread1 = message(isRead = false)
        val unread2 = message(isRead = false)
        val rows = rowsOf(read1, unread1, unread2)

        // The captured set is what the screen saw at open time: both unreads.
        val info = unreadDivider(rows, setOf(unread1.id, unread2.id))

        // Rows are [Day, read1, unread1, unread2]: the divider sits before index 2.
        assertEquals(2, info.position)
        // The position must point at a message row — the first unread one.
        val atPosition = rows[info.position!!]
        assertTrue("expected the divider to sit before unread1, got: $atPosition",
            atPosition is ChatTimelineRow.Message && atPosition.message.id == unread1.id)
        assertEquals(2, info.count)
    }

    @Test
    fun dividerAtTheTopWhenEverythingIsUnread() {
        val u1 = message(isRead = false)
        val u2 = message(isRead = false)
        val rows = rowsOf(u1, u2)

        // Rows are [Day, u1, u2]: right after the mandatory day separator.
        assertEquals(UnreadDividerInfo(1, 2), unreadDivider(rows, setOf(u1.id, u2.id)))
    }

    @Test
    fun positionZeroIsPossibleWithoutADaySeparator() {
        // A hand-built row list (no Day row) exercises the very first index.
        val m = message(isRead = false)
        val rows = listOf<ChatTimelineRow>(ChatTimelineRow.Message(m, newGroup = true))
        assertEquals(UnreadDividerInfo(0, 1), unreadDivider(rows, setOf(m.id)))
    }

    @Test
    fun prunedCapturedIdsDoNotCountAndMayRemoveTheDivider() {
        val gone = message(isRead = false) // captured at open time...
        val visible = message(isRead = false) // ...but only this one is still in the list.
        val rows = rowsOf(visible)

        // Rows are [Day, visible]: the divider sits before index 1 and counts only what is visible.
        assertEquals(UnreadDividerInfo(1, 1), unreadDivider(rows, setOf(gone.id, visible.id)))
        // If every captured row was pruned away, there is nothing to mark.
        assertEquals(UnreadDividerInfo(null, 0), unreadDivider(rows, setOf(gone.id)))
    }

    @Test
    fun daySeparatorsAndPresenceNotesDoNotShiftTheCount() {
        val dayOne = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()
        val m1 = ChatMessage(
            id = 1, conversationId = 2, direction = ChatDirection.IN, kind = ChatKind.TEXT,
            text = "old", originNode = 2, originSeq = 1, createdAt = dayOne,
            status = ChatStatus.SENT, isRead = true,
        )
        val m2 = ChatMessage(
            id = 2, conversationId = 2, direction = ChatDirection.IN, kind = ChatKind.TEXT,
            text = "new", originNode = 2, originSeq = 2, createdAt = dayOne + 90_000_000L,
            status = ChatStatus.SENT, isRead = false,
        )
        val event = PresenceEvent(nodeId = 2, transition = PresenceTransition.BACK_ONLINE, atMs = dayOne + 3_600_000L)

        // Rows: Day, m1, presence note, Day (next calendar day), m2.
        val rows = buildChatTimelineRows(listOf(m1, m2), listOf(event), "Peer", m2.createdAt, zone)
        assertEquals(5, rows.size)

        val info = unreadDivider(rows, setOf(m2.id))
        assertEquals(4, info.position)
        assertEquals(1, info.count)
    }
}
