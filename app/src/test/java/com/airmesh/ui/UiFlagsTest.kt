package com.airmesh.ui

import com.airmesh.chat.ChatDirection
import com.airmesh.chat.ChatKind
import com.airmesh.chat.ChatMessage
import com.airmesh.chat.ChatStatus
import com.airmesh.chat.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK S3: with the flag off, no unread UI model is produced or shown (pure helpers). */
class UiFlagsTest {

    private fun summary(id: Int, unreadCount: Int) = ConversationSummary(
        conversationId = id,
        lastMessageAt = 1_000L,
        lastDirection = ChatDirection.IN,
        lastKind = ChatKind.TEXT,
        lastText = "hi",
        unreadCount = unreadCount,
    )

    private fun message(id: Long, isRead: Boolean) = ChatMessage(
        id = id,
        conversationId = 7,
        direction = ChatDirection.IN,
        kind = ChatKind.TEXT,
        text = "m$id",
        originNode = 7,
        originSeq = id.toInt(),
        createdAt = 1_000L + id,
        status = ChatStatus.SENT,
        isRead = isRead,
    )

    @Test
    fun rowBadgeIsNotShownWhenFlagOff() {
        assertFalse("badge hidden with flag off", unreadBadgeVisible(false, 5))
        assertTrue("badge shown with flag on and unreads", unreadBadgeVisible(true, 3))
        assertFalse("no badge without unreads even when the flag is on", unreadBadgeVisible(true, 0))
    }

    @Test
    fun chatsTabBadgeIsNotShownWhenFlagOff() {
        val summaries = listOf(summary(1, 4), summary(2, 0), summary(3, 1))
        assertFalse("tab badge hidden with flag off", chatsTabBadgeVisible(false, summaries))
        assertTrue("tab badge shown with flag on and unread conversations", chatsTabBadgeVisible(true, summaries))
        assertFalse("no tab badge when every conversation is read", chatsTabBadgeVisible(true, listOf(summary(1, 0))))
    }

    @Test
    fun unreadDividerModelIsNotProducedWhenFlagOff() {
        val rows = listOf(
            ChatTimelineRow.Message(message(1L, isRead = true), newGroup = true),
            ChatTimelineRow.Message(message(2L, isRead = false), newGroup = false),
        )
        assertNull("no divider model with flag off", unreadDividerModel(false, rows, setOf(2L)))
        assertEquals(
            "flag on reproduces the existing divider model",
            UnreadDividerInfo(position = 1, count = 1),
            unreadDividerModel(true, rows, setOf(2L)),
        )
    }

    @Test
    fun newMessagesChipIsNotShownWhenFlagOff() {
        assertFalse("chip hidden with flag off", newMessagesChipVisible(false, follow = false, unseenCount = 3))
        assertTrue("chip shown with flag on while reading history", newMessagesChipVisible(true, follow = false, unseenCount = 2))
        assertFalse("no chip when following the bottom", newMessagesChipVisible(true, follow = true, unseenCount = 2))
    }
}
