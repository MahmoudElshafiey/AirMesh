package com.airmesh.transport

/**
 * True when a "no group" report that started at [firstFalseMs] is old enough to
 * confirm the group is gone: at least [graceMs] have passed.
 */
fun groupGoneConfirmed(
    firstFalseMs: Long,
    nowMs: Long,
    graceMs: Long = GroupPresenceTracker.DEFAULT_GRACE_MS,
): Boolean = nowMs - firstFalseMs >= graceMs

/**
 * Debounces Wi-Fi Direct "group not formed" reports during formation.
 *
 * While a group is being set up the system can briefly report `groupFormed=false`
 * (role negotiation, address assignment). Closing live TCP sockets on such a
 * blip would tear down a link that is about to be useful again, so a "gone"
 * verdict only sticks once reports have stayed false for [graceMs]. The first
 * false report starts the window; further false reports do not restart it.
 */
class GroupPresenceTracker(private val graceMs: Long = DEFAULT_GRACE_MS) {

    private var falseSinceMs: Long? = null

    /**
     * Feeds one groupFormed report observed at [nowMs].
     *
     * Returns true when the transport should be closed now (the "gone" episode
     * has outlasted the grace period). A formed-group report always returns
     * false and cancels any pending episode.
     */
    fun onReport(groupFormed: Boolean, nowMs: Long): Boolean {
        if (groupFormed) {
            falseSinceMs = null
            return false
        }
        val firstFalse = falseSinceMs ?: nowMs.also { falseSinceMs = it }
        return groupGoneConfirmed(firstFalse, nowMs, graceMs)
    }

    /**
     * Milliseconds left in the pending "gone" episode at [nowMs], or null when
     * no episode is pending. Callers use this to schedule a confirmation check.
     */
    fun pendingGraceRemaining(nowMs: Long): Long? {
        val firstFalse = falseSinceMs ?: return null
        return (firstFalse + graceMs - nowMs).coerceAtLeast(0L)
    }

    /** Clears any pending episode (e.g. when the transport is closed anyway). */
    fun reset() {
        falseSinceMs = null
    }

    companion object {
        /** How long "no group" must persist before the group counts as gone. */
        const val DEFAULT_GRACE_MS = 2_000L
    }
}
