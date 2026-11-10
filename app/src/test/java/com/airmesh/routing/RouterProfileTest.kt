package com.airmesh.routing

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that PROFILE frames are routed exactly like MSG and LOCATION: delivered
 * locally once, forwarded with ttl-1, deduplicated, and stored for replay when a
 * hop cannot reach every neighbor. HELLO and ACK must keep their protocol-only
 * behavior (never delivered to the app).
 */
class RouterProfileTest {

    /** Records every callback so tests can inspect routing decisions. */
    private class Harness(
        localNodeId: Int = 10,
        var neighborIds: Set<Int> = setOf(1, 2, 3),
        initialTtl: Int = Router.DEFAULT_TTL,
        capacity: Int = 64,
        store: PacketStore? = null,
    ) {
        val sent = mutableListOf<Pair<Int, Frame>>()
        val delivered = mutableListOf<Frame>()

        val router = Router(
            localNodeId = localNodeId,
            dedup = DedupCache(capacity),
            neighbors = { neighborIds },
            send = { neighborId, frame ->
                sent += neighborId to frame
                true
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
            type: FrameType = FrameType.PROFILE,
            payload: ByteArray = "profile".toByteArray(),
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
    fun profileFrameIsDeliveredLocallyOnce() {
        val harness = Harness(neighborIds = setOf(1, 2))
        val payload = byteArrayOf(0x01, 0x02)
        val frame = harness.frame(nodeId = 77, seq = 9, ttl = 4, payload = payload)

        harness.receive(frame, fromNeighbor = 1)

        // Delivered exactly once, with the original TTL and intact identity.
        assertEquals(1, harness.delivered.size)
        val delivered = harness.delivered.single()
        assertEquals(FrameType.PROFILE, delivered.type)
        assertEquals(4, delivered.ttl)
        assertEquals(77, delivered.nodeId)
        assertEquals(9, delivered.seq)
        assertArrayEquals(payload, delivered.payload)
    }

    @Test
    fun profileFrameIsForwardedWithDecrementedTtl() {
        val harness = Harness(neighborIds = setOf(1, 2))
        val frame = harness.frame(nodeId = 77, seq = 9, ttl = 4)

        harness.receive(frame, fromNeighbor = 1)

        // The local copy keeps its TTL; only the forwarded copy is decremented.
        assertEquals(1, harness.delivered.size)
        assertEquals(4, harness.delivered.single().ttl)

        // Forwarded to every neighbor except the sender, with ttl - 1.
        assertEquals(1, harness.sent.size)
        val (neighbor, forwarded) = harness.sent.single()
        assertEquals(2, neighbor)
        assertEquals(FrameType.PROFILE, forwarded.type)
        assertEquals(3, forwarded.ttl)
        assertEquals(77, forwarded.nodeId)
        assertEquals(9, forwarded.seq)
    }

    @Test
    fun duplicateProfileFrameIsDropped() {
        val harness = Harness()
        val frame = harness.frame(seq = 1, ttl = 5)

        harness.receive(frame, fromNeighbor = 1)
        assertEquals(1, harness.delivered.size)
        assertEquals(setOf(2, 3), harness.sent.map { it.first }.toSet())

        harness.delivered.clear()
        harness.sent.clear()

        // Same (nodeId, seq) arrives again via another path. Still a duplicate.
        harness.receive(frame.copy(ttl = 4), fromNeighbor = 2)

        assertTrue(harness.delivered.isEmpty())
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun isolatedProfileFrameIsStoredForReplay() {
        val store = InMemoryPacketStore()
        // Only the sender is reachable, so this hop cannot forward anywhere new.
        val harness = Harness(neighborIds = setOf(1), store = store)

        harness.receive(harness.frame(nodeId = 5, seq = 9, ttl = 5), fromNeighbor = 1)

        // Delivered locally and stored for replay to the missing neighbors.
        assertEquals(1, harness.delivered.size)
        val stored = store.all.single()
        assertEquals(FrameType.PROFILE, stored.frame.type)
        assertEquals(5, stored.frame.nodeId)
        assertEquals(9, stored.frame.seq)
        assertEquals(4, stored.frame.ttl)
        // The sender already has the frame, so replay must skip it.
        assertEquals(setOf(1), stored.deliveredToNeighbors)
    }

    @Test
    fun originatedProfileFrameIsStoredWhenIsolated() {
        val store = InMemoryPacketStore()
        val harness = Harness(neighborIds = emptySet(), store = store)

        val frame = harness.originate(FrameType.PROFILE, "later".toByteArray())

        assertEquals(FrameType.PROFILE, frame.type)
        val stored = store.all.single()
        assertEquals(frame.nodeId, stored.frame.nodeId)
        assertEquals(frame.seq, stored.frame.seq)
        assertEquals(frame.ttl, stored.frame.ttl)
        assertTrue(stored.deliveredToNeighbors.isEmpty())
    }

    @Test
    fun helloAndAckBehaviorIsUnchanged() {
        val harness = Harness(neighborIds = setOf(1, 2))

        // HELLO: still protocol traffic (not delivered), but forwarded when ttl allows.
        harness.receive(harness.frame(type = FrameType.HELLO, seq = 7, ttl = 2), fromNeighbor = 1)
        assertTrue(harness.delivered.isEmpty())
        assertEquals(1, harness.sent.size)
        val helloForwarded = harness.sent.single()
        assertEquals(2, helloForwarded.first)
        assertEquals(FrameType.HELLO, helloForwarded.second.type)
        assertEquals(1, helloForwarded.second.ttl)

        // ACK: protocol traffic and ttl 1, so neither delivered nor forwarded.
        harness.delivered.clear()
        harness.sent.clear()
        harness.receive(harness.frame(type = FrameType.ACK, seq = 8, ttl = 1), fromNeighbor = 1)
        assertTrue(harness.delivered.isEmpty())
        assertTrue(harness.sent.isEmpty())

        // PROFILE in the same router is still delivered locally.
        harness.delivered.clear()
        harness.receive(harness.frame(type = FrameType.PROFILE, seq = 9, ttl = 1), fromNeighbor = 1)
        assertEquals(1, harness.delivered.size)
        assertEquals(FrameType.PROFILE, harness.delivered.single().type)
    }
}
