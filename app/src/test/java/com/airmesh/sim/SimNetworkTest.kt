package com.airmesh.sim

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimNetworkTest {

    @Test
    fun lineOfFiveDeliversEndToEnd() {
        val net = SimNetwork(seed = 1)
        (1..5).forEach { net.addNode(it) }
        (1..4).forEach { hop -> net.connect(hop, hop + 1, latencyMs = 5) }

        net.originate(from = 1, payload = "hello".toByteArray(), at = 0)
        net.runUntil(10_000)

        val message = net.metrics().messages.single()
        assertEquals(1, message.originNode)
        assertEquals(0L, message.originTime)
        assertEquals(listOf(2, 3, 4, 5), message.deliveries.map { it.nodeId })
        assertEquals(listOf(1, 2, 3, 4), message.deliveries.map { it.hops })

        val metrics = net.metrics()
        assertEquals(1.0, metrics.deliveryRatio, 0.0)
        assertEquals(4, metrics.deliveredCopies)
        assertEquals(4, metrics.expectedCopies)
        assertEquals(12.5, metrics.averageLatencyMs, 0.0)
        assertEquals(20L, metrics.maxLatencyMs)
        assertEquals(2.5, metrics.averageHopCount, 0.0)
        assertEquals(4, metrics.totalFramesOnAir)
    }

    @Test
    fun triangleDeliversEachNodeExactlyOnce() {
        val net = SimNetwork(seed = 2)
        (1..3).forEach { net.addNode(it) }
        net.connect(1, 2)
        net.connect(2, 3)
        net.connect(1, 3)

        net.originate(from = 1, payload = "triangle".toByteArray(), at = 0)
        net.runUntil(1_000)

        val message = net.metrics().messages.single()
        assertEquals(listOf(2, 3), message.deliveries.map { it.nodeId }.sorted())
        assertEquals(2, message.deliveries.size)
        assertEquals(setOf(2, 3), message.deliveries.map { it.nodeId }.toSet())
        assertTrue("flood must produce a duplicate", net.metrics().duplicatesDropped > 0)
    }

    @Test
    fun ttlLimitStopsPropagationAtExpectedHop() {
        val net = SimNetwork(seed = 3, initialTtl = 3)
        (1..5).forEach { net.addNode(it) }
        (1..4).forEach { hop -> net.connect(hop, hop + 1, latencyMs = 5) }

        net.originate(from = 1, payload = "ttl".toByteArray(), at = 0)
        net.runUntil(10_000)

        val message = net.metrics().messages.single()
        assertEquals(listOf(2, 3, 4), message.deliveries.map { it.nodeId })
        assertEquals(listOf(1, 2, 3), message.deliveries.map { it.hops })
        assertEquals(3, message.deliveries.size)
        assertEquals(5, net.nodeIds.size)
    }

    @Test
    fun sameSeedProducesIdenticalMetricsTwice() {
        val first = mobilityRun(seed = 42)
        val second = mobilityRun(seed = 42)

        assertEquals(first, second)
        assertTrue("run must produce traffic", first.totalFramesOnAir > 0)
    }

    @Test
    fun differentSeedsProduceDifferentMetrics() {
        val first = mobilityRun(seed = 42)
        val second = mobilityRun(seed = 7)

        assertNotEquals(first, second)
    }

    @Test
    fun lossOfOneDeliversNothing() {
        val net = SimNetwork(seed = 4)
        (1..3).forEach { net.addNode(it) }
        net.connect(1, 2, loss = 1.0)
        net.connect(2, 3, loss = 1.0)
        net.connect(1, 3, loss = 1.0)

        net.originate(from = 1, payload = "lost".toByteArray(), at = 0)
        net.runUntil(10_000)

        val metrics = net.metrics()
        assertTrue(metrics.messages.single().deliveries.isEmpty())
        assertEquals(0, metrics.deliveredCopies)
        assertEquals(0.0, metrics.deliveryRatio, 0.0)
        assertTrue("frames were still handed to the air", metrics.totalFramesOnAir > 0)
    }

    @Test
    fun disconnectClearsNeighborsAndQueuesInsteadOfSending() {
        val net = SimNetwork(seed = 10)
        val one = net.addNode(1)
        val two = net.addNode(2)
        net.connect(1, 2)

        assertEquals(setOf(2), one.transport.neighbors.value)
        assertEquals(setOf(1), two.transport.neighbors.value)

        net.disconnect(1, 2)

        assertTrue(one.transport.neighbors.value.isEmpty())
        assertTrue(two.transport.neighbors.value.isEmpty())

        // The router reads the transport neighbor set live, so originating now
        // must not hand anything to the removed link. Store-and-forward keeps
        // the frame queued for a future appearance of node 2.
        net.originate(from = 1, payload = "queued".toByteArray(), at = 0)
        net.runUntil(100)

        assertEquals(0, net.metrics().totalFramesOnAir)
        assertEquals(1, net.metrics().framesStored)
        assertEquals(0, net.metrics().framesReplayed)
        val pending = runBlocking { one.packetStore.pendingFor(neighborId = 2, notBefore = 0) }
        assertEquals(1, pending.size)
    }

    @Test
    fun frameInFlightIsDroppedWhenLinkIsRemoved() {
        val net = SimNetwork(seed = 11)
        net.addNode(1)
        net.addNode(2)
        net.connect(1, 2, latencyMs = 100)

        net.originate(from = 1, payload = "in-flight".toByteArray(), at = 0)
        net.runUntil(50)
        assertTrue(net.metrics().messages.single().deliveries.isEmpty())

        net.disconnect(1, 2)
        net.runUntil(200)

        assertTrue(net.metrics().messages.single().deliveries.isEmpty())
        assertEquals(1, net.metrics().totalFramesOnAir)
    }

    @Test
    fun readdingLinkReplaysStoredFrameOnce() {
        val net = SimNetwork(seed = 12)
        net.addNode(1)
        net.addNode(2)

        net.originate(from = 1, payload = "stored".toByteArray(), at = 0)
        net.runUntil(100)

        net.connect(1, 2, latencyMs = 5)
        net.runUntil(200)

        val first = net.metrics()
        val delivery = first.messages.single().deliveries.single()
        assertEquals(2, delivery.nodeId)
        assertEquals(105L, delivery.time)
        assertEquals(1, first.framesReplayed)

        net.disconnect(1, 2)
        net.runUntil(300)
        net.connect(1, 2, latencyMs = 5)
        net.runUntil(400)

        val second = net.metrics()
        assertEquals(1, second.messages.single().deliveries.size)
        assertEquals(1, second.framesReplayed)
        assertEquals(1, second.totalFramesOnAir)
    }

    @Test
    fun isolatedMessageIsDeliveredAfterLinkAppears() {
        val net = SimNetwork(seed = 5)
        net.addNode(1)
        net.addNode(2)

        net.originate(from = 1, payload = "deferred".toByteArray(), at = 0)
        net.runUntil(10_000)

        assertTrue(net.metrics().messages.single().deliveries.isEmpty())

        net.connect(1, 2, latencyMs = 5)
        net.runUntil(20_000)

        val metrics = net.metrics()
        val delivery = metrics.messages.single().deliveries.single()
        assertEquals(2, delivery.nodeId)
        assertEquals(10_005L, delivery.time)
        assertEquals(1, delivery.hops)
        // The origin stores the frame; the receiver also queues a copy for any
        // future neighbor because its only current neighbor is the sender.
        assertTrue(metrics.framesStored >= 1)
        assertEquals(1, metrics.framesReplayed)
        assertEquals(1.0, metrics.deliveryRatio, 0.0)
    }

    @Test
    fun mobilityWithFixedSeedStaysInExpectedRange() {
        val metrics = mobilityRun(seed = 42)

        assertEquals(12, metrics.messages.size)
        assertTrue(
            "unexpected delivery ratio ${metrics.deliveryRatio}",
            metrics.deliveryRatio in 0.20..0.98,
        )
        assertTrue(metrics.totalFramesOnAir > 0)
        assertTrue(metrics.averageHopCount >= 1.0)
    }

    @Test
    fun mobilityChangesLinkSetOverTime() {
        val net = mobilityNetwork(seed = 42)
        val snapshots = mutableListOf(linkSet(net))

        listOf(5_000L, 10_000L, 20_000L, 40_000L).forEach { time ->
            net.runUntil(time)
            snapshots += linkSet(net)
        }

        assertTrue(
            "link set never changed: $snapshots",
            snapshots.distinct().size > 1,
        )
    }

    @Test
    fun zeroSpeedMobilityKeepsLinkSetStable() {
        val net = mobilityNetwork(seed = 42, minSpeed = 0.0, maxSpeed = 0.0)
        val initial = linkSet(net)

        listOf(5_000L, 10_000L, 20_000L, 40_000L).forEach { time ->
            net.runUntil(time)
            assertEquals("links changed at $time", initial, linkSet(net))
        }
    }

    /**
     * Six nodes in an 80x80 area with a short radio range, seeded loss, and
     * random-waypoint movement. The topology changes enough that delivery is
     * neither trivial nor impossible.
     */
    private fun mobilityNetwork(
        seed: Long,
        minSpeed: Double = 2.0,
        maxSpeed: Double = 5.0,
    ): SimNetwork {
        val net = SimNetwork(seed = seed)
        net.addNode(1, x = 0.0, y = 0.0)
        net.addNode(2, x = 20.0, y = 0.0)
        net.addNode(3, x = 40.0, y = 0.0)
        net.addNode(4, x = 60.0, y = 0.0)
        net.addNode(5, x = 0.0, y = 20.0)
        net.addNode(6, x = 20.0, y = 20.0)

        net.configureRadioRange(range = 22.0, loss = 0.2, latencyMs = 5)
        net.enableMobility(
            width = 80.0,
            height = 80.0,
            minSpeed = minSpeed,
            maxSpeed = maxSpeed,
            stepMs = 1_000,
        )
        return net
    }

    private fun mobilityRun(seed: Long): SimMetrics {
        val net = mobilityNetwork(seed = seed)
        repeat(12) { index ->
            net.originate(
                from = 1 + (index % 3),
                payload = ByteArray(4) { index.toByte() },
                at = index * 5_000L,
            )
        }
        net.runUntil(60_000)
        return net.metrics()
    }

    /** Current links as unordered node-id pairs, read from both transports. */
    private fun linkSet(net: SimNetwork): Set<Pair<Int, Int>> =
        net.nodeIds.flatMap { id ->
            net.node(id).transport.neighbors.value.mapNotNull { neighbor ->
                if (id < neighbor) id to neighbor else null
            }
        }.toSet()
}
