package com.airmesh.transport

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import com.airmesh.routing.DedupCache
import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.Router
import com.airmesh.routing.StoreAndForward
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM integration tests for [TcpMeshTransport] over real loopback sockets.
 *
 * Ports are never hard-coded: every listener binds port 0 and the transport
 * returns the port the OS picked. Nothing sleeps for a fixed amount of time to
 * "let things settle" either; every assertion waits on a condition with a
 * timeout through [awaitUntil].
 */
class TcpMeshTransportTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val RAW_PEER_ID = 99
        const val MAX_TEST_FRAME_SIZE = FrameCodec.MIN_FRAME_SIZE + FrameCodec.MAX_PAYLOAD

        /** Poll interval for condition checks; the timeout does the bounding. */
        const val POLL_MS = 5L
    }

    // ------------------------------------------------------------------
    // 1. Handshake: each side learns the other's node id.
    // ------------------------------------------------------------------

    @Test
    fun twoTransportsSeeEachOtherAsNeighbors() = runBlocking {
        val scope = testScope()
        val a = TestNode(id = 1, scope = scope)
        val b = TestNode(id = 2, scope = scope)

        try {
            val port = b.transport.listen(0)
            a.transport.connect(HOST, port)

            awaitUntil("A sees B as neighbor 2") {
                a.transport.neighbors.value == setOf(2)
            }
            awaitUntil("B sees A as neighbor 1") {
                b.transport.neighbors.value == setOf(1)
            }
        } finally {
            a.close()
            b.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 2. broadcast hands over the exact frame bytes, HELLO stays internal.
    // ------------------------------------------------------------------

    @Test
    fun broadcastDeliversExactBytesAndNeverLeaksHello() = runBlocking {
        val scope = testScope()
        val a = TestNode(id = 1, scope = scope)
        val b = TestNode(id = 2, scope = scope)
        val received = CopyOnWriteArrayList<ReceivedPacket>()

        try {
            // Subscribe before connecting so nothing can be missed.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                b.transport.incoming.collect { received += it }
            }

            val port = b.transport.listen(0)
            a.transport.connect(HOST, port)
            awaitUntil("both sides handshook") {
                a.transport.neighbors.value == setOf(2) &&
                    b.transport.neighbors.value == setOf(1)
            }

            val frame = Frame(
                version = 1,
                type = FrameType.MSG,
                flags = 0,
                nodeId = 1,
                seq = 1,
                ttl = 8,
                payload = "hello mesh".toByteArray(),
            )
            val bytes = FrameCodec.encode(frame)
            a.transport.broadcast(bytes)

            awaitUntil("B received the broadcast bytes") {
                received.any { it.bytes.contentEquals(bytes) }
            }

            val matching = received.filter { it.bytes.contentEquals(bytes) }
            assertEquals("expected exactly one copy", 1, matching.size)
            assertEquals("sender id must be A", 1, matching.single().senderId)

            val helloLeaked = received.any { packet ->
                FrameCodec.decode(packet.bytes).getOrNull()?.type == FrameType.HELLO
            }
            assertFalse("HELLO must never be exposed on incoming", helloLeaked)
        } finally {
            a.close()
            b.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 3. A corrupt frame is counted and dropped, the stream keeps working.
    // ------------------------------------------------------------------

    @Test
    fun corruptFrameIsDroppedAndValidFrameAfterItStillArrives() = runBlocking {
        val scope = testScope()
        val a = TestNode(id = 1, scope = scope)
        val received = CopyOnWriteArrayList<ReceivedPacket>()

        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                a.transport.incoming.collect { received += it }
            }

            val port = a.transport.listen(0)
            Socket().use { socket ->
                socket.soTimeout = 5_000
                socket.connect(InetSocketAddress(HOST, port), 5_000)

                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

                // A sends its HELLO immediately; answer it to complete the
                // handshake before injecting raw frames.
                val hello = readFrame(input)
                assertEquals(
                    FrameType.HELLO,
                    FrameCodec.decode(hello).getOrThrow().type,
                )
                writeFrame(output, helloBytes(RAW_PEER_ID))

                awaitUntil("A registered the raw peer") {
                    a.transport.neighbors.value == setOf(RAW_PEER_ID)
                }

                // Flip one payload bit: the CRC no longer matches.
                val corrupt = FrameCodec.encode(
                    Frame(1, FrameType.MSG, 0, RAW_PEER_ID, 7, 8, byteArrayOf(0x10, 0x20)),
                )
                corrupt[FrameCodec.HEADER_SIZE] =
                    (corrupt[FrameCodec.HEADER_SIZE].toInt() xor 0x01).toByte()

                val valid = FrameCodec.encode(
                    Frame(1, FrameType.MSG, 0, RAW_PEER_ID, 8, 8, byteArrayOf(0x30, 0x40)),
                )

                writeFrame(output, corrupt)
                writeFrame(output, valid)

                awaitUntil("the valid frame after the corrupt one arrived") {
                    received.any { it.bytes.contentEquals(valid) }
                }

                assertEquals(1, a.transport.droppedCorruptFrames)
                assertEquals(1, received.count { it.bytes.contentEquals(valid) })
                assertTrue(
                    "the corrupt frame must never be surfaced",
                    received.none { it.bytes.contentEquals(corrupt) },
                )
            }
        } finally {
            a.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 4. A closed socket removes the neighbor.
    // ------------------------------------------------------------------

    @Test
    fun closingPeerRemovesItFromNeighbors() = runBlocking {
        val scope = testScope()
        val a = TestNode(id = 1, scope = scope)
        val b = TestNode(id = 2, scope = scope)

        try {
            val port = b.transport.listen(0)
            a.transport.connect(HOST, port)
            awaitUntil("both sides handshook") {
                a.transport.neighbors.value == setOf(2) &&
                    b.transport.neighbors.value == setOf(1)
            }

            b.transport.stop()

            awaitUntil("A drops B after its socket closes") {
                a.transport.neighbors.value.isEmpty()
            }
            assertTrue(a.transport.neighbors.value.isEmpty())
        } finally {
            a.close()
            b.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 5. Hub topology: clients do not see each other.
    // ------------------------------------------------------------------

    @Test
    fun hubFanOutReachesOnlyTheHub() = runBlocking {
        val scope = testScope()
        val a = TestNode(id = 1, scope = scope)
        val b = TestNode(id = 2, scope = scope)
        val c = TestNode(id = 3, scope = scope)
        val bReceived = CopyOnWriteArrayList<ReceivedPacket>()
        val cReceived = CopyOnWriteArrayList<ReceivedPacket>()

        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                b.transport.incoming.collect { bReceived += it }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                c.transport.incoming.collect { cReceived += it }
            }

            val port = b.transport.listen(0)
            a.transport.connect(HOST, port)
            awaitUntil("A joined the hub") {
                b.transport.neighbors.value == setOf(1)
            }

            c.transport.connect(HOST, port)
            awaitUntil("A and C are both attached to B") {
                b.transport.neighbors.value == setOf(1, 3) &&
                    a.transport.neighbors.value == setOf(2) &&
                    c.transport.neighbors.value == setOf(2)
            }

            val bytes = FrameCodec.encode(
                Frame(1, FrameType.MSG, 0, 1, 1, 8, "hub fan-out".toByteArray()),
            )
            a.transport.broadcast(bytes)

            awaitUntil("B received A's broadcast") {
                bReceived.any { it.bytes.contentEquals(bytes) }
            }
            assertEquals(1, bReceived.count { it.bytes.contentEquals(bytes) })

            val reachedC = awaitUntilOrNull(1_000) {
                cReceived.any { it.bytes.contentEquals(bytes) }
            }
            assertFalse("A must not reach C through the hub", reachedC)
            assertFalse(
                "HELLO must never be exposed on incoming",
                cReceived.any { packet ->
                    FrameCodec.decode(packet.bytes).getOrNull()?.type == FrameType.HELLO
                },
            )
        } finally {
            a.close()
            b.close()
            c.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 6. End-to-end: real Router + StoreAndForward over real TCP sockets.
    // ------------------------------------------------------------------

    @Test
    fun routerAndStoreAndForwardWorkOverRealTcp() = runBlocking {
        val scope = testScope()
        val a = TestNode(id = 1, scope = scope)
        val b = TestNode(id = 2, scope = scope)
        val c = TestNode(id = 3, scope = scope)

        try {
            val port = b.transport.listen(0)
            a.transport.connect(HOST, port)
            awaitUntil("A-B link is up") {
                a.transport.neighbors.value == setOf(2) &&
                    b.transport.neighbors.value == setOf(1)
            }

            c.transport.connect(HOST, port)
            awaitUntil("A-B-C hub links are up") {
                b.transport.neighbors.value == setOf(1, 3) &&
                    a.transport.neighbors.value == setOf(2) &&
                    c.transport.neighbors.value == setOf(2)
            }

            // A originates MSG #1 and it floods through the hub to C.
            val first = a.router.originate(FrameType.MSG, "first".toByteArray())
            assertEquals(1, first.seq)

            awaitUntil("B and C each deliver MSG #1 once") {
                b.deliveredCount(1, 1) == 1 && c.deliveredCount(1, 1) == 1
            }
            assertEquals(
                "the originator must not deliver its own frame",
                0,
                a.deliveredCount(1, 1),
            )

            // Take C offline. B now has no other neighbor to forward to, so
            // the next message has to be stored.
            c.transport.stop()
            awaitUntil("B notices that C is gone") {
                b.transport.neighbors.value == setOf(1)
            }

            val second = a.router.originate(FrameType.MSG, "second".toByteArray())
            assertEquals(2, second.seq)

            awaitUntil("B stored MSG #2 for later delivery") {
                b.packetStore.all.any { it.frame.nodeId == 1 && it.frame.seq == 2 }
            }

            // C reconnects; the hub replays what it stored.
            c.transport.connect(HOST, port)
            awaitUntil("C is back on the hub") {
                b.transport.neighbors.value == setOf(1, 3) &&
                    c.transport.neighbors.value == setOf(2)
            }
            awaitUntil("C receives MSG #2 from store-and-forward") {
                c.deliveredCount(1, 2) == 1
            }

            // Each frame was delivered exactly once, and never to its author.
            assertEquals(1, b.deliveredCount(1, 1))
            assertEquals(1, c.deliveredCount(1, 1))
            assertEquals(1, b.deliveredCount(1, 2))
            assertEquals(0, a.deliveredCount(1, 2))
        } finally {
            a.close()
            b.close()
            c.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Test harness
    // ------------------------------------------------------------------

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One mesh node: the real transport plus the same routing wiring the
     * simulator uses in `SimNode`.
     *
     * The neighbor watcher mirrors `SimNetwork.applyLinks`: only added ids get
     * a store-and-forward replay, so a reconnect triggers exactly one replay.
     */
    private class TestNode(
        val id: Int,
        scope: CoroutineScope,
    ) {

        private companion object {
            const val TTL = 8
        }

        val transport = TcpMeshTransport(localNodeId = id)
        val packetStore = InMemoryPacketStore()
        val delivered = CopyOnWriteArrayList<Frame>()

        private val dedup = DedupCache()
        private val jobs = mutableListOf<Job>()

        val storeAndForward: StoreAndForward
        val router: Router

        init {
            // Replay path: a stored frame is only sent when the neighbor is
            // currently reachable, so a failed attempt stays queued.
            storeAndForward = StoreAndForward(
                store = packetStore,
                send = { neighborId, frame -> sendFrame(neighborId, frame) },
            )

            router = Router(
                localNodeId = id,
                dedup = dedup,
                neighbors = { transport.neighbors.value },
                send = { neighborId, frame -> sendFrame(neighborId, frame) },
                deliver = { frame -> delivered += frame },
                initialTtl = TTL,
                packetStore = storeAndForward,
            )

            jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.incoming.collect { packet ->
                    FrameCodec.decode(packet.bytes).onSuccess { frame ->
                        router.onFrameReceived(frame, packet.senderId)
                    }
                }
            }

            var previous = emptySet<Int>()
            jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.neighbors.collect { current ->
                    val added = current - previous
                    previous = current
                    for (neighbor in added.sorted()) {
                        storeAndForward.onNeighborAppeared(neighbor)
                    }
                }
            }
        }

        suspend fun sendFrame(neighborId: Int, frame: Frame): Boolean {
            // Transport.send has no return value, so report a reachable
            // neighbor as a successful hand-off, matching SimTransport.
            if (neighborId !in transport.neighbors.value) return false
            transport.send(neighborId, FrameCodec.encode(frame))
            return true
        }

        fun deliveredCount(nodeId: Int, seq: Int): Int =
            delivered.count { it.nodeId == nodeId && it.seq == seq }

        fun close() {
            jobs.forEach { it.cancel() }
            transport.stop()
        }
    }

    /** Waits for [condition], failing the test instead of hanging. */
    private suspend fun awaitUntil(
        description: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(timeoutMs) {
                while (!condition()) delay(POLL_MS)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("timed out after ${timeoutMs}ms waiting for $description", timeout)
        }
    }

    /** Bounded negative wait: true when [condition] became true in time. */
    private suspend fun awaitUntilOrNull(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!condition()) delay(POLL_MS)
        true
    } ?: false

    private fun helloBytes(nodeId: Int): ByteArray = FrameCodec.encode(
        Frame(
            version = 1,
            type = FrameType.HELLO,
            flags = 0,
            nodeId = nodeId,
            seq = 0,
            ttl = 1,
            payload = ByteArray(0),
        ),
    )

    private fun readFrame(input: DataInputStream): ByteArray {
        val high = input.read()
        val low = input.read()
        check(high >= 0 && low >= 0) { "stream ended inside a frame length prefix" }
        val size = (high shl 8) or low
        check(size in 1..MAX_TEST_FRAME_SIZE) { "invalid frame length: $size" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return bytes
    }

    private fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
        output.writeShort(bytes.size)
        output.write(bytes)
        output.flush()
    }
}
