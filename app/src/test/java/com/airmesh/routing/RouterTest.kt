package com.airmesh.routing

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RouterTest {

    /** Records every callback so tests can inspect routing decisions. */
    private class Harness(
        localNodeId: Int = 10,
        var neighborIds: Set<Int> = setOf(1, 2, 3),
        initialTtl: Int = Router.DEFAULT_TTL,
        capacity: Int = 64,
    ) {
        val sent = mutableListOf<Pair<Int, Frame>>()
        val delivered = mutableListOf<Frame>()

        val router = Router(
            localNodeId = localNodeId,
            dedup = DedupCache(capacity),
            neighbors = { neighborIds },
            send = { neighborId, frame -> sent += neighborId to frame },
            deliver = { frame -> delivered += frame },
            initialTtl = initialTtl,
        )

        fun frame(
            nodeId: Int = 5,
            seq: Int = 1,
            ttl: Int = 5,
            type: FrameType = FrameType.MSG,
            payload: ByteArray = "hello".toByteArray(),
        ): Frame = Frame(
            version = 1,
            type = type,
            flags = 0,
            nodeId = nodeId,
            seq = seq,
            ttl = ttl,
            payload = payload,
        )
    }

    @Test
    fun duplicateFrameIsDropped() {
        val harness = Harness()
        val frame = harness.frame(seq = 1, ttl = 5)

        harness.router.onFrameReceived(frame, fromNeighbor = 1)
        assertEquals(1, harness.delivered.size)
        assertEquals(setOf(2, 3), harness.sent.map { it.first }.toSet())

        harness.delivered.clear()
        harness.sent.clear()

        // The same (nodeId, seq) arrives again through another path, with a
        // lower TTL because the other path is shorter/longer. Still a duplicate.
        harness.router.onFrameReceived(frame.copy(ttl = 4), fromNeighbor = 2)

        assertTrue(harness.delivered.isEmpty())
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun forwardedFrameHasDecrementedTtlAndKeepsIdentity() {
        val harness = Harness(neighborIds = setOf(1, 2))
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val frame = harness.frame(
            nodeId = 77,
            seq = 9,
            ttl = 4,
            type = FrameType.LOCATION,
            payload = payload,
        )

        harness.router.onFrameReceived(frame, fromNeighbor = 1)

        // The local copy keeps its TTL; only the forwarded copy is decremented.
        assertEquals(1, harness.delivered.size)
        assertEquals(4, harness.delivered.single().ttl)

        assertEquals(1, harness.sent.size)
        val (neighbor, forwarded) = harness.sent.single()
        assertEquals(2, neighbor)
        assertEquals(3, forwarded.ttl)
        assertEquals(77, forwarded.nodeId)
        assertEquals(9, forwarded.seq)
        assertEquals(FrameType.LOCATION, forwarded.type)
        assertEquals(0, forwarded.flags)
        assertArrayEquals(payload, forwarded.payload)
    }

    @Test
    fun ttlOneIsDeliveredButNotForwarded() {
        val harness = Harness(neighborIds = setOf(1, 2, 3))

        harness.router.onFrameReceived(harness.frame(ttl = 1), fromNeighbor = 1)

        assertEquals(1, harness.delivered.size)
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun ttlZeroIsDeliveredButNotForwarded() {
        val harness = Harness(neighborIds = setOf(1, 2, 3))

        harness.router.onFrameReceived(
            harness.frame(type = FrameType.MSG, seq = 1, ttl = 0),
            fromNeighbor = 1,
        )
        harness.router.onFrameReceived(
            harness.frame(type = FrameType.LOCATION, seq = 2, ttl = 0),
            fromNeighbor = 1,
        )

        assertEquals(
            listOf(FrameType.MSG, FrameType.LOCATION),
            harness.delivered.map { it.type },
        )
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun senderIsExcludedFromForwarding() {
        val harness = Harness(neighborIds = setOf(1, 2, 3, 4))

        harness.router.onFrameReceived(harness.frame(ttl = 3), fromNeighbor = 2)

        assertEquals(setOf(1, 3, 4), harness.sent.map { it.first }.toSet())
    }

    @Test
    fun originateUsesLocalNodeIdAndIncrementsSeq() {
        val harness = Harness(localNodeId = 42, neighborIds = setOf(1, 2), initialTtl = 8)
        val payload = "hi".toByteArray()

        val first = harness.router.originate(FrameType.MSG, payload)
        val second = harness.router.originate(FrameType.LOCATION, ByteArray(0))

        assertEquals(42, first.nodeId)
        assertEquals(1, first.seq)
        assertEquals(8, first.ttl)
        assertEquals(FrameType.MSG, first.type)
        // Current wire version, stated independently of the Router constant.
        assertEquals(1, first.version)
        assertEquals(0, first.flags)
        assertArrayEquals(payload, first.payload)

        assertEquals(42, second.nodeId)
        assertEquals(2, second.seq)
        assertEquals(FrameType.LOCATION, second.type)

        // Each originate flooded every current neighbor.
        assertEquals(4, harness.sent.size)
        assertEquals(listOf(1, 2, 1, 2), harness.sent.map { it.first })
        assertArrayEquals(payload, harness.sent[0].second.payload)
    }

    @Test
    fun originatedFrameIsMarkedSeenSoItsEchoIsDropped() {
        val harness = Harness(neighborIds = setOf(1))

        val frame = harness.router.originate(FrameType.MSG, "x".toByteArray())
        harness.delivered.clear()
        harness.sent.clear()

        // A neighbor floods our own frame back to us.
        harness.router.onFrameReceived(frame.copy(ttl = frame.ttl - 1), fromNeighbor = 1)

        assertTrue(harness.delivered.isEmpty())
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun initialTtlIsConfigurable() {
        val harness = Harness(neighborIds = emptySet(), initialTtl = 3)

        val frame = harness.router.originate(FrameType.MSG, ByteArray(0))

        assertEquals(3, frame.ttl)
    }

    @Test
    fun seqWrapsAroundAfterUint16Max() {
        val harness = Harness(neighborIds = emptySet())

        var last: Frame? = null
        repeat(0xFFFF) {
            last = harness.router.originate(FrameType.MSG, ByteArray(0))
        }

        assertEquals(0xFFFF, last!!.seq)
        assertEquals(0, harness.router.originate(FrameType.MSG, ByteArray(0)).seq)
        assertEquals(1, harness.router.originate(FrameType.MSG, ByteArray(0)).seq)
    }

    @Test
    fun onlyMsgAndLocationAreDeliveredLocally() {
        val harness = Harness(neighborIds = emptySet())

        harness.router.onFrameReceived(harness.frame(type = FrameType.MSG, seq = 1, ttl = 1), 1)
        harness.router.onFrameReceived(harness.frame(type = FrameType.LOCATION, seq = 2, ttl = 1), 1)
        harness.router.onFrameReceived(harness.frame(type = FrameType.ACK, seq = 3, ttl = 1), 1)
        harness.router.onFrameReceived(harness.frame(type = FrameType.HELLO, seq = 4, ttl = 1), 1)

        assertEquals(
            listOf(FrameType.MSG, FrameType.LOCATION),
            harness.delivered.map { it.type },
        )
    }

    @Test
    fun nonDeliverableFrameIsStillForwardedWhenTtlAllows() {
        val harness = Harness(neighborIds = setOf(1, 2))

        harness.router.onFrameReceived(
            harness.frame(type = FrameType.HELLO, seq = 7, ttl = 2),
            fromNeighbor = 1,
        )

        assertTrue(harness.delivered.isEmpty())
        assertEquals(1, harness.sent.size)
        assertEquals(2, harness.sent.single().first)
        assertEquals(1, harness.sent.single().second.ttl)
    }

    @Test
    fun concurrentOriginateAssignsEveryFrameAUniqueSeq() {
        val threadCount = 8
        val framesPerThread = 1_000
        val router = Router(
            localNodeId = 99,
            dedup = DedupCache(),
            neighbors = { emptySet() },
            send = { _, _ -> },
        )
        val frames = ConcurrentLinkedQueue<Frame>()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            val futures = (0 until threadCount).map {
                executor.submit {
                    // Release all threads together to force concurrent
                    // originate() calls against the sequence counter.
                    start.await()
                    repeat(framesPerThread) {
                        frames.add(router.originate(FrameType.MSG, ByteArray(0)))
                    }
                }
            }

            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(threadCount * framesPerThread, frames.size)
        assertEquals(
            (1..(threadCount * framesPerThread)).toSet(),
            frames.map { it.seq }.toSet(),
        )
    }

    @Test
    fun rejectsInvalidConstructorArguments() {
        val dedup = DedupCache()

        assertThrows(IllegalArgumentException::class.java) {
            Router(
                localNodeId = 0x10000,
                dedup = dedup,
                neighbors = { emptySet() },
                send = { _, _ -> },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Router(
                localNodeId = 1,
                dedup = dedup,
                neighbors = { emptySet() },
                send = { _, _ -> },
                initialTtl = 0,
            )
        }
    }
}
