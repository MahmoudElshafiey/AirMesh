package com.airmesh.chat

/**
 * Renders the conversation list (TASK D2 part 1): the pinned mesh room, then
 * conversations by last message time (newest first), then live contacts that do
 * not have a conversation yet. A pure function of its inputs so every ordering
 * and formatting rule is unit-testable on the JVM.
 */
object ConversationListBuilder {

    /** Display name of the broadcast room. */
    const val MESH_ROOM_TITLE = "Everyone nearby"

    /** Preview text for location rows. */
    const val LOCATION_PREVIEW = "Location"

    fun build(
        summaries: List<ConversationSummary>,
        contacts: List<Contact>,
        presence: Map<Int, NodePresence>,
        // Kept in the signature so future time-based rules (e.g. hiding stale
        // rows) do not change callers; unused for now.
        @Suppress("UNUSED_PARAMETER") nowMs: Long,
    ): List<ConversationItem> {
        val contactByNode = contacts.associateBy { it.nodeId }
        val summaryByConv = summaries.associateBy { it.conversationId }

        // 1) The mesh room is always first, even before any message exists.
        val items = mutableListOf(
            itemForConversation(MESH_CONVERSATION_ID, summaryByConv[MESH_CONVERSATION_ID], null, presence),
        )

        // 2) Conversations with messages, newest last-message first (ties: id).
        summaries
            .filter { it.conversationId != MESH_CONVERSATION_ID }
            .sortedWith(compareByDescending<ConversationSummary> { it.lastMessageAt }.thenBy { it.conversationId })
            .forEach { summary ->
                items += itemForConversation(summary.conversationId, summary, contactByNode[summary.conversationId], presence)
            }

        // 3) Contacts that are ONLINE or RECENT but have no conversation yet:
        //    ONLINE first, then by name A-Z.
        val withConversation = summaries.map { it.conversationId }.toSet()
        contacts
            .filter { contact ->
                if (contact.nodeId == MESH_CONVERSATION_ID) return@filter false
                if (contact.nodeId in withConversation) return@filter false
                when (presence[contact.nodeId]?.state) {
                    PresenceState.ONLINE, PresenceState.RECENT -> true
                    else -> false
                }
            }
            .sortedWith(
                compareBy<Contact> { presence[it.nodeId]!!.state != PresenceState.ONLINE }
                    .thenBy { titleFor(it.nodeId, it) },
            )
            .forEach { contact ->
                items += itemForConversation(contact.nodeId, null, contact, presence)
            }

        return items
    }

    /** Display name of a peer: the announced name or "User #id". */
    fun titleFor(conversationId: Int, contact: Contact?): String =
        if (conversationId == MESH_CONVERSATION_ID) MESH_ROOM_TITLE else contact?.displayName ?: "User #$conversationId"

    /** Up to two leading letters of the title's words; "?" when it has none. */
    fun initialsOf(title: String): String {
        val letters = title.split(' ')
            .mapNotNull { word -> word.firstOrNull()?.takeIf(Char::isLetter) }
            .take(2)
            .map { it.uppercaseChar() }
        return if (letters.isEmpty()) "?" else letters.joinToString("")
    }

    private fun itemForConversation(
        conversationId: Int,
        summary: ConversationSummary?,
        contact: Contact?,
        presence: Map<Int, NodePresence>,
    ): ConversationItem {
        val isMeshRoom = conversationId == MESH_CONVERSATION_ID
        val title = titleFor(conversationId, contact)
        return ConversationItem(
            conversationId = conversationId,
            title = title,
            initials = initialsOf(title),
            colorIndex = contact?.colorIndex ?: 0,
            preview = previewOf(summary),
            timeMs = summary?.lastMessageAt ?: 0L,
            unreadCount = summary?.unreadCount ?: 0,
            // The mesh room is not a node; peers without presence are UNKNOWN.
            presence = if (isMeshRoom) NodePresence(PresenceState.UNKNOWN, false)
                else presence[conversationId] ?: NodePresence(PresenceState.UNKNOWN, false),
        )
    }

    private fun previewOf(summary: ConversationSummary?): String = when {
        summary == null -> ""
        summary.lastKind == ChatKind.LOCATION -> LOCATION_PREVIEW
        summary.lastDirection == ChatDirection.OUT -> "You: ${summary.lastText.orEmpty()}"
        else -> summary.lastText.orEmpty()
    }
}
