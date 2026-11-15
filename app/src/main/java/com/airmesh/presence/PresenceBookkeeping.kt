package com.airmesh.presence

/**
 * Per-node evidence bookkeeping for [PresenceResolver] (TASK U5 part 2). Tracks, per node:
 * whether it is a direct neighbor right now, when that link dropped, and the last frame time.
 * It also tracks our own link's up/down transitions so the resolver can apply its restore
 * grace rule. Pure Kotlin — the clock is injected for fake-clock tests; no Android imports.
 */
class PresenceBookkeeping(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val resolver: PresenceResolver = PresenceResolver(clock),
) {
    private class NodeState {
        var isDirectNeighbor = false
        /** When this node stopped being our direct neighbor; null while connected or if it never was. */
        var directLostAt: Long? = null
        /** Last frame of any kind from this origin (direct or relayed); null if none ever. */
        var lastActivityAt: Long? = null
        /** Last frame received while they were a direct neighbor; null if none. */
        var lastDirectActivityAt: Long? = null
    }

    private val nodes = HashMap<Int, NodeState>()
    private var neighborIds: Set<Int> = emptySet()
    private var connected = false
    private var ownLinkLostAt: Long? = null
    private var ownLinkRestoredAt: Long? = null
    /**
     * Each node's last published state while we had at least one neighbor. During our own
     * outage the published states are all NOT_CONNECTED, so this memory — not the latest
     * round — answers "were they online before the loss" for the restore grace rule.
     */
    private var stateWhileConnected = HashMap<Int, PresenceState>()

    /**
     * Feed the current direct-neighbor set (TASK U5 part 2). Records per-node link drops and
     * our own link's up/down transitions. When the WHOLE set goes empty we cannot tell which
     * neighbor left on its own and which one was cut off by our outage, so no drop time is
     * stamped in that case — after a restore, the resolver's grace rule decides instead.
     */
    @Synchronized
    fun noteNeighbors(ids: Set<Int>) {
        val now = clock()
        if (!ids.isEmpty()) {
            if (!connected) {
                // Disconnected -> connected; only a recorded loss makes this a "restore".
                if (ownLinkLostAt != null) ownLinkRestoredAt = now
                connected = true
            }
        } else if (connected) {
            // Connected -> disconnected: we went deaf to everyone at once.
            ownLinkLostAt = now
            connected = false
        }
        neighborIds = ids

        val all = HashSet<Int>(ids)
        all.addAll(nodes.keys)
        for (id in all) {
            val st = nodes.getOrPut(id) { NodeState() }
            if (st.isDirectNeighbor && id !in ids) {
                st.isDirectNeighbor = false
                // Only stamp a drop time when WE are still connected: that is "they left".
                if (!ids.isEmpty()) st.directLostAt = now
            } else if (!st.isDirectNeighbor && id in ids) {
                st.isDirectNeighbor = true
                // Fresh socket: an old drop marker would be stale and must not survive.
                st.directLostAt = null
            }
        }
    }

    /** A frame of any kind (direct or relayed) arrived from [nodeId]. */
    @Synchronized
    fun noteActivity(nodeId: Int) {
        val now = clock()
        val st = nodes.getOrPut(nodeId) { NodeState() }
        // Copy the mutable fields to locals first: smart casts on member vars are not allowed.
        val last = st.lastActivityAt
        if (last == null || now > last) st.lastActivityAt = now
        val isDirect = st.isDirectNeighbor
        val lastDirect = st.lastDirectActivityAt
        if (isDirect && (lastDirect == null || now > lastDirect)) {
            st.lastDirectActivityAt = now
        }
    }

    /** Current state of every node we have ever seen; also remembers this round for the next. */
    @Synchronized
    fun states(): Map<Int, PresenceState> {
        val ownLink = OwnLink(neighborIds.size, ownLinkLostAt, ownLinkRestoredAt)
        val result = HashMap<Int, PresenceState>(nodes.size)
        for ((id, st) in nodes) {
            val previousWasOnline = when (stateWhileConnected[id]) {
                PresenceState.ONLINE_DIRECT, PresenceState.ONLINE_RELAYED -> true
                else -> false
            }
            val evidence = ContactEvidence(
                isDirectNeighbor = st.isDirectNeighbor,
                directLostAt = st.directLostAt,
                lastActivityAt = st.lastActivityAt,
                lastDirectActivityAt = st.lastDirectActivityAt,
                previousStateWasOnline = previousWasOnline,
            )
            result[id] = resolver.resolve(ownLink, evidence)
        }
        // Refresh the "online before our outage" memory only while we are connected: during
        // an outage every state is NOT_CONNECTED and must not erase what came before it.
        if (neighborIds.isNotEmpty()) stateWhileConnected.putAll(result)
        return result
    }
}
