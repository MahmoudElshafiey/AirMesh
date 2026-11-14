package com.airmesh.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airmesh.chat.ChatDirection
import com.airmesh.chat.ChatKind
import com.airmesh.chat.ChatMessage
import com.airmesh.chat.ChatStatus
import com.airmesh.chat.Contact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented checks for the Room chat layer (TASK D2 part 2): insert and read,
 * unique index, status update, unread counts, prune, clear.
 *
 * Run with a connected device or emulator: `./gradlew connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class RoomChatStoreTest {

    private lateinit var database: ChatDatabase
    private lateinit var store: RoomChatStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomChatStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private var now = 1_700_000_000_000L

    private fun message(
        conversationId: Int,
        direction: ChatDirection,
        kind: ChatKind = ChatKind.TEXT,
        text: String? = "t",
        originNode: Int,
        originSeq: Int,
        status: ChatStatus = ChatStatus.SENT,
        isRead: Boolean = true,
    ): ChatMessage {
        now += 1_000 // every row gets a strictly newer timestamp
        return ChatMessage(
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
    }

    private fun rows(conversationId: Int): List<ChatMessage> = runBlocking { store.messages(conversationId).first() }

    @Test
    fun insertAndReadBackRoundTripsAllFields() = runBlocking {
        assertTrue(store.insert(message(0, ChatDirection.IN, originNode = 5, originSeq = 1)))
        assertTrue(
            store.insert(
                message(
                    0,
                    ChatDirection.OUT,
                    kind = ChatKind.LOCATION,
                    text = null,
                    originNode = 42,
                    originSeq = 2,
                ).copy(latitudeE6 = 1_000_000, longitudeE6 = -2_000_000, accuracyMeters = 12),
            ),
        )

        val all = rows(0)
        assertEquals(2, all.size)
        assertTrue(all.all { it.id > 0L })
        // Oldest first.
        assertEquals(listOf(ChatDirection.IN, ChatDirection.OUT), all.map { it.direction })
        val location = all[1]
        assertEquals(ChatKind.LOCATION, location.kind)
        assertNull(location.text)
        assertEquals(1_000_000, location.latitudeE6)
        assertEquals(-2_000_000, location.longitudeE6)
        assertEquals(12, location.accuracyMeters)
    }

    @Test
    fun uniqueIndexRejectsDuplicateOriginSeqKind() = runBlocking {
        assertTrue(store.insert(message(0, ChatDirection.IN, originNode = 5, originSeq = 3)))
        // Same key with different content: still a duplicate.
        assertFalse(store.insert(message(0, ChatDirection.OUT, text = "other", originNode = 5, originSeq = 3)))
        assertEquals(1, rows(0).size)

        // A different kind for the same (origin, seq) is a distinct row.
        assertTrue(store.insert(message(0, ChatDirection.IN, kind = ChatKind.LOCATION, text = null, originNode = 5, originSeq = 3)))
        assertEquals(2, rows(0).size)
    }

    @Test
    fun statusUpdateUpgradesButNeverDowngrades() = runBlocking {
        store.insert(message(3, ChatDirection.OUT, originNode = 42, originSeq = 9, status = ChatStatus.QUEUED))

        assertTrue(store.updateOutgoingStatus(3, 9, ChatStatus.SENT))
        assertEquals(ChatStatus.SENT, rows(3).single().status)

        // Downgrade refused...
        assertFalse(store.updateOutgoingStatus(3, 9, ChatStatus.QUEUED))
        // ...same status is a no-op...
        assertFalse(store.updateOutgoingStatus(3, 9, ChatStatus.SENT))
        // ...a jump to DELIVERED works...
        assertTrue(store.updateOutgoingStatus(3, 9, ChatStatus.DELIVERED))
        assertEquals(ChatStatus.DELIVERED, rows(3).single().status)
        // ...and unknown (conversation, seq) pairs miss.
        assertFalse(store.updateOutgoingStatus(4, 9, ChatStatus.DELIVERED))

        // Incoming rows are never touched by status updates.
        store.insert(message(5, ChatDirection.IN, originNode = 7, originSeq = 1, status = ChatStatus.SENT))
        assertFalse(store.updateOutgoingStatus(5, 1, ChatStatus.DELIVERED))
    }

    @Test
    fun unreadCountsAndMarkRead() = runBlocking {
        store.insert(message(1, ChatDirection.IN, originNode = 5, originSeq = 1, isRead = false))
        store.insert(message(1, ChatDirection.IN, originNode = 6, originSeq = 2, isRead = false))
        store.insert(message(2, ChatDirection.IN, originNode = 7, originSeq = 3, isRead = false))

        var summaries = store.summaries.first()
        assertEquals(2, summaries.single { it.conversationId == 1 }.unreadCount)
        assertEquals(1, summaries.single { it.conversationId == 2 }.unreadCount)

        store.markRead(1)

        summaries = store.summaries.first()
        assertEquals(0, summaries.single { it.conversationId == 1 }.unreadCount)
        // The other conversation is untouched.
        assertEquals(1, summaries.single { it.conversationId == 2 }.unreadCount)
        assertTrue(rows(1).all { it.isRead })
        assertFalse(rows(2).single().isRead)
    }

    @Test
    fun pruneKeepsNewestPerConversation() = runBlocking {
        repeat(3) { i ->
            store.insert(message(0, ChatDirection.IN, originNode = 5, originSeq = i + 1))
            store.insert(message(7, ChatDirection.IN, originNode = 6, originSeq = i + 1))
        }

        store.prune(maxPerConversation = 2)

        assertEquals(listOf(2, 3), rows(0).map { it.originSeq })
        assertEquals(listOf(2, 3), rows(7).map { it.originSeq })
    }

    @Test
    fun clearConversationRemovesOnlyThatConversation() = runBlocking {
        store.insert(message(1, ChatDirection.IN, originNode = 5, originSeq = 1))
        store.insert(message(2, ChatDirection.IN, originNode = 6, originSeq = 2))

        store.clearConversation(1)

        assertTrue(rows(1).isEmpty())
        assertEquals(1, rows(2).size)
    }

    @Test
    fun contactUpsertKeepsKnownValues() = runBlocking {
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
}
