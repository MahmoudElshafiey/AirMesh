package com.airmesh.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airmesh.chat.ChatDirection
import com.airmesh.chat.ChatKind
import com.airmesh.chat.ChatMessage
import com.airmesh.chat.ChatStatus
import com.airmesh.chat.ConversationSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TASK R1 part 1: the real Room queries behind per-conversation unread — counts, markRead,
 * the one-time OUT repair, and summaries re-emitting after a read update. In-memory database,
 * so nothing touches the app's real file.
 *
 * Run with a connected device or emulator: `./gradlew connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class ChatUnreadInstrumentedTest {

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

    private var now = 1_700_000_000L

    private fun message(
        conversationId: Int,
        direction: ChatDirection,
        originNode: Int,
        originSeq: Int,
        isRead: Boolean,
        kind: ChatKind = ChatKind.TEXT,
        text: String? = "m$originSeq",
    ): ChatMessage {
        now += 1_000
        return ChatMessage(
            conversationId = conversationId,
            direction = direction,
            kind = kind,
            text = text,
            originNode = originNode,
            originSeq = originSeq,
            createdAt = now,
            status = ChatStatus.SENT,
            isRead = isRead,
        )
    }

    /** Unread count of one conversation from a rollup snapshot (0 when it has no rows). */
    private fun unread(summaries: List<ConversationSummary>, conversationId: Int): Int =
        summaries.firstOrNull { it.conversationId == conversationId }?.unreadCount ?: 0

    @Test
    fun perConversationUnreadCountsUseTheRealQueries() = runBlocking {
        store.insert(message(3, ChatDirection.IN, originNode = 1, originSeq = 1, isRead = false))
        store.insert(message(3, ChatDirection.OUT, originNode = 9, originSeq = 2, isRead = true))
        store.insert(message(4, ChatDirection.IN, originNode = 2, originSeq = 3, isRead = false))
        store.insert(message(4, ChatDirection.IN, originNode = 2, originSeq = 4, isRead = false))

        val summaries = store.summaries.first()
        assertEquals(1, unread(summaries, 3))
        assertEquals(2, unread(summaries, 4))
    }

    @Test
    fun markReadUpdateClearsOnlyThatConversation() = runBlocking {
        store.insert(message(3, ChatDirection.IN, originNode = 1, originSeq = 1, isRead = false))
        store.insert(message(4, ChatDirection.IN, originNode = 2, originSeq = 2, isRead = false))

        store.markRead(3)

        val summaries = store.summaries.first()
        assertEquals(0, unread(summaries, 3))
        assertEquals(1, unread(summaries, 4))
    }

    @Test
    fun repairMarksLegacyOutgoingRowsReadAndLeavesIncomingAlone() = runBlocking {
        // A legacy OUT row written before the always-read rule.
        store.insert(message(5, ChatDirection.OUT, originNode = 9, originSeq = 1, isRead = false))
        store.insert(message(5, ChatDirection.IN, originNode = 1, originSeq = 2, isRead = false))

        assertEquals(1, store.repairOutgoingRead())

        val rows = store.messages(5).first()
        assertTrue(rows.single { it.direction == ChatDirection.OUT }.isRead)
        assertFalse(rows.single { it.direction == ChatDirection.IN }.isRead)
        // Idempotent: a second run changes nothing.
        assertEquals(0, store.repairOutgoingRead())
    }

    @Test
    fun summariesFlowReEmitsAfterMarkRead() = runBlocking {
        store.insert(message(6, ChatDirection.IN, originNode = 1, originSeq = 1, isRead = false))

        // Collect on a background dispatcher; the list is thread-safe because the collector and
        // this test observe it from different threads.
        val emissions = CopyOnWriteArrayList<List<ConversationSummary>>()
        val collector = launch(Dispatchers.IO) { for (s in store.summaries) emissions += s }
        withTimeout(5_000) { while (emissions.isEmpty()) delay(10) }
        assertEquals(1, unread(emissions.last(), 6))

        store.markRead(6)

        // Room invalidates on UPDATE: the flow must re-emit with the conversation read.
        withTimeout(5_000) {
            while (emissions.none { unread(it, 6) == 0 }) delay(10)
        }
        collector.cancel()
    }
}
