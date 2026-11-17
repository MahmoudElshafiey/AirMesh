package com.airmesh.chat

import com.airmesh.ui.chatsTabBadgeCount
import com.airmesh.ui.unreadBadgeText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK R1 part 1: truthful per-conversation unread semantics on [InMemoryChatStore].
 * Unread of a conversation = its IN rows with isRead=false; outgoing never counts; the mesh
 * room (id 0) follows exactly the same rules as direct chats.
 */
class UnreadSemanticsTest {

    private var now = 1_700_000_000_000L
    private val store = InMemoryChatStore()
    private val repo = ChatRepository(localNodeId = 42, store = store, clock = { now })

    /** Unread count of one conversation from the store's rollups (0 when it has no rows). */
    private fun unread(conversationId: Int): Int = runBlocking {
        store.summaries.first().firstOrNull { it.conversationId == conversationId }?.unreadCount ?: 0
    }

    @Test
    fun unreadCountsArePerConversation() = runBlocking {
        repo.onIncomingDirect(fromNode = 5, text = "a", seq = 1)
        now += 1_000
        repo.onIncomingDirect(fromNode = 6, text = "b", seq = 1)
        now += 1_000
        repo.onIncomingDirect(fromNode = 6, text = "c", seq = 2)

        assertEquals(1, unread(5))
        assertEquals(2, unread(6))
    }

    @Test
    fun outgoingNeverCountsAsUnread() = runBlocking {
        repo.recordOutgoingBroadcast("hi all", seq = 1, handed = true)
        now += 1_000
        repo.recordOutgoingDirect(destNode = 5, text = "psst", seq = 2, handed = false)

        assertEquals(0, unread(MESH_CONVERSATION_ID))
        assertEquals(0, unread(5))
    }

    @Test
    fun markReadResetsOnlyThatConversation() = runBlocking {
        repo.onIncomingDirect(fromNode = 5, text = "a", seq = 1)
        now += 1_000
        repo.onIncomingDirect(fromNode = 6, text = "b", seq = 1)

        repo.markRead(5)

        assertEquals(0, unread(5))
        assertEquals(1, unread(6))
    }

    @Test
    fun newMessageAfterMarkReadIncrementsOnlyItsOwnConversation() = runBlocking {
        repo.onIncomingDirect(fromNode = 5, text = "a", seq = 1)
        now += 1_000
        repo.onIncomingDirect(fromNode = 6, text = "b", seq = 1)
        repo.markRead(5)
        repo.markRead(6)

        now += 1_000
        repo.onIncomingDirect(fromNode = 5, text = "again", seq = 2)

        assertEquals(1, unread(5))
        assertEquals(0, unread(6))
    }

    @Test
    fun roomBehavesLikeDirectChats() = runBlocking {
        // A broadcast arrives: the room row is IN and unread until the room is opened.
        repo.onIncomingBroadcast(originNode = 7, text = "hello", originSeq = 1)
        assertEquals(1, unread(MESH_CONVERSATION_ID))
        assertFalse(store.messages(MESH_CONVERSATION_ID).first().single().isRead)

        // Opening the room marks it read — same call as for a direct chat.
        repo.markRead(MESH_CONVERSATION_ID)
        assertEquals(0, unread(MESH_CONVERSATION_ID))
        assertTrue(store.messages(MESH_CONVERSATION_ID).first().single().isRead)

        // A later broadcast counts again: the room is not special-cased any more.
        now += 1_000
        repo.onIncomingBroadcast(originNode = 8, text = "again", originSeq = 2)
        assertEquals(1, unread(MESH_CONVERSATION_ID))

        // Location shares follow the room's rules too.
        now += 1_000
        repo.onIncomingLocation(originNode = 9, latitudeE6 = 1, longitudeE6 = 2, accuracyMeters = 3, originSeq = 3)
        assertEquals(2, unread(MESH_CONVERSATION_ID))
    }

    @Test
    fun rowBadgeCapsAt99Plus() {
        // The cap is the pure helper every badge renders through.
        assertEquals("", unreadBadgeText(0))
        assertEquals("1", unreadBadgeText(1))
        assertEquals("99", unreadBadgeText(99))
        assertEquals("99+", unreadBadgeText(100))
    }

    @Test
    fun chatsTabBadgeCountsConversationsNotMessages() = runBlocking {
        repo.onIncomingDirect(fromNode = 5, text = "a", seq = 1)
        now += 1_000
        repo.onIncomingDirect(fromNode = 5, text = "b", seq = 2)
        now += 1_000
        repo.onIncomingDirect(fromNode = 6, text = "c", seq = 1)

        // Two conversations have unread (three messages total): the tab badge is 2.
        assertEquals(2, chatsTabBadgeCount(store.summaries.first()))

        repo.markRead(5)
        repo.markRead(6)
        assertEquals(0, chatsTabBadgeCount(store.summaries.first()))
    }

    @Test
    fun repairMarksLegacyOutgoingRowsReadAndLeavesIncomingAlone() = runBlocking {
        // A legacy OUT row written before the always-read rule (inserted directly with isRead=false).
        store.insert(
            ChatMessage(
                conversationId = 5,
                direction = ChatDirection.OUT,
                kind = ChatKind.TEXT,
                text = "old send",
                originNode = 42,
                originSeq = 100,
                createdAt = now,
                status = ChatStatus.SENT,
                isRead = false,
            ),
        )
        repo.onIncomingDirect(fromNode = 5, text = "unread", seq = 1)

        assertEquals(1, repo.repairOutgoingRead())

        val rows = store.messages(5).first()
        assertTrue(rows.single { it.direction == ChatDirection.OUT }.isRead)
        assertFalse(rows.single { it.direction == ChatDirection.IN }.isRead)
        // Idempotent: a second run changes nothing.
        assertEquals(0, repo.repairOutgoingRead())
    }
}
