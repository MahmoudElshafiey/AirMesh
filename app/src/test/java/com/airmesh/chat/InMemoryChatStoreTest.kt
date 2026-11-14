package com.airmesh.chat

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [InMemoryChatStore] semantics (TASK D2 part 1). */
class InMemoryChatStoreTest {

    private val store = InMemoryChatStore()
    private var now = 1_700_000_000_000L

    private fun msg(
        conversationId: Int,
        direction: ChatDirection,
        kind: ChatKind = ChatKind.TEXT,
        text: String? = "t",
        originNode: Int,
        originSeq: Int,
        status: ChatStatus = ChatStatus.SENT,
        isRead: Boolean = true,
    ) = ChatMessage(
        conversationId = conversationId,
        direction = direction,
        kind = kind,
        text = text,
        originNode = originNode,
        originSeq = originSeq,
        createdAt = now,
        status = status,
        isRead = isRead,
    )

    private fun rows(conversationId: Int): List<ChatMessage> = runBlocking { store.messages(conversationId).first() }

    @Test
    fun insertAssignsIdsAndRejectsUniqueKeyDuplicates() = runBlocking {
        assertTrue(store.insert(msg(0, ChatDirection.IN, originNode = 5, originSeq = 1)))
        // Same key with different content: still a duplicate.
        assertFalse(store.insert(msg(0, ChatDirection.OUT, text = "other", originNode = 5, originSeq = 1)))

        val rows = rows(0)
        assertEquals(1, rows.size)
        assertTrue(rows.single().id > 0L)

        // A different kind for the same (origin, seq) is a distinct row.
        assertTrue(store.insert(msg(0, ChatDirection.IN, kind = ChatKind.LOCATION, text = null, originNode = 5, originSeq = 1)))
        assertEquals(2, rows(0).size)
    }

    @Test
    fun updateOutgoingStatusNeverDowngradesAndMissesReturnFalse() = runBlocking {
        store.insert(msg(3, ChatDirection.OUT, originNode = 42, originSeq = 9, status = ChatStatus.QUEUED))

        assertTrue(store.updateOutgoingStatus(3, 9, ChatStatus.SENT))
        assertEquals(ChatStatus.SENT, rows(3).single().status)

        // Downgrade refused...
        assertFalse(store.updateOutgoingStatus(3, 9, ChatStatus.QUEUED))
        assertEquals(ChatStatus.SENT, rows(3).single().status)
        // ...same status is a no-op...
        assertFalse(store.updateOutgoingStatus(3, 9, ChatStatus.SENT))
        // ...and unknown (conversation, seq) pairs miss.
        assertFalse(store.updateOutgoingStatus(4, 9, ChatStatus.DELIVERED))
    }

    @Test
    fun markReadOnlyTouchesItsConversation() = runBlocking {
        store.insert(msg(1, ChatDirection.IN, originNode = 5, originSeq = 1, isRead = false))
        store.insert(msg(2, ChatDirection.IN, originNode = 6, originSeq = 2, isRead = false))

        store.markRead(1)

        assertTrue(rows(1).single().isRead)
        assertFalse(rows(2).single().isRead)
    }

    @Test
    fun pruneKeepsTheNewestRowsPerConversation() = runBlocking {
        repeat(3) { i ->
            now += 1_000
            store.insert(msg(0, ChatDirection.IN, originNode = 5, originSeq = i + 1))
            store.insert(msg(7, ChatDirection.IN, originNode = 6, originSeq = i + 1))
        }

        store.prune(maxPerConversation = 2)

        assertEquals(listOf(2, 3), rows(0).map { it.originSeq })
        assertEquals(listOf(2, 3), rows(7).map { it.originSeq })
    }

    @Test
    fun clearConversationRemovesOnlyThatConversation() = runBlocking {
        store.insert(msg(1, ChatDirection.IN, originNode = 5, originSeq = 1))
        store.insert(msg(2, ChatDirection.IN, originNode = 6, originSeq = 2))

        store.clearConversation(1)

        assertTrue(rows(1).isEmpty())
        assertEquals(1, rows(2).size)
    }

    @Test
    fun contactUpsertMergesWithoutErasingKnownValues() = runBlocking {
        assertNull(store.contact(5))

        // Activity-driven upsert: id only.
        store.upsertContact(5, null, 0)
        assertEquals(Contact(5, null, 0), store.contact(5))

        // A PROFILE announcement fills in name and color...
        store.upsertContact(5, "Zed", 3)
        assertEquals(Contact(5, "Zed", 3), store.contact(5))

        // ...and later activity must not erase them.
        store.upsertContact(5, null, 0)
        assertEquals(Contact(5, "Zed", 3), store.contact(5))

        val all = store.contacts.first()
        assertEquals(listOf(5), all.map { it.nodeId })
    }

    @Test
    fun summariesCarryTheLastRowAndUnreadCount() = runBlocking {
        now += 1_000
        store.insert(msg(4, ChatDirection.IN, originNode = 9, originSeq = 1, isRead = false))
        now += 1_000
        store.insert(msg(4, ChatDirection.OUT, text = "reply", originNode = 42, originSeq = 2))

        val summary = store.summaries.first().single()
        assertEquals(4, summary.conversationId)
        assertEquals(now, summary.lastMessageAt)
        assertEquals(ChatDirection.OUT, summary.lastDirection)
        assertEquals("reply", summary.lastText)
        assertEquals(1, summary.unreadCount)
    }
}
