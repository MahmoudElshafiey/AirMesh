package com.airmesh.chat

/**
 * Tracks when we last saw any frame from each node (TASK D2 part 1). Any frame
 * activity counts — including frames relayed through other nodes, which is why a
 * node can be ONLINE without being a direct neighbor; [NodePresence.isDirectNeighbor]
 * carries that distinction separately. Nodes absent from [states] are UNKNOWN.
 */
class PresenceTracker(private val clock: () -> Long) {

    companion object {
        /** Seen within the last 100 s: ONLINE. */
        const val ONLINE_WINDOW_MS = 100_000L

        /** Seen within the last 24 h: RECENT; older entries are dropped. */
        const val RECENT_WINDOW_MS = 24 * 60 * 60 * 1000L
    }

    private val lock = Any()
    private var lastSeen = HashMap<Int, Long>()

    /** Records frame activity from [nodeId] at the current clock. */
    fun noteActivity(nodeId: Int) {
        synchronized(lock) {
            val now = clock()
            lastSeen[nodeId] = now
            // Drop entries that fell out of the RECENT window so the map stays bounded.
            lastSeen.keys.removeIf { now - lastSeen.getValue(it) > RECENT_WINDOW_MS }
        }
    }

    /**
     * Presence of every node seen within [RECENT_WINDOW_MS]: ONLINE when seen
     * within [ONLINE_WINDOW_MS], else RECENT. [neighborIds] is the live neighbor
     * set used for the direct-neighbor flag.
     */
    fun states(neighborIds: Set<Int>): Map<Int, NodePresence> = synchronized(lock) {
        val now = clock()
        // Prune here too, so a quiet mesh still ages nodes out of the result.
        lastSeen.keys.removeIf { now - lastSeen.getValue(it) > RECENT_WINDOW_MS }
        lastSeen.mapValues { (nodeId, seenAt) ->
            val state = if (now - seenAt <= ONLINE_WINDOW_MS) PresenceState.ONLINE else PresenceState.RECENT
            NodePresence(state, nodeId in neighborIds)
        }
    }
}
