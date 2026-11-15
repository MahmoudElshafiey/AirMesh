package com.airmesh.chat

import com.airmesh.presence.PresenceState as LivePresenceState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the truthful-presence conversation list (TASK U5 part 2). */
class LiveConversationListBuilderTest {

    private val now = 1_000_000L

    private fun contact(id: Int, name: String? = null) = Contact(nodeId = id, displayName = name, colorIndex = id % 8)

    private fun summary(conv: Int, atMs: Long, text: String = "hi") =
        ConversationSummary(conv, atMs, ChatDirection.IN, ChatKind.TEXT, text, 0)

    @Test
    fun theRoomIsAlwaysFirst() {
        val items = LiveConversationListBuilder.build(
            summaries = listOf(summary(7, now - 1_000L)),
            contacts = listOf(contact(7)),
            livePresence = mapOf(7 to LivePresenceState.ONLINE_DIRECT),
            nowMs = now,
        )
        assertEquals(listOf(MESH_CONVERSATION_ID, 7), items.map { it.conversationId })
    }

    @Test
    fun conversationsAreSortedByLastMessageNewestFirst() {
        val items = LiveConversationListBuilder.build(
            summaries = listOf(summary(7, now - 5_000L), summary(8, now - 1_000L)),
            contacts = emptyList(),
            livePresence = emptyMap(),
            nowMs = now,
        )
        assertEquals(listOf(MESH_CONVERSATION_ID, 8, 7), items.map { it.conversationId })
    }

    @Test
    fun thePeopleSectionShowsOnlineCheckingAndRecentlySeenContacts() {
        val presence = mapOf(
            1 to LivePresenceState.ONLINE_DIRECT,
            2 to LivePresenceState.ONLINE_RELAYED,
            3 to LivePresenceState.CHECKING,
            4 to LivePresenceState.LastSeen(now - 5 * 60_000L), // seen 5 min ago: inside the window
        )
        val items = LiveConversationListBuilder.build(
            summaries = emptyList(),
            contacts = listOf(contact(1), contact(2), contact(3), contact(4)),
            livePresence = presence,
            nowMs = now,
        )
        assertEquals(listOf(MESH_CONVERSATION_ID, 1, 2, 3, 4), items.map { it.conversationId })
    }

    @Test
    fun thePeopleSectionHidesOutOfRangeNotConnectedAndStaleContacts() {
        val presence = mapOf(
            5 to LivePresenceState.OUT_OF_RANGE,
            6 to LivePresenceState.NOT_CONNECTED,
            7 to LivePresenceState.UNKNOWN,
            8 to LivePresenceState.LastSeen(now - 11 * 60_000L), // seen 11 min ago: outside the window
        )
        val items = LiveConversationListBuilder.build(
            summaries = emptyList(),
            contacts = listOf(contact(5), contact(6), contact(7), contact(8)),
            livePresence = presence,
            nowMs = now,
        )
        assertEquals(listOf(MESH_CONVERSATION_ID), items.map { it.conversationId })
    }

    @Test
    fun aContactSeenExactlyTenMinutesAgoIsStillInThePeopleSection() {
        // The 10-minute window is inclusive on the boundary.
        val presence = mapOf(9 to LivePresenceState.LastSeen(now - LiveConversationListBuilder.PEOPLE_SEEN_WINDOW_MS))
        val items = LiveConversationListBuilder.build(
            summaries = emptyList(),
            contacts = listOf(contact(9)),
            livePresence = presence,
            nowMs = now,
        )
        assertEquals(listOf(MESH_CONVERSATION_ID, 9), items.map { it.conversationId })
    }

    @Test
    fun contactsWithAConversationNeverAppearInThePeopleSection() {
        // Online but already chatted: the conversation row is enough; no duplicate people row.
        val presence = mapOf(5 to LivePresenceState.ONLINE_DIRECT)
        val items = LiveConversationListBuilder.build(
            summaries = listOf(summary(5, now - 1_000L)),
            contacts = listOf(contact(5)),
            livePresence = presence,
            nowMs = now,
        )
        assertEquals(listOf(MESH_CONVERSATION_ID, 5), items.map { it.conversationId })
    }

    @Test
    fun onlineContactsSortBeforeCheckingAndSeenOnes() {
        val presence = mapOf(
            1 to LivePresenceState.ONLINE_DIRECT,
            3 to LivePresenceState.CHECKING,
            4 to LivePresenceState.LastSeen(now - 5 * 60_000L),
        )
        val items = LiveConversationListBuilder.build(
            summaries = emptyList(),
            contacts = listOf(contact(4), contact(3), contact(1)), // deliberately unsorted input
            livePresence = presence,
            nowMs = now,
        )
        assertEquals(listOf(MESH_CONVERSATION_ID, 1, 3, 4), items.map { it.conversationId })
    }

    @Test
    fun theRoomRowCarriesUnknownStateAndContactsKeepTheirPresence() {
        val presence = mapOf(7 to LivePresenceState.ONLINE_RELAYED)
        val items = LiveConversationListBuilder.build(
            summaries = listOf(summary(7, now - 1_000L)),
            contacts = listOf(contact(7)),
            livePresence = presence,
            nowMs = now,
        )
        assertEquals(LivePresenceState.UNKNOWN, items[0].liveState) // the room is not a node
        assertEquals(LivePresenceState.ONLINE_RELAYED, items[1].liveState)
    }
}
