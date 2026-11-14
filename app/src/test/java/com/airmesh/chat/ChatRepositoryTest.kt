package com.airmesh.chat

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [ChatRepository] on top of [InMemoryChatStore] (TASK D2 part 1). */
class ChatRepositoryTest {

    private var now = 1_700_000_000_000L
    private val store = InMemoryChatStore()
    private val repo = ChatRepository(localNodeId = 42, store = store, clock = { now })

    private fun rows(conversationId: Int): List<ChatMessage> =
        runBlocking { store.messages(conversationId).first() }

    @Test
    fun incomingBroadcastLandsInTheMeshRoomReadAndSent() {
        repo.onIncomingBroadcast(originNode = 7, text = "hello mesh", originSeq = 3)

        val row = rows(MESH_CONVERSATION_ID).single()
        assertEquals(ChatDirection.IN, row.direction)
        assertEquals(ChatKind.TEXT, row.kind)
        assertEquals("hello mesh", row.text)
        assertEquals(7, row.originNode)
        assertEquals(3, row.originSeq)
        assertEquals(now, row.createdAt)
        assertEquals(ChatStatus.SENT, row.status)
        assertTrue(row.isRead)
    }

    @Test
    fun duplicateIncomingFrameIsIgnoredByTheUniqueKey() {
        repo.onIncomingBroadcast(7, "first", 3)
        now += 1_000
        // Same (originNode, originSeq, kind): a re-delivered frame adds no row.
        repo.onIncomingBroadcast(7, "second copy", 3)

        val rows = rows(MESH_CONVERSATION_ID)
        assertEquals(1, rows.size)
        assertEquals("first", rows.single().text)
    }

    @Test
    fun incomingDirectStartsAnUnreadConversationAndMarkReadClearsIt() {
        repo.onIncomingDirect(fromNode = 9, text = "psst", seq = 5)

        val row = rows(9).single()
        assertEquals(ChatDirection.IN, row.direction)
        assertFalse(row.isRead)
        assertEquals(1, runBlocking { store.summaries.first().single().unreadCount })

        repo.markRead(9)

        assertTrue(rows(9).single().isRead)
        assertEquals(0, runBlocking { store.summaries.first().single().unreadCount })
    }

    @Test
    fun outgoingStatusComesFromTheHandoffAndUpgradesOnReplay() {
        repo.recordOutgoingBroadcast("queued text", seq = 1, handed = false)
        assertEquals(ChatStatus.QUEUED, rows(MESH_CONVERSATION_ID).single().status)

        // The messages flow re-emits after a replay hands the frame to a neighbor:
        // same unique key, so this must upgrade the row instead of duplicating it.
        repo.recordOutgoingBroadcast("queued text", seq = 1, handed = true)

        val rows = rows(MESH_CONVERSATION_ID)
        assertEquals(1, rows.size)
        assertEquals(ChatStatus.SENT, rows.single().status)
    }

    @Test
    fun receiptMarksDeliveredAndNeverDowngrades() {
        repo.recordOutgoingDirect(destNode = 3, text = "dm", seq = 2, handed = true)
        repo.onReceipt(fromNode = 3, ackedSeq = 2)
        assertEquals(ChatStatus.DELIVERED, rows(3).single().status)

        // A second receipt is a no-op...
        repo.onReceipt(3, 2)
        assertEquals(ChatStatus.DELIVERED, rows(3).single().status)
        // ...and the store refuses to downgrade an already-delivered row.
        assertFalse(store.updateOutgoingStatus(3, 2, ChatStatus.SENT))

        // A receipt for a still-QUEUED send (store-and-forward) jumps to DELIVERED.
        repo.recordOutgoingDirect(4, "later", seq = 6, handed = false)
        repo.onReceipt(4, 6)
        assertEquals(ChatStatus.DELIVERED, rows(4).single().status)
    }

    @Test
    fun outgoingLocationIsRecordedWithItsFields() {
        repo.recordOutgoingLocation(latitudeE6 = 1_000_000, longitudeE6 = -2_000_000, accuracyMeters = 12, seq = 8, handed = true)

        val row = rows(MESH_CONVERSATION_ID).single()
        assertEquals(ChatKind.LOCATION, row.kind)
        assertEquals(1_000_000, row.latitudeE6)
        assertEquals(-2_000_000, row.longitudeE6)
        assertEquals(12, row.accuracyMeters)
        assertNull(row.text)
        assertEquals(ChatDirection.OUT, row.direction)
        assertEquals("OUT rows are authored by us", 42, row.originNode)
    }

    @Test
    fun retentionKeepsOnlyTheNewestFiveHundredPerConversation() {
        repeat(502) { i -> repo.onIncomingBroadcast(originNode = 7, text = "m$i", originSeq = i + 1) }

        val meshRows = rows(MESH_CONVERSATION_ID)
        assertEquals(ChatRepository.RETENTION_PER_CONVERSATION, meshRows.size)
        // The oldest two (seq 1 and 2) are gone; the newest remain.
        assertFalse(meshRows.any { it.originSeq <= 2 })
        assertTrue(meshRows.all { it.originSeq >= 3 })

        // Another conversation is pruned independently of the mesh room.
        repeat(501) { i -> repo.onIncomingDirect(fromNode = 9, text = "d$i", seq = i + 1) }
        assertEquals(ChatRepository.RETENTION_PER_CONVERSATION, rows(9).size)
        assertEquals(ChatRepository.RETENTION_PER_CONVERSATION, rows(MESH_CONVERSATION_ID).size)
    }

    @Test
    fun markReadDoesNotTouchOtherConversations() {
        repo.onIncomingDirect(5, "a", 1)
        repo.onIncomingDirect(6, "b", 2)

        repo.markRead(5)

        assertTrue(rows(5).single().isRead)
        assertFalse(rows(6).single().isRead)
    }
}
