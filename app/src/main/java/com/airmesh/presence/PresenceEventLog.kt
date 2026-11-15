package com.airmesh.presence

/** A confirmed presence transition for one node (TASK U5 part 1). */
enum class PresenceTransition { WENT_OUT_OF_RANGE, BACK_ONLINE }

/** One logged transition; [atMs] is when the new state STARTED holding, not when it was confirmed. */
data class PresenceEvent(val nodeId: Int, val transition: PresenceTransition, val atMs: Long)

/** Coarse sides used for debouncing: CHECKING/NOT_CONNECTED/UNKNOWN confirm nothing. */
private enum class Bucket { ONLINE, OFFLINE, NEUTRAL }

/**
 * In-memory log of presence transitions (TASK U5 part 1). A transition is recorded only after
 * the new side has been reported for [debounceMs] straight, so CHECKING flicker and brief drops
 * never produce events. Neutral states neither confirm nor cancel a pending transition: once we
 * leave the confirmed side into an offline state, a later CHECKING does not reset the clock —
 * but returning to the other side before [debounceMs] cancels it. The first sighting of a node
 * is adopted silently (it did not "transition" from our point of view). Keeps at most
 * [maxEventsPerNode] newest events per node, oldest dropped.
 */
class PresenceEventLog(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val debounceMs: Long = DEBOUNCE_MS,
    private val maxEventsPerNode: Int = MAX_EVENTS_PER_NODE,
) {
    companion object {
        /** A new side must be reported this long before it counts as a real transition. */
        const val DEBOUNCE_MS = 5_000L

        /** Newest transitions kept per node; older ones are dropped. */
        const val MAX_EVENTS_PER_NODE = 20
    }

    private class NodeTracker {
        var confirmed: Bucket? = null // last CONFIRMED side; neutral never confirms
        var pendingTarget: Bucket? = null
        var pendingSinceMs: Long = 0L
    }

    private val trackers = HashMap<Int, NodeTracker>()
    private val eventsByNode = HashMap<Int, ArrayDeque<PresenceEvent>>()

    private fun bucketOf(state: PresenceState): Bucket = when (state) {
        PresenceState.ONLINE_DIRECT, PresenceState.ONLINE_RELAYED -> Bucket.ONLINE
        PresenceState.OUT_OF_RANGE, is PresenceState.LastSeen -> Bucket.OFFLINE
        PresenceState.CHECKING, PresenceState.NOT_CONNECTED, PresenceState.UNKNOWN -> Bucket.NEUTRAL
    }

    /**
     * Feed the resolver's current state for one node. The service calls this on every
     * recompute (event-driven + 5 s tick), even when the published map did not change: the
     * repeated notes are what advance a pending transition past its debounce window.
     */
    @Synchronized
    fun note(nodeId: Int, state: PresenceState) {
        val now = clock()
        val bucket = bucketOf(state)
        val tracker = trackers.getOrPut(nodeId) { NodeTracker() }

        val pendingTarget = tracker.pendingTarget
        if (pendingTarget != null) {
            when {
                // Flicker through a neutral state neither confirms nor cancels the wait.
                bucket == Bucket.NEUTRAL -> return

                // Still on the new side: confirm once it has held long enough.
                bucket == pendingTarget -> {
                    if (now - tracker.pendingSinceMs >= debounceMs) {
                        emit(nodeId, pendingTarget, tracker.pendingSinceMs)
                        tracker.confirmed = pendingTarget
                        tracker.pendingTarget = null
                    }
                    return
                }

                // Back on the old side before it held long enough: brief drop, ignore it.
                else -> tracker.pendingTarget = null
            }
        }

        if (bucket == Bucket.NEUTRAL) return
        val confirmed = tracker.confirmed
        when {
            // First sighting of this node: adopt the side silently, no event.
            confirmed == null -> tracker.confirmed = bucket
            // Stable on the confirmed side: nothing to do.
            bucket == confirmed -> Unit
            // A real transition begins: start the debounce clock at THIS moment.
            else -> {
                tracker.pendingTarget = bucket
                tracker.pendingSinceMs = now
            }
        }
    }

    private fun emit(nodeId: Int, target: Bucket, atMs: Long) {
        val transition = if (target == Bucket.OFFLINE) {
            PresenceTransition.WENT_OUT_OF_RANGE
        } else {
            PresenceTransition.BACK_ONLINE
        }
        val list = eventsByNode.getOrPut(nodeId) { ArrayDeque() }
        list.addLast(PresenceEvent(nodeId, transition, atMs))
        while (list.size > maxEventsPerNode) list.removeFirst()
    }

    /** The node's transitions, oldest first; empty when none were confirmed. */
    @Synchronized
    fun eventsFor(nodeId: Int): List<PresenceEvent> = eventsByNode[nodeId]?.toList().orEmpty()

    /** Immutable snapshot of every node's event list, for publishing as a StateFlow. */
    @Synchronized
    fun snapshot(): Map<Int, List<PresenceEvent>> = eventsByNode.mapValues { it.value.toList() }
}
