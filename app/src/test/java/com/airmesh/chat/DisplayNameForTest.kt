package com.airmesh.chat

import com.airmesh.profile.ProfileInfo
import com.airmesh.presence.PresenceState as LivePresenceState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the display-name combine (TASK R2 part 3): a live profile always wins over
 * the stored contact, so a name that arrives late or changes replaces "User #id" (or an older
 * saved name) immediately. The fallback chain is profile → contact → "User #id".
 */
class DisplayNameForTest {

    private val now = 1_000_000L

    private fun contact(id: Int, name: String? = null) = Contact(nodeId = id, displayName = name, colorIndex = id % 8)

    private fun profile(id: Int, name: String) = ProfileInfo(name, id % 8, now)

    @Test
    fun aLateProfileReplacesTheFallbackName() {
        // Before the profile arrives there is no contact either: stable fallback.
        assertEquals("User #7", ConversationListBuilder.displayNameFor(7, null, emptyMap()))
        // The profile arrives late over the mesh and wins immediately.
        assertEquals("Alice", ConversationListBuilder.displayNameFor(7, null, mapOf(7 to profile(7, "Alice"))))
    }

    @Test
    fun aChangedProfileReplacesTheStoredContactName() {
        val stored = contact(8, "Old Name")
        // The saved name shows until the live profile arrives.
        assertEquals("Old Name", ConversationListBuilder.displayNameFor(8, stored, emptyMap()))
        // A changed name from the mesh wins over the older saved one.
        assertEquals("New Name", ConversationListBuilder.displayNameFor(8, stored, mapOf(8 to profile(8, "New Name"))))
    }

    @Test
    fun anUnknownPeerStaysUserHashId() {
        // No contact and no profile — even when other nodes have profiles.
        assertEquals("User #9", ConversationListBuilder.displayNameFor(9, null, mapOf(1 to profile(1, "Alice"))))
        // A blank saved name falls back too.
        assertEquals("User #9", ConversationListBuilder.displayNameFor(9, contact(9, "  "), emptyMap()))
    }

    @Test
    fun aBlankProfileFallsBackToTheContactName() {
        val stored = contact(10, "Saved")
        assertEquals("Saved", ConversationListBuilder.displayNameFor(10, stored, mapOf(10 to profile(10, "   "))))
    }

    @Test
    fun titleForKeepsTheOldBehaviorWithoutProfiles() {
        // The default (no profiles) keeps the saved-name behavior of existing callers.
        assertEquals("Saved", ConversationListBuilder.titleFor(11, contact(11, "Saved")))
        assertEquals("User #12", ConversationListBuilder.titleFor(12, null))
    }

    @Test
    fun titleForFollowsTheLiveProfileWhenProvided() {
        assertEquals("Alice", ConversationListBuilder.titleFor(13, contact(13, "Old"), mapOf(13 to profile(13, "Alice"))))
    }

    @Test
    fun theMeshRoomTitleIgnoresProfilesAndContacts() {
        val profiles = mapOf(MESH_CONVERSATION_ID to profile(MESH_CONVERSATION_ID, "Impostor"))
        assertEquals(
            ConversationListBuilder.MESH_ROOM_TITLE,
            ConversationListBuilder.titleFor(MESH_CONVERSATION_ID, contact(MESH_CONVERSATION_ID, "X"), profiles),
        )
    }

    @Test
    fun theChatListTitlesFollowALateProfile() {
        val summaries = listOf(ConversationSummary(7, now - 1_000L, ChatDirection.IN, ChatKind.TEXT, "hi", 0))
        val contacts = listOf(contact(8, "Saved")) // no conversation yet: people section
        val presence = mapOf(8 to LivePresenceState.ONLINE_DIRECT)

        // Before the profiles arrive: fallback for the chat row, saved name for the person.
        val before = LiveConversationListBuilder.build(summaries, contacts, presence, now)
        assertEquals("User #7", before.first { it.conversationId == 7 }.title)
        assertEquals("Saved", before.first { it.conversationId == 8 }.title)

        // The profiles arrive: both rows are re-titled immediately.
        val after = LiveConversationListBuilder.build(
            summaries, contacts, presence, now, mapOf(7 to profile(7, "Alice"), 8 to profile(8, "Alicia")),
        )
        assertEquals("Alice", after.first { it.conversationId == 7 }.title)
        assertEquals("Alicia", after.first { it.conversationId == 8 }.title)
    }
}
