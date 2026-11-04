package com.airmesh.routing

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StoreAndForwardTest {

    /** Wires a fake store, a recording send callback, and a manual clock. */
    private class Harness(
        maxAgeMs: Long = StoreAndForward.DEFAULT_MAX_AGE_MS,
        maxStoreSize: Int = StoreAndForward.DEFAULT_MAX_STORE_SIZE,
    ) {
        var now = 1_000_000L
        val store = InMemoryPacketStore()
        val sent = ConcurrentLinkedQueue<Pair<Int, Frame>>()
        val failedNeighbors: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        val sf = StoreAndForward(
            store = store,
            send = { neighborId, frame ->
                sent += neighborId to frame
                // A tiny pause widens the race window in the concurrency test;
                // no lock is held here by design.
                Thread.sleep(1)
                neighborId !in failedNeighbors
            },
            clock = { now },
            maxAgeMs = maxAgeMs,
            maxStoreSize = maxStoreSize,
        )

        fun frame(
            nodeId: Int = 1,
            seq: Int = 1,
            ttl: Int = 4,
            type: FrameType = FrameType.MSG,
            payload: String = "hello",
        ): Frame = Frame(
            version = 1,
            type = type,
            flags = 0,
            nodeId = nodeId,
            seq = seq,
            ttl = ttl,
            payload = payload.toByteArray(),
        )

        fun save(frame: Frame, deliveredToNeighbors: Set<Int> = emptySet()) = runBlocking {
            sf.save(frame, deliveredToNeighbors, createdAt = now)
        }

        fun neighborAppeared(neighborId: Int) = runBlocking {
            sf.onNeighborAppeared(neighborId)
        }

        fun isDelivered(nodeId: Int, seq: Int, neighborId: Int): Boolean = runBlocking {
            store.isDelivered(nodeId, seq, neighborId)
        }
    }

    @Test
    fun isolatedFrameIsStored() = runBlocking {
        val harness = Harness()
        val frame = harness.frame(seq = 1)

        harness.sf.save(frame, emptySet(), createdAt = harness.now)

        val pending = harness.store.pendingFor(neighborId = 7, notBefore = harness.now)
        assertEquals(1, pending.size)
        assertEquals(frame.nodeId, pending.single().frame.nodeId)
        assertEquals(frame.seq, pending.single().frame.seq)
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun storedFrameIsDeliveredWhenNeighborAppears() {
        val harness = Harness()
        val frame = harness.frame(seq = 3)
        harness.save(frame)

        harness.neighborAppeared(7)

        assertEquals(1, harness.sent.size)
        val (neighbor, sentFrame) = harness.sent.single()
        assertEquals(7, neighbor)
        assertEquals(frame.nodeId, sentFrame.nodeId)
        assertEquals(frame.seq, sentFrame.seq)
        assertTrue(harness.isDelivered(frame.nodeId, frame.seq, neighborId = 7))
    }

    @Test
    fun storedFrameIsNotDeliveredTwiceToTheSameNeighbor() {
        val harness = Harness()
        val frame = harness.frame(seq = 4)
        harness.save(frame)

        harness.neighborAppeared(7)
        harness.neighborAppeared(7)

        assertEquals(1, harness.sent.size)
        assertTrue(harness.isDelivered(frame.nodeId, frame.seq, neighborId = 7))
    }

    @Test
    fun differentNeighborsEachReceiveStoredFrameOnce() {
        val harness = Harness()
        val frame = harness.frame(seq = 5)
        harness.save(frame)

        harness.neighborAppeared(7)
        harness.neighborAppeared(8)
        harness.neighborAppeared(7)

        assertEquals(listOf(7, 8), harness.sent.map { it.first })
        assertTrue(harness.isDelivered(frame.nodeId, frame.seq, neighborId = 7))
        assertTrue(harness.isDelivered(frame.nodeId, frame.seq, neighborId = 8))
    }

    @Test
    fun expiredFrameIsRemovedAndNotDelivered() {
        val harness = Harness(maxAgeMs = 60_000)
        val frame = harness.frame(seq = 6)
        harness.save(frame)

        harness.now += 60_001
        harness.neighborAppeared(7)

        assertTrue(harness.sent.isEmpty())
        assertTrue(harness.store.all.isEmpty())
    }

    @Test
    fun expiredFramesArePrunedWhenANewFrameIsSaved() {
        val harness = Harness(maxAgeMs = 60_000)
        harness.save(harness.frame(seq = 1))

        harness.now += 60_001
        harness.save(harness.frame(seq = 2))

        assertEquals(listOf(2), harness.store.all.map { it.frame.seq })
    }

    @Test
    fun lastHopFramesAreNeitherStoredNorResent() {
        val harness = Harness()

        // The facade refuses to queue a frame that cannot be forwarded.
        harness.save(harness.frame(seq = 1, ttl = 1))
        assertTrue(harness.store.all.isEmpty())

        // Even if an older entry has TTL 1, replay must skip it.
        val lastHop = harness.frame(seq = 2, ttl = 1)
        runBlocking { harness.store.save(lastHop, emptySet(), harness.now) }
        harness.neighborAppeared(7)

        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun oldestFramesAreDroppedWhenStoreIsFull() {
        val harness = Harness(maxStoreSize = 2)

        harness.save(harness.frame(seq = 1))
        harness.now += 1
        harness.save(harness.frame(seq = 2))
        harness.now += 1
        harness.save(harness.frame(seq = 3))

        assertEquals(listOf(2, 3), harness.store.all.map { it.frame.seq })
    }

    @Test
    fun failedSendStaysQueuedAndIsRetried() {
        val harness = Harness()
        val frame = harness.frame(seq = 9)
        harness.save(frame)
        harness.failedNeighbors += 7

        harness.neighborAppeared(7)

        assertEquals(1, harness.sent.size)
        assertFalse(harness.isDelivered(frame.nodeId, frame.seq, neighborId = 7))

        harness.failedNeighbors -= 7
        harness.neighborAppeared(7)

        assertEquals(2, harness.sent.size)
        assertTrue(harness.isDelivered(frame.nodeId, frame.seq, neighborId = 7))
    }

    @Test
    fun concurrentNeighborAppearanceSendsEachFrameExactlyOnce() {
        val harness = Harness()
        val frameCount = 20
        repeat(frameCount) { index ->
            harness.save(harness.frame(nodeId = 1, seq = index + 1))
        }

        val threadCount = 8
        val callsPerThread = 10
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            val futures = (0 until threadCount).map {
                executor.submit {
                    // Release all threads together so every one of them reads
                    // the same pending snapshot and races for the claims.
                    start.await()
                    repeat(callsPerThread) {
                        runBlocking { harness.sf.onNeighborAppeared(7) }
                    }
                }
            }

            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val sendsPerFrame = harness.sent
            .filter { it.first == 7 }
            .groupingBy { it.second.seq }
            .eachCount()

        assertEquals((1..frameCount).toSet(), sendsPerFrame.keys)
        sendsPerFrame.forEach { (seq, count) ->
            assertEquals("frame $seq must be sent exactly once", 1, count)
        }
    }

    @Test
    fun rejectsInvalidPolicyValues() {
        val store = InMemoryPacketStore()
        val send: suspend (Int, Frame) -> Boolean = { _, _ -> true }

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            StoreAndForward(store, send, maxAgeMs = 0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            StoreAndForward(store, send, maxStoreSize = 0)
        }
    }
}
