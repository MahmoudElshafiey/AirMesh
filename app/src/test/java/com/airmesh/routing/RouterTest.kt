package com.airmesh.routing

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameType
import kotlinx.coroutines.runBlocking
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
        store: PacketStore? = null,
        sendResult: (Int, Frame) -> Boolean = { _, _ -> true },
    ) {
        val sent = mutableListOf<Pair<Int, Frame>>()
        val delivered = mutableListOf<Frame>()

        val router = Router(
            localNodeId = localNodeId,
            dedup = DedupCache(capacity),
            neighbors = { neighborIds },
            send = { neighborId, frame ->
                sent += neighborId to frame
                sendResult(neighborId, frame)
            },
            deliver = { frame -> delivered += frame },
            initialTtl = initialTtl,
            packetStore = store,
        )

        fun receive(frame: Frame, fromNeighbor: Int) = runBlocking {
            router.onFrameReceived(frame, fromNeighbor)
        }

        fun originate(
            type: FrameType = FrameType.MSG,
            payload: ByteArray = ByteArray(0),
        ): Frame = runBlocking {
            router.originate(type, payload)
        }

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

        harness.receive(frame, fromNeighbor = 1)
        assertEquals(1, harness.delivered.size)
        assertEquals(setOf(2, 3), harness.sent.map { it.first }.toSet())

        harness.delivered.clear()
        harness.sent.clear()

        // The same (nodeId, seq) arrives again through another path, with a
        // lower TTL because the other path is shorter/longer. Still a duplicate.
        harness.receive(frame.copy(ttl = 4), fromNeighbor = 2)

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

        harness.receive(frame, fromNeighbor = 1)

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

        harness.receive(harness.frame(ttl = 1), fromNeighbor = 1)

        assertEquals(1, harness.delivered.size)
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun ttlZeroIsDeliveredButNotForwarded() {
        val harness = Harness(neighborIds = setOf(1, 2, 3))

        harness.receive(
            harness.frame(type = FrameType.MSG, seq = 1, ttl = 0),
            fromNeighbor = 1,
        )
        harness.receive(
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

        harness.receive(harness.frame(ttl = 3), fromNeighbor = 2)

        assertEquals(setOf(1, 3, 4), harness.sent.map { it.first }.toSet())
    }

    @Test
    fun originateUsesLocalNodeIdAndIncrementsSeq() {
        val harness = Harness(localNodeId = 42, neighborIds = setOf(1, 2), initialTtl = 8)
        val payload = "hi".toByteArray()

        val first = harness.originate(FrameType.MSG, payload)
        val second = harness.originate(FrameType.LOCATION, ByteArray(0))

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

        val frame = harness.originate(FrameType.MSG, "x".toByteArray())
        harness.delivered.clear()
        harness.sent.clear()

        // A neighbor floods our own frame back to us.
        harness.receive(frame.copy(ttl = frame.ttl - 1), fromNeighbor = 1)

        assertTrue(harness.delivered.isEmpty())
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun initialTtlIsConfigurable() {
        val harness = Harness(neighborIds = emptySet(), initialTtl = 3)

        val frame = harness.originate(FrameType.MSG, ByteArray(0))

        assertEquals(3, frame.ttl)
    }

    @Test
    fun seqWrapsAroundAfterUint16Max() {
        val harness = Harness(neighborIds = emptySet())

        var last: Frame? = null
        repeat(0xFFFF) {
            last = harness.originate(FrameType.MSG, ByteArray(0))
        }

        assertEquals(0xFFFF, last!!.seq)
        assertEquals(0, harness.originate(FrameType.MSG, ByteArray(0)).seq)
        assertEquals(1, harness.originate(FrameType.MSG, ByteArray(0)).seq)
    }

    @Test
    fun onlyMsgAndLocationAreDeliveredLocally() {
        val harness = Harness(neighborIds = emptySet())

        harness.receive(harness.frame(type = FrameType.MSG, seq = 1, ttl = 1), 1)
        harness.receive(harness.frame(type = FrameType.LOCATION, seq = 2, ttl = 1), 1)
        harness.receive(harness.frame(type = FrameType.ACK, seq = 3, ttl = 1), 1)
        harness.receive(harness.frame(type = FrameType.HELLO, seq = 4, ttl = 1), 1)

        assertEquals(
            listOf(FrameType.MSG, FrameType.LOCATION),
            harness.delivered.map { it.type },
        )
    }

    @Test
    fun nonDeliverableFrameIsStillForwardedWhenTtlAllows() {
        val harness = Harness(neighborIds = setOf(1, 2))

        harness.receive(
            harness.frame(type = FrameType.HELLO, seq = 7, ttl = 2),
            fromNeighbor = 1,
        )

        assertTrue(harness.delivered.isEmpty())
        assertEquals(1, harness.sent.size)
        assertEquals(2, harness.sent.single().first)
        assertEquals(1, harness.sent.single().second.ttl)
    }

    @Test
    fun isolatedOriginatedFrameIsStoredForLater() {
        val store = InMemoryPacketStore()
        val harness = Harness(neighborIds = emptySet(), store = store)

        val frame = harness.originate(FrameType.MSG, "later".toByteArray())

        val stored = store.all.single()
        assertEquals(frame.nodeId, stored.frame.nodeId)
        assertEquals(frame.seq, stored.frame.seq)
        assertEquals(frame.ttl, stored.frame.ttl)
        assertTrue(stored.deliveredToNeighbors.isEmpty())
    }

    @Test
    fun storeTimestampComesFromTheInjectedClock() = runBlocking {
        val store = InMemoryPacketStore()
        val router = Router(
            localNodeId = 1,
            dedup = DedupCache(),
            neighbors = { emptySet() },
            send = { _, _ -> true },
            packetStore = store,
            clock = { 12_345L },
        )

        router.originate(FrameType.MSG, "clock".toByteArray())

        assertEquals(12_345L, store.all.single().createdAt)
    }

    @Test
    fun receivedFrameWithOnlyItsSenderAsNeighborIsStored() {
        val store = InMemoryPacketStore()
        val harness = Harness(neighborIds = setOf(1), store = store)

        harness.receive(harness.frame(nodeId = 5, seq = 9, ttl = 5), fromNeighbor = 1)

        val stored = store.all.single()
        assertEquals(5, stored.frame.nodeId)
        assertEquals(9, stored.frame.seq)
        assertEquals(4, stored.frame.ttl)
        // The sender already has the frame, so replay must skip it.
        assertEquals(setOf(1), stored.deliveredToNeighbors)
    }

    @Test
    fun failedSendIsStoredAndSuccessfulNeighborsAreMarkedDelivered() {
        val store = InMemoryPacketStore()
        val harness = Harness(
            neighborIds = setOf(1, 2, 3),
            store = store,
            sendResult = { neighbor, _ -> neighbor != 3 },
        )

        harness.receive(harness.frame(nodeId = 5, seq = 11, ttl = 3), fromNeighbor = 1)

        val stored = store.all.single()
        assertEquals(2, stored.frame.ttl)
        // Source 1 and successful neighbor 2 are done; 3 must be retried.
        assertEquals(setOf(1, 2), stored.deliveredToNeighbors)
    }

    @Test
    fun fullyForwardedFrameIsNotStored() {
        val store = InMemoryPacketStore()
        val harness = Harness(neighborIds = setOf(2, 3), store = store)

        harness.receive(harness.frame(nodeId = 5, seq = 12, ttl = 4), fromNeighbor = 1)

        assertTrue(store.all.isEmpty())
    }

    @Test
    fun lastHopCopyIsNotStored() {
        val store = InMemoryPacketStore()
        val harness = Harness(neighborIds = setOf(1), store = store)

        // Forwarded TTL becomes 1: nobody can forward it further.
        harness.receive(harness.frame(nodeId = 5, seq = 13, ttl = 2), fromNeighbor = 1)

        assertTrue(store.all.isEmpty())
    }

    @Test
    fun concurrentOriginateAssignsEveryFrameAUniqueSeq() {
        val threadCount = 8
        val framesPerThread = 1_000
        val router = Router(
            localNodeId = 99,
            dedup = DedupCache(),
            neighbors = { emptySet() },
            send = { _, _ -> true },
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
                        frames.add(runBlocking { router.originate(FrameType.MSG, ByteArray(0)) })
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
                send = { _, _ -> true },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Router(
                localNodeId = 1,
                dedup = dedup,
                neighbors = { emptySet() },
                send = { _, _ -> true },
                initialTtl = 0,
            )
        }
    }
}
