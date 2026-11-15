package com.airmesh.chat

import androidx.compose.runtime.Immutable
import com.airmesh.presence.PresenceState as LivePresenceState

/** One rendered row of the truthful-presence conversation list (TASK U5 part 2). */
@Immutable // all fields are value types
data class LiveConversationItem(
    val conversationId: Int,
    val title: String,
    val initials: String,
    val colorIndex: Int,
    val preview: String,
    /** Last message time; 0 when the contact has no messages yet. */
    val timeMs: Long,
    val unreadCount: Int,
    /** The node's truthful state (TASK U5 part 2); UNKNOWN for the mesh room and unknown peers. */
    val liveState: LivePresenceState,
)

/**
 * Conversation list with truthful presence states (TASK U5 part 2). Mirrors
 * [ConversationListBuilder] — same room-first ordering, previews and titles — but the "People
 * in your network" section now shows contacts that are online (direct or relayed), checking,
 * or seen within [PEOPLE_SEEN_WINDOW_MS], instead of the old coarse ONLINE/RECENT window. A
 * pure function of its inputs so every rule is unit-testable on the JVM.
 */
object LiveConversationListBuilder {

    /** "People in your network" keeps a LastSeen contact for this long (10 minutes). */
    const val PEOPLE_SEEN_WINDOW_MS = 600_000L

    fun build(
        summaries: List<ConversationSummary>,
        contacts: List<Contact>,
        livePresence: Map<Int, LivePresenceState>,
        nowMs: Long,
    ): List<LiveConversationItem> {
        val contactByNode = contacts.associateBy { it.nodeId }
        val summaryByConv = summaries.associateBy { it.conversationId }

        // 1) The mesh room is always first, even before any message exists.
        val items = mutableListOf(
            itemForConversation(MESH_CONVERSATION_ID, summaryByConv[MESH_CONVERSATION_ID], null, livePresence),
        )

        // 2) Conversations with messages, newest last-message first (ties: id).
        summaries
            .filter { it.conversationId != MESH_CONVERSATION_ID }
            .sortedWith(compareByDescending<ConversationSummary> { it.lastMessageAt }.thenBy { it.conversationId })
            .forEach { summary ->
                items += itemForConversation(summary.conversationId, summary, contactByNode[summary.conversationId], livePresence)
            }

        // 3) Contacts in the people section (see [isInPeopleSection]) without a conversation yet:
        //    online first, then by name A-Z.
        val withConversation = summaries.map { it.conversationId }.toSet()
        contacts
            .filter { contact ->
                if (contact.nodeId == MESH_CONVERSATION_ID) return@filter false
                if (contact.nodeId in withConversation) return@filter false
                isInPeopleSection(livePresence[contact.nodeId], nowMs)
            }
            .sortedWith(
                compareBy<Contact> { !isOnline(livePresence[it.nodeId]) }
                    .thenBy { ConversationListBuilder.titleFor(it.nodeId, it) },
            )
            .forEach { contact ->
                items += itemForConversation(contact.nodeId, null, contact, livePresence)
            }

        return items
    }

    /** People section rule (TASK U5 part 2): online direct/relayed, checking, or seen within the window. */
    fun isInPeopleSection(state: LivePresenceState?, nowMs: Long): Boolean = when (state) {
        LivePresenceState.ONLINE_DIRECT,
        LivePresenceState.ONLINE_RELAYED,
        LivePresenceState.CHECKING -> true

        is LivePresenceState.LastSeen -> nowMs - state.atMs <= PEOPLE_SEEN_WINDOW_MS

        // OUT_OF_RANGE / NOT_CONNECTED / UNKNOWN: not "in your network" right now.
        else -> false
    }

    private fun isOnline(state: LivePresenceState?): Boolean =
        state == LivePresenceState.ONLINE_DIRECT || state == LivePresenceState.ONLINE_RELAYED

    private fun itemForConversation(
        conversationId: Int,
        summary: ConversationSummary?,
        contact: Contact?,
        livePresence: Map<Int, LivePresenceState>,
    ): LiveConversationItem {
        val isMeshRoom = conversationId == MESH_CONVERSATION_ID
        val title = ConversationListBuilder.titleFor(conversationId, contact)
        return LiveConversationItem(
            conversationId = conversationId,
            title = title,
            initials = ConversationListBuilder.initialsOf(title),
            colorIndex = contact?.colorIndex ?: 0,
            preview = previewOf(summary),
            timeMs = summary?.lastMessageAt ?: 0L,
            unreadCount = summary?.unreadCount ?: 0,
            // The mesh room is not a node; peers without presence are UNKNOWN.
            liveState = if (isMeshRoom) LivePresenceState.UNKNOWN else livePresence[conversationId] ?: LivePresenceState.UNKNOWN,
        )
    }

    private fun previewOf(summary: ConversationSummary?): String = when {
        summary == null -> ""
        summary.lastKind == ChatKind.LOCATION -> ConversationListBuilder.LOCATION_PREVIEW
        summary.lastDirection == ChatDirection.OUT -> "You: ${summary.lastText.orEmpty()}"
        else -> summary.lastText.orEmpty()
    }
}
