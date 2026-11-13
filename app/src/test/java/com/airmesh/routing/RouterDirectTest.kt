package com.airmesh.routing

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Router tests for DIRECT/DIRECT_ACK delivery and FramePolicy vetoes (TASK D1 part 2). */
class RouterDirectTest {

    /** Records every callback so tests can inspect routing decisions. */
    private class Harness(
        localNodeId: Int = 10,
        var neighborIds: Set<Int> = setOf(1, 2),
        store: PacketStore? = null,
        policy: FramePolicy = FramePolicy.Default,
        sendResult: (Int, Frame) -> Boolean = { _, _ -> true },
    ) {
        val sent = mutableListOf<Pair<Int, Frame>>()
        val delivered = mutableListOf<Frame>()

        val router = Router(
            localNodeId = localNodeId,
            dedup = DedupCache(),
            neighbors = { neighborIds },
            send = { neighborId, frame ->
                sent += neighborId to frame
                sendResult(neighborId, frame)
            },
            deliver = { frame -> delivered += frame },
            packetStore = store,
            policy = policy,
        )

        fun receive(frame: Frame, fromNeighbor: Int) = runBlocking {
            router.onFrameReceived(frame, fromNeighbor)
        }

        fun originate(type: FrameType, payload: ByteArray): Frame = runBlocking {
            router.originate(type, payload)
        }

        fun frame(
            nodeId: Int = 5,
            seq: Int = 1,
            ttl: Int = 3,
            type: FrameType = FrameType.DIRECT,
            payload: ByteArray = "dm".toByteArray(),
        ): Frame = Frame(version = 1, type = type, flags = 0, nodeId = nodeId, seq = seq, ttl = ttl, payload = payload)
    }

    /** Forwards everything but never stores frames of [types]. */
    private class NoStorePolicy(private val types: Set<FrameType>) : FramePolicy {
        override fun shouldStore(frame: Frame): Boolean = frame.type !in types
    }

    /** Never forwards or stores frames of [types]; they stay local only. */
    private class LocalOnlyPolicy(private val types: Set<FrameType>) : FramePolicy {
        override fun shouldForward(frame: Frame): Boolean = frame.type !in types
        override fun shouldStore(frame: Frame): Boolean = frame.type !in types
    }

    @Test
    fun directAndDirectAckAreDeliveredLocallyOnce() {
        val harness = Harness(neighborIds = setOf(1, 2))

        harness.receive(harness.frame(type = FrameType.DIRECT, seq = 1), fromNeighbor = 1)
        assertEquals(listOf(FrameType.DIRECT), harness.delivered.map { it.type })
        // Like MSG: forwarded to the other neighbor with a decremented TTL.
        assertEquals(1, harness.sent.size)
        assertEquals(2, harness.sent.single().first)
        assertEquals(2, harness.sent.single().second.ttl)

        harness.delivered.clear()
        harness.sent.clear()

        // The same (nodeId, seq) arriving again is a duplicate: no second delivery.
        harness.receive(harness.frame(type = FrameType.DIRECT, seq = 1), fromNeighbor = 2)
        assertTrue(harness.delivered.isEmpty())
        assertTrue(harness.sent.isEmpty())

        harness.receive(harness.frame(type = FrameType.DIRECT_ACK, seq = 2), fromNeighbor = 1)
        assertEquals(listOf(FrameType.DIRECT_ACK), harness.delivered.map { it.type })
        assertEquals(1, harness.sent.size)
    }

    @Test
    fun defaultPolicyTreatsDirectLikeMsgForStorage() {
        val store = InMemoryPacketStore()
        // Only the sender is a neighbor: exactly the case that stores under the
        // historical behavior (mirrors receivedFrameWithOnlyItsSenderAsNeighborIsStored).
        val harness = Harness(neighborIds = setOf(1), store = store)

        harness.receive(harness.frame(nodeId = 5, seq = 9, ttl = 5), fromNeighbor = 1)

        assertEquals(1, harness.delivered.size)
        val stored = store.all.single()
        assertEquals(FrameType.DIRECT, stored.frame.type)
        assertEquals(4, stored.frame.ttl)
        assertEquals(setOf(1), stored.deliveredToNeighbors)
    }

    @Test
    fun defaultPolicyForwardsAndStoresEverything() {
        val frame = Frame(1, FrameType.DIRECT_ACK, 0, 5, 1, 3, "x".toByteArray())
        assertTrue(FramePolicy.Default.shouldForward(frame))
        assertTrue(FramePolicy.Default.shouldStore(frame))
    }

    @Test
    fun forwardVetoStopsForwardingAndStorageButStillDelivers() {
        val store = InMemoryPacketStore()
        // The node policy shape: a DIRECT addressed to me is local-only.
        val harness = Harness(neighborIds = setOf(1, 2), store = store, policy = LocalOnlyPolicy(setOf(FrameType.DIRECT)))

        harness.receive(harness.frame(nodeId = 5, seq = 1, ttl = 5), fromNeighbor = 1)

        assertEquals(listOf(FrameType.DIRECT), harness.delivered.map { it.type })
        assertTrue("a vetoed frame is not forwarded", harness.sent.isEmpty())
        assertTrue("a vetoed frame is not stored either", store.all.isEmpty())
    }

    @Test
    fun forwardVetoOnOriginateSkipsTheFloodAndTheStore() {
        val store = InMemoryPacketStore()
        // A self-addressed DM would be vetoed: no flood, nothing queued.
        val harness = Harness(neighborIds = setOf(1, 2), store = store, policy = LocalOnlyPolicy(setOf(FrameType.DIRECT)))

        val frame = harness.originate(FrameType.DIRECT, "to me".toByteArray())

        assertEquals(10, frame.nodeId)
        assertEquals(1, frame.seq)
        assertTrue(harness.sent.isEmpty())
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun storeVetoDropsReceivedFrameInsteadOfStoring() {
        val store = InMemoryPacketStore()
        // The send to neighbor 2 fails: under the default policy this frame
        // would be stored with deliveredTo={1}. The veto drops it instead.
        val harness = Harness(
            neighborIds = setOf(1, 2),
            store = store,
            policy = NoStorePolicy(setOf(FrameType.DIRECT)),
            sendResult = { neighbor, _ -> neighbor != 2 },
        )

        harness.receive(harness.frame(nodeId = 5, seq = 9, ttl = 5), fromNeighbor = 1)

        assertEquals(1, harness.delivered.size)
        // Forwarding still happened (the attempt to neighbor 2 is recorded)...
        assertEquals(listOf(2), harness.sent.map { it.first })
        // ...but the frame was dropped instead of queued.
        assertTrue(store.all.isEmpty())
    }

    @Test
    fun storeVetoDropsOriginatedFrameInsteadOfStoring() {
        val store = InMemoryPacketStore()
        // No neighbors: an isolated originated frame is normally stored for later.
        val harness = Harness(neighborIds = emptySet(), store = store, policy = NoStorePolicy(setOf(FrameType.DIRECT)))

        val frame = harness.originate(FrameType.DIRECT, "later".toByteArray())

        assertEquals(1, frame.seq)
        assertTrue("the vetoed frame is not queued", store.all.isEmpty())
    }
}
