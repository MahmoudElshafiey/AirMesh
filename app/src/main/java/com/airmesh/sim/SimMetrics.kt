package com.airmesh.sim

import com.airmesh.protocol.Frame

/** First time one node received one originated message. */
data class SimDelivery(
    val nodeId: Int,
    val time: Long,
    val hops: Int,
)

/** Delivery records for one message originated by one node. */
data class SimMessageMetrics(
    val originNode: Int,
    val originTime: Long,
    val deliveries: List<SimDelivery>,
)

/**
 * Immutable snapshot of one simulation run.
 *
 * The origin already has the message when it originates it, so only other
 * nodes count as deliveries. [expectedCopies] is
 * `messages.size * (nodeCount - 1)`, which makes a total loss scenario report a
 * delivery ratio of zero.
 */
data class SimMetrics(
    val messages: List<SimMessageMetrics>,
    val deliveredCopies: Int,
    val expectedCopies: Int,
    val deliveryRatio: Double,
    val averageLatencyMs: Double,
    val maxLatencyMs: Long,
    val averageHopCount: Double,
    val totalFramesOnAir: Int,
    val duplicatesDropped: Long,
    val framesStored: Int,
    val framesReplayed: Int,
)

/** Mutable collector owned by [SimNetwork]; [snapshot] makes it immutable. */
internal class SimMetricsCollector {

    private data class MessageKey(val originNode: Int, val seq: Int)

    private class MutableMessage(
        val originNode: Int,
        val originTime: Long,
        val initialTtl: Int,
    ) {
        val deliveries = LinkedHashMap<Int, SimDelivery>()
    }

    private val messages = LinkedHashMap<MessageKey, MutableMessage>()

    var totalFramesOnAir: Int = 0
        private set

    var framesStored: Int = 0
        private set

    var framesReplayed: Int = 0
        private set

    fun onOriginated(frame: Frame, at: Long) {
        messages[MessageKey(frame.nodeId, frame.seq)] =
            MutableMessage(
                originNode = frame.nodeId,
                originTime = at,
                initialTtl = frame.ttl,
            )
    }

    fun onDelivered(nodeId: Int, frame: Frame, at: Long) {
        val message = messages[MessageKey(frame.nodeId, frame.seq)] ?: return
        if (nodeId == message.originNode) return
        if (message.deliveries.containsKey(nodeId)) return

        // A forwarded copy has been decremented once per hop. The origin sent
        // the frame with its full initial TTL, so a direct neighbor has hop 1.
        val hops = message.initialTtl - frame.ttl + 1
        message.deliveries[nodeId] = SimDelivery(nodeId = nodeId, time = at, hops = hops)
    }

    fun recordOnAir() {
        totalFramesOnAir++
    }

    fun recordStored() {
        framesStored++
    }

    fun recordReplayed() {
        framesReplayed++
    }

    fun snapshot(nodeCount: Int, duplicatesDropped: Long): SimMetrics {
        val messageSnapshots = messages.values.map { message ->
            SimMessageMetrics(
                originNode = message.originNode,
                originTime = message.originTime,
                deliveries = message.deliveries.values.toList(),
            )
        }

        var deliveredCopies = 0
        var latencyTotal = 0L
        var maxLatency = 0L
        var hopTotal = 0
        for (message in messageSnapshots) {
            for (delivery in message.deliveries) {
                deliveredCopies++
                val latency = delivery.time - message.originTime
                latencyTotal += latency
                if (latency > maxLatency) maxLatency = latency
                hopTotal += delivery.hops
            }
        }

        val expectedCopies = messages.size * maxOf(0, nodeCount - 1)
        val deliveryRatio =
            if (expectedCopies == 0) 0.0 else deliveredCopies.toDouble() / expectedCopies
        val averageLatency =
            if (deliveredCopies == 0) 0.0 else latencyTotal.toDouble() / deliveredCopies
        val averageHops =
            if (deliveredCopies == 0) 0.0 else hopTotal.toDouble() / deliveredCopies

        return SimMetrics(
            messages = messageSnapshots,
            deliveredCopies = deliveredCopies,
            expectedCopies = expectedCopies,
            deliveryRatio = deliveryRatio,
            averageLatencyMs = averageLatency,
            maxLatencyMs = maxLatency,
            averageHopCount = averageHops,
            totalFramesOnAir = totalFramesOnAir,
            duplicatesDropped = duplicatesDropped,
            framesStored = framesStored,
            framesReplayed = framesReplayed,
        )
    }
}
