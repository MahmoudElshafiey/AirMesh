package com.airmesh.sim

import kotlin.math.ceil
import kotlin.random.Random

internal data class MediumAccessMetrics(
    val collisions: Int,
    val averageBackoffWaitMs: Double,
    val channelBusyRatio: Double,
    val framesDroppedAfterMaxRetries: Int,
)

/**
 * Slotted CSMA/CA medium access model for the AirMesh simulator.
 *
 * Senders sense whether any neighbor in range is currently transmitting. While
 * busy, the backoff counter freezes and wait time accumulates. When idle, the
 * counter decrements each slot. When it reaches zero, transmission begins.
 *
 * If two transmissions overlap in time at a common receiver, both suffer a
 * collision. An oracle immediately informs the sender at the end of the
 * attempt, doubling the contention window and scheduling a retry up to
 * [CsmaCaConfig.maxRetries].
 */
internal class SimMediumAccess(
    private val clock: SimClock,
    private val events: SimEventQueue,
    private val random: Random,
    val config: CsmaCaConfig,
    private val neighborsOf: (Int) -> Set<Int>,
    private val recordAttempt: () -> Unit,
    private val deliver: (from: Int, to: Int, bytes: ByteArray) -> Unit,
) {

    private class FrameRequest(
        val from: Int,
        val to: Int,
        val bytes: ByteArray,
    ) {
        var failedAttempts: Int = 0
        var backoffRemaining: Int? = null
        var waitMs: Long = 0L
    }

    private class Attempt(
        val state: NodeState,
        val frame: FrameRequest,
        val start: Long,
        val end: Long,
    ) {
        var collided: Boolean = false
    }

    private class NodeState(val id: Int, cwMin: Int, cwMax: Int) {
        val queue = ArrayDeque<FrameRequest>()
        val cw = ContentionWindow(cwMin, cwMax)
        var current: FrameRequest? = null
        var active: Attempt? = null
    }

    private data class BusyInterval(val start: Long, val end: Long)

    private val nodeStates = sortedMapOf<Int, NodeState>()
    private val active = mutableListOf<Attempt>()
    private val busyIntervals = mutableListOf<BusyInterval>()

    private var tickScheduled: Boolean = false
    private var collisions: Int = 0
    private var completedFrames: Int = 0
    private var totalWaitMs: Long = 0L
    private var framesDroppedAfterMaxRetries: Int = 0

    fun enqueue(from: Int, to: Int, bytes: ByteArray) {
        require(from != to) { "a node cannot transmit to itself: $from" }
        val state = nodeStates.getOrPut(from) {
            NodeState(from, config.cwMin, config.cwMax)
        }
        state.queue.addLast(FrameRequest(from, to, bytes))
        scheduleTick(clock.now + config.slotTimeMs)
    }

    fun metrics(): MediumAccessMetrics {
        val avgWait =
            if (completedFrames == 0) {
                0.0
            } else {
                totalWaitMs.toDouble() / completedFrames
            }
        return MediumAccessMetrics(
            collisions = collisions,
            averageBackoffWaitMs = avgWait,
            channelBusyRatio = computeChannelBusyRatio(clock.now),
            framesDroppedAfterMaxRetries = framesDroppedAfterMaxRetries,
        )
    }

    private fun scheduleTick(at: Long) {
        if (tickScheduled) return
        tickScheduled = true
        events.schedule(at) {
            tickScheduled = false
            onSlot()
        }
    }

    private fun onSlot() {
        val now = clock.now
        val activeSenders = active.filter { it.start < now && it.end > now }
            .map { it.state.id }
            .toSet()

        val ready = mutableListOf<NodeState>()
        for (state in nodeStates.values) {
            if (state.active != null) continue

            var frame = state.current
            if (frame == null) {
                frame = state.queue.removeFirstOrNull() ?: continue
                state.current = frame
            }

            val isBusy = neighborsOf(state.id).any { it in activeSenders }
            if (isBusy) {
                frame.waitMs += config.slotTimeMs
                continue
            }

            var backoff = frame.backoffRemaining
            if (backoff == null) {
                backoff = state.cw.nextBackoff(random)
                frame.backoffRemaining = backoff
            }

            if (backoff > 0) {
                frame.backoffRemaining = backoff - 1
                frame.waitMs += config.slotTimeMs
            } else {
                ready += state
            }
        }

        startAttempts(ready, now)

        if (hasWork()) {
            scheduleTick(now + config.slotTimeMs)
        }
    }

    private fun startAttempts(states: List<NodeState>, now: Long) {
        val started = mutableListOf<Attempt>()
        for (state in states) {
            val frame = state.current ?: continue
            val duration = transmissionDurationMs(frame.bytes)
            val attempt = Attempt(state, frame, start = now, end = now + duration)

            for (other in active) {
                checkCollision(attempt, other)
            }
            for (other in started) {
                checkCollision(attempt, other)
            }

            active += attempt
            started += attempt
            state.active = attempt

            recordAttempt()
            busyIntervals += BusyInterval(now, attempt.end)

            events.schedule(attempt.end) {
                finishAttempt(attempt)
            }
        }
    }

    private fun checkCollision(a: Attempt, b: Attempt) {
        val overlaps = a.start < b.end && b.start < a.end
        if (!overlaps) return

        // A frame is lost when another transmission overlaps it at a common
        // receiver: a node that can hear both senders. Senders are transmitting,
        // so they are not treated as receivers in this scaled model.
        val aNeighbors = neighborsOf(a.frame.from)
        val bNeighbors = neighborsOf(b.frame.from)
        val commonReceiver = (aNeighbors intersect bNeighbors)
            .any { it != a.frame.from && it != b.frame.from }

        if (commonReceiver) {
            markCollided(a)
            markCollided(b)
        }
    }

    private fun markCollided(attempt: Attempt) {
        if (!attempt.collided) {
            attempt.collided = true
            collisions++
        }
    }

    private fun finishAttempt(attempt: Attempt) {
        active.remove(attempt)
        val state = attempt.state
        if (state.active === attempt) {
            state.active = null
        }

        val frame = state.current ?: return

        if (attempt.collided) {
            state.cw.onCollision()
            frame.failedAttempts++
            if (frame.failedAttempts > config.maxRetries) {
                framesDroppedAfterMaxRetries++
                completedFrames++
                totalWaitMs += frame.waitMs
                state.current = null
            } else {
                frame.backoffRemaining = null
            }
        } else {
            state.cw.onSuccess()
            deliver(frame.from, frame.to, frame.bytes)
            completedFrames++
            totalWaitMs += frame.waitMs
            state.current = null
        }

        if (hasWork()) {
            scheduleTick(clock.now + config.slotTimeMs)
        }
    }

    private fun hasWork(): Boolean =
        active.isNotEmpty() || nodeStates.values.any { it.current != null || it.queue.isNotEmpty() }

    private fun transmissionDurationMs(bytes: ByteArray): Long {
        val bits = bytes.size.toLong() * 8L
        val bitsPerSlot = config.bitrate.toDouble() * config.slotTimeMs.toDouble() / 1000.0
        val slots = maxOf(1L, ceil(bits / bitsPerSlot).toLong())
        return slots * config.slotTimeMs
    }

    private fun computeChannelBusyRatio(now: Long): Double {
        if (now <= 0L) return 0.0
        val clipped = busyIntervals.mapNotNull { interval ->
            if (interval.start < now) {
                interval.start to minOf(interval.end, now)
            } else {
                null
            }
        }.sortedBy { it.first }

        if (clipped.isEmpty()) return 0.0

        var totalMs = 0L
        var currentStart = clipped.first().first
        var currentEnd = clipped.first().second

        for (i in 1 until clipped.size) {
            val (s, e) = clipped[i]
            if (s <= currentEnd) {
                if (e > currentEnd) currentEnd = e
            } else {
                totalMs += (currentEnd - currentStart)
                currentStart = s
                currentEnd = e
            }
        }
        totalMs += (currentEnd - currentStart)

        return totalMs.toDouble() / now
    }
}
