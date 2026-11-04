package com.airmesh.sim

import java.util.PriorityQueue

/**
 * Deterministic event queue ordered by virtual time.
 *
 * Equal timestamps are executed in insertion order. The sequence number is
 * global, so an event scheduled while another same-time event is running is
 * placed after every event already in the queue.
 */
internal class SimEventQueue {

    private data class Event(
        val time: Long,
        val sequence: Long,
        val action: suspend () -> Unit,
    )

    private val queue = PriorityQueue(
        compareBy<Event> { it.time }.thenBy { it.sequence },
    )
    private var nextSequence = 0L

    fun schedule(time: Long, action: suspend () -> Unit) {
        queue += Event(time = time, sequence = nextSequence++, action = action)
    }

    /**
     * Runs every event whose timestamp is at or before [endTime], then advances
     * [clock] to [endTime]. Events added by handlers with a timestamp inside the
     * window are processed too.
     */
    suspend fun runUntil(endTime: Long, clock: SimClock) {
        require(endTime >= clock.now) {
            "endTime must not be before virtual now: now=${clock.now}, endTime=$endTime"
        }

        while (true) {
            val next = queue.peek() ?: break
            if (next.time > endTime) break
            queue.poll()
            clock.advanceTo(next.time)
            next.action()
        }
        clock.advanceTo(endTime)
    }
}
