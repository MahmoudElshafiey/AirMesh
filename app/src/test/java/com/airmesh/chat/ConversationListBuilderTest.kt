package com.airmesh.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for [ConversationListBuilder] ordering and rendering rules (TASK D2 part 1). */
class ConversationListBuilderTest {

    private val t0 = 1_700_000_000_000L

    private fun summary(
        conversationId: Int,
        lastMessageAt: Long,
        direction: ChatDirection = ChatDirection.IN,
        kind: ChatKind = ChatKind.TEXT,
        text: String? = "hi",
        unreadCount: Int = 0,
    ) = ConversationSummary(conversationId, lastMessageAt, direction, kind, text, unreadCount)

    private fun contact(nodeId: Int, name: String?, colorIndex: Int = 0) = Contact(nodeId, name, colorIndex)

    @Test
    fun meshRoomIsAlwaysFirstEvenWithNoHistory() {
        val items = ConversationListBuilder.build(emptyList(), emptyList(), emptyMap(), t0)

        assertEquals(1, items.size)
        val room = items.single()
        assertEquals(MESH_CONVERSATION_ID, room.conversationId)
        assertEquals("Everyone nearby", room.title)
        assertEquals("", room.preview)
        assertEquals(0L, room.timeMs)
        assertEquals(0, room.unreadCount)
    }

    @Test
    fun conversationsAreSortedByLastMessageNewestFirst() {
        val summaries = listOf(
            summary(5, t0 + 1_000),
            summary(7, t0 + 3_000),
            summary(9, t0 + 2_000),
        )

        val items = ConversationListBuilder.build(summaries, emptyList(), emptyMap(), t0)

        assertEquals(listOf(MESH_CONVERSATION_ID, 7, 9, 5), items.map { it.conversationId })
    }

    @Test
    fun liveContactsWithoutConversationsComeAfterConversationsOnlineFirstThenName() {
        val summaries = listOf(summary(20, t0 + 1_000))
        val contacts = listOf(contact(3, "Zoe"), contact(4, "Amy"), contact(5, null), contact(6, "Bob"))
        val presence = mapOf(
            3 to NodePresence(PresenceState.RECENT, false),
            4 to NodePresence(PresenceState.ONLINE, true),
            5 to NodePresence(PresenceState.RECENT, false), // unnamed: sorts as "User #5"
            6 to NodePresence(PresenceState.ONLINE, false),
        )

        val items = ConversationListBuilder.build(summaries, contacts, presence, t0)

        // ONLINE (Amy, Bob) before RECENT ("User #5", Zoe — U sorts before Z).
        assertEquals(listOf(MESH_CONVERSATION_ID, 20, 4, 6, 5, 3), items.map { it.conversationId })
    }

    @Test
    fun aContactWithAConversationAppearsOnlyOnce() {
        val summaries = listOf(summary(7, t0 + 1_000))
        val contacts = listOf(contact(7, "Zed", colorIndex = 2))
        val presence = mapOf(7 to NodePresence(PresenceState.ONLINE, true))

        val items = ConversationListBuilder.build(summaries, contacts, presence, t0)

        assertEquals(listOf(MESH_CONVERSATION_ID, 7), items.map { it.conversationId })
        assertEquals("Zed", items[1].title)
        assertEquals(2, items[1].colorIndex)
    }

    @Test
    fun unknownPresenceContactsWithoutConversationsAreOmitted() {
        val contacts = listOf(contact(8, "Ghost"), contact(9, null))
        // No presence entries at all: nothing is ONLINE or RECENT.
        val items = ConversationListBuilder.build(emptyList(), contacts, emptyMap(), t0)

        assertEquals(listOf(MESH_CONVERSATION_ID), items.map { it.conversationId })
    }

    @Test
    fun titlesInitialsAndColorsFallBackSensibly() {
        val summaries = listOf(
            summary(123, t0 + 1_000), // no contact row at all
            summary(456, t0 + 2_000), // contact with a name
        )
        val contacts = listOf(contact(456, "Alice Smith", colorIndex = 3))

        val items = ConversationListBuilder.build(summaries, contacts, emptyMap(), t0)

        assertEquals(listOf(MESH_CONVERSATION_ID, 456, 123), items.map { it.conversationId })
        assertEquals("Alice Smith", items[1].title)
        assertEquals("AS", items[1].initials)
        assertEquals(3, items[1].colorIndex)
        assertEquals("User #123", items[2].title)
        assertEquals("U", items[2].initials)
        assertEquals(0, items[2].colorIndex)
        assertEquals(PresenceState.UNKNOWN, items[2].presence.state)
    }

    @Test
    fun previewsFormatOutgoingLocationAndIncomingText() {
        val summaries = listOf(
            summary(1, t0 + 1_000, direction = ChatDirection.OUT, text = "on my way"),
            summary(2, t0 + 2_000, kind = ChatKind.LOCATION, text = null),
            summary(3, t0 + 3_000, direction = ChatDirection.IN, text = "raw incoming"),
        )

        val items = ConversationListBuilder.build(summaries, emptyList(), emptyMap(), t0)

        // Newest first: 3, then 2, then 1 after the mesh room.
        assertEquals("raw incoming", items[1].preview)
        assertEquals("Location", items[2].preview)
        assertEquals("You: on my way", items[3].preview)
    }

    @Test
    fun unreadCountAndTimeAreCarriedThrough() {
        val summaries = listOf(summary(4, t0 + 5_000, unreadCount = 7))

        val item = ConversationListBuilder.build(summaries, emptyList(), emptyMap(), t0)[1]

        assertEquals(t0 + 5_000, item.timeMs)
        assertEquals(7, item.unreadCount)
    }
}
