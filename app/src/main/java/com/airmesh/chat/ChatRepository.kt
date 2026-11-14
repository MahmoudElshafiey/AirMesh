package com.airmesh.chat

/**
 * Turns node-level events into chat rows (TASK D2 part 1). Pure Kotlin with an
 * injectable clock so the whole history logic is testable on the JVM.
 *
 * The repository never talks to sockets or Android; it only writes through
 * [ChatStore]. Deduplication happens at the store's unique key
 * (originNode, originSeq, kind), so re-emitted StateFlows are harmless: a second
 * record of the same send upgrades the stored row instead of duplicating it.
 */
class ChatRepository(
    /** This device's node id: the origin of every OUT row we record. */
    private val localNodeId: Int,
    private val store: ChatStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /** Retention cap per conversation; enforced after every insert. */
        const val RETENTION_PER_CONVERSATION = 500
    }

    /** A broadcast text message received from [originNode]. Mesh room, read, SENT. */
    fun onIncomingBroadcast(originNode: Int, text: String, originSeq: Int) {
        insertAndPrune(
            ChatMessage(
                conversationId = MESH_CONVERSATION_ID,
                direction = ChatDirection.IN,
                kind = ChatKind.TEXT,
                text = text,
                originNode = originNode,
                originSeq = originSeq,
                createdAt = clock(),
                status = ChatStatus.SENT,
                isRead = true,
            ),
        )
    }

    /** A location share received from [originNode]. Mesh room, read, SENT. */
    fun onIncomingLocation(
        originNode: Int,
        latitudeE6: Int,
        longitudeE6: Int,
        accuracyMeters: Int,
        originSeq: Int,
    ) {
        insertAndPrune(
            ChatMessage(
                conversationId = MESH_CONVERSATION_ID,
                direction = ChatDirection.IN,
                kind = ChatKind.LOCATION,
                latitudeE6 = latitudeE6,
                longitudeE6 = longitudeE6,
                accuracyMeters = accuracyMeters,
                originNode = originNode,
                originSeq = originSeq,
                createdAt = clock(),
                status = ChatStatus.SENT,
                isRead = true,
            ),
        )
    }

    /** A direct message received from [fromNode]: its own conversation, unread. */
    fun onIncomingDirect(fromNode: Int, text: String, seq: Int) {
        insertAndPrune(
            ChatMessage(
                conversationId = fromNode,
                direction = ChatDirection.IN,
                kind = ChatKind.TEXT,
                text = text,
                originNode = fromNode,
                originSeq = seq,
                createdAt = clock(),
                status = ChatStatus.SENT,
                isRead = false,
            ),
        )
    }

    /** Our broadcast send; [handed] says whether a neighbor accepted the first flood. */
    fun recordOutgoingBroadcast(text: String, seq: Int, handed: Boolean) {
        recordOutgoing(
            conversationId = MESH_CONVERSATION_ID,
            kind = ChatKind.TEXT,
            text = text,
            latitudeE6 = null,
            longitudeE6 = null,
            accuracyMeters = null,
            seq = seq,
            handed = handed,
        )
    }

    /** Our direct send to [destNode]. */
    fun recordOutgoingDirect(destNode: Int, text: String, seq: Int, handed: Boolean) {
        recordOutgoing(
            conversationId = destNode,
            kind = ChatKind.TEXT,
            text = text,
            latitudeE6 = null,
            longitudeE6 = null,
            accuracyMeters = null,
            seq = seq,
            handed = handed,
        )
    }

    /** Our location share. */
    fun recordOutgoingLocation(
        latitudeE6: Int,
        longitudeE6: Int,
        accuracyMeters: Int,
        seq: Int,
        handed: Boolean,
    ) {
        recordOutgoing(
            conversationId = MESH_CONVERSATION_ID,
            kind = ChatKind.LOCATION,
            text = null,
            latitudeE6 = latitudeE6,
            longitudeE6 = longitudeE6,
            accuracyMeters = accuracyMeters,
            seq = seq,
            handed = handed,
        )
    }

    /** A receipt from [fromNode] for our frame [ackedSeq]: DELIVERED, never downgraded. */
    fun onReceipt(fromNode: Int, ackedSeq: Int) {
        store.updateOutgoingStatus(fromNode, ackedSeq, ChatStatus.DELIVERED)
    }

    /** The user opened a conversation: all its incoming rows become read. */
    fun markRead(conversationId: Int) = store.markRead(conversationId)

    private fun recordOutgoing(
        conversationId: Int,
        kind: ChatKind,
        text: String?,
        latitudeE6: Int?,
        longitudeE6: Int?,
        accuracyMeters: Int?,
        seq: Int,
        handed: Boolean,
    ) {
        val status = if (handed) ChatStatus.SENT else ChatStatus.QUEUED
        // A re-emitted flow may record the same send twice; the unique key then
        // makes insert a no-op and we upgrade the stored row instead (e.g. a
        // QUEUED row becomes SENT once a replay hands it to a neighbor).
        val inserted = store.insert(
            ChatMessage(
                conversationId = conversationId,
                direction = ChatDirection.OUT,
                kind = kind,
                text = text,
                latitudeE6 = latitudeE6,
                longitudeE6 = longitudeE6,
                accuracyMeters = accuracyMeters,
                originNode = localNodeId,
                originSeq = seq,
                createdAt = clock(),
                status = status,
                isRead = true,
            ),
        )
        if (!inserted) store.updateOutgoingStatus(conversationId, seq, status)
    }

    private fun insertAndPrune(message: ChatMessage) {
        if (store.insert(message)) store.prune(RETENTION_PER_CONVERSATION)
    }
}
