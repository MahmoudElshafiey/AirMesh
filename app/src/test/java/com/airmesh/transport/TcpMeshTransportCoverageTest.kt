package com.airmesh.transport

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOST = "127.0.0.1"
private const val POLL_MS = 5L
private const val MAX_FRAME_SIZE = FrameCodec.MIN_FRAME_SIZE + FrameCodec.MAX_PAYLOAD
private const val IO_TIMEOUT_MS = 5_000L

/**
 * Coverage tests for the [TcpMeshTransport] paths that the happy-path tests do
 * not touch: malformed framing, duplicate node ids, failed writes, two-way
 * hub/client operation, and lifecycle restarts.
 *
 * Every listener binds port 0 and uses the returned port, and every assertion
 * waits on a condition with a timeout instead of sleeping for a fixed time.
 */
class TcpMeshTransportCoverageTest {

    // ------------------------------------------------------------------
    // 1. A malformed length prefix kills only that socket.
    // ------------------------------------------------------------------

    @Test
    fun malformedLengthPrefixClosesOnlyThatSocket() = runBlocking {
        val scope = testScope()
        val hub = Endpoint(id = 1, scope = scope)
        val good = Endpoint(id = 2, scope = scope)
        var zero: RawSocket? = null
        var oversized: RawSocket? = null

        try {
            val port = hub.transport.listen(0)
            good.transport.connect(HOST, port)
            awaitUntil("the good neighbor is connected") {
                hub.transport.neighbors.value == setOf(2) &&
                    good.transport.neighbors.value == setOf(1)
            }

            // --- length 0 ---
            val zeroPeer = connectRaw(port, nodeId = 90)
            zero = zeroPeer
            awaitUntil("the zero-length peer handshook") {
                hub.transport.neighbors.value == setOf(2, 90)
            }
            zeroPeer.writeLength(0)

            awaitUntil("the zero-length peer was removed") {
                hub.transport.neighbors.value == setOf(2)
            }
            assertServerClosed(zeroPeer)
            assertStillServing(hub, good, seq = 1)

            // --- length above the maximum ---
            val oversizedPeer = connectRaw(port, nodeId = 91)
            oversized = oversizedPeer
            awaitUntil("the oversized peer handshook") {
                hub.transport.neighbors.value == setOf(2, 91)
            }
            oversizedPeer.writeLength(MAX_FRAME_SIZE + 1)

            awaitUntil("the oversized peer was removed") {
                hub.transport.neighbors.value == setOf(2)
            }
            assertServerClosed(oversizedPeer)
            assertStillServing(hub, good, seq = 2)

            assertTrue(hub.transport.neighbors.value == setOf(2))
        } finally {
            zero?.close()
            oversized?.close()
            hub.close()
            good.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 2. The newest socket for a node id replaces the old one.
    // ------------------------------------------------------------------

    @Test
    fun duplicateNodeIdKeepsTheNewestSocket() = runBlocking {
        val scope = testScope()
        val hub = Endpoint(id = 2, scope = scope)
        val original = Endpoint(id = 1, scope = scope)
        var duplicate: RawSocket? = null

        try {
            val port = hub.transport.listen(0)
            original.transport.connect(HOST, port)
            awaitUntil("the original A-B link is up") {
                hub.transport.neighbors.value == setOf(1) &&
                    original.transport.neighbors.value == setOf(2)
            }

            // The raw socket announces A's id as well. Registering it closes
            // the original socket, which the original side observes.
            val duplicatePeer = connectRaw(port, nodeId = 1)
            duplicate = duplicatePeer
            awaitUntil("the original socket was closed after the duplicate handshake") {
                original.transport.neighbors.value.isEmpty()
            }
            assertEquals(setOf(1), hub.transport.neighbors.value)

            val bytes = FrameCodec.encode(
                Frame(1, FrameType.MSG, 0, 2, 1, 8, "newest wins".toByteArray()),
            )
            hub.transport.broadcast(bytes)

            val delivered = readFrameWithTimeout(duplicatePeer)
            assertArrayEquals(
                "the newest socket must receive the broadcast",
                bytes,
                delivered,
            )

            // The evicted socket must not have received the frame.
            assertTrue(
                "the closed original socket must not receive data",
                original.received.none { it.bytes.contentEquals(bytes) },
            )

            // The stale connection must not delete the new mapping.
            val hubLostNeighbor = awaitUntilOrNull(300) {
                hub.transport.neighbors.value.isEmpty()
            }
            assertFalse("the old connection removed the new neighbor", hubLostNeighbor)
            assertEquals(setOf(1), hub.transport.neighbors.value)
        } finally {
            duplicate?.close()
            hub.close()
            original.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 3. A write to an abruptly reset socket removes that neighbor only.
    // ------------------------------------------------------------------

    @Test
    fun failedWriteRemovesTheNeighborAndOthersStillReceive() = runBlocking {
        val scope = testScope()
        val hub = Endpoint(id = 1, scope = scope)
        val good = Endpoint(id = 2, scope = scope)
        var resetted: RawSocket? = null

        try {
            val port = hub.transport.listen(0)
            good.transport.connect(HOST, port)
            awaitUntil("the good neighbor is connected") {
                hub.transport.neighbors.value == setOf(2) &&
                    good.transport.neighbors.value == setOf(1)
            }

            val resetPeer = connectRaw(port, nodeId = 77)
            resetted = resetPeer
            awaitUntil("the soon-to-reset neighbor handshook") {
                hub.transport.neighbors.value == setOf(2, 77)
            }

            // SO_LINGER with a zero timeout makes close() send a TCP RST
            // instead of a clean FIN.
            resetPeer.socket.setSoLinger(true, 0)
            resetPeer.close()
            resetted = null

            // Broadcast and unicast while the dead peer may still be mapped.
            val first = FrameCodec.encode(
                Frame(1, FrameType.MSG, 0, 1, 1, 8, "after reset".toByteArray()),
            )
            hub.transport.broadcast(first)
            hub.transport.send(77, first)

            awaitUntil("the reset neighbor disappeared") {
                hub.transport.neighbors.value == setOf(2)
            }

            awaitUntil("the surviving neighbor got the first frame") {
                good.received.any { it.bytes.contentEquals(first) }
            }
            assertEquals(1, good.received.count { it.bytes.contentEquals(first) })

            val second = FrameCodec.encode(
                Frame(1, FrameType.MSG, 0, 1, 2, 8, "still serving".toByteArray()),
            )
            hub.transport.broadcast(second)
            awaitUntil("the surviving neighbor got the second frame") {
                good.received.any { it.bytes.contentEquals(second) }
            }
            assertEquals(1, good.received.count { it.bytes.contentEquals(second) })
        } finally {
            resetted?.close()
            hub.close()
            good.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 4. One broadcast reaches both hub clients with identical bytes once.
    // ------------------------------------------------------------------

    @Test
    fun hubBroadcastReachesBothClientsExactlyOnce() = runBlocking {
        val scope = testScope()
        val hub = Endpoint(id = 2, scope = scope)
        val a = Endpoint(id = 1, scope = scope)
        val c = Endpoint(id = 3, scope = scope)

        try {
            val port = hub.transport.listen(0)
            a.transport.connect(HOST, port)
            awaitUntil("A is attached to the hub") {
                hub.transport.neighbors.value == setOf(1)
            }
            c.transport.connect(HOST, port)
            awaitUntil("both clients are attached to the hub") {
                hub.transport.neighbors.value == setOf(1, 3) &&
                    a.transport.neighbors.value == setOf(2) &&
                    c.transport.neighbors.value == setOf(2)
            }

            val bytes = FrameCodec.encode(
                Frame(1, FrameType.MSG, 0, 2, 1, 8, "fan out".toByteArray()),
            )
            hub.transport.broadcast(bytes)

            awaitUntil("both clients received the broadcast") {
                a.received.any { it.bytes.contentEquals(bytes) } &&
                    c.received.any { it.bytes.contentEquals(bytes) }
            }

            val aCopies = a.received.filter { it.bytes.contentEquals(bytes) }
            val cCopies = c.received.filter { it.bytes.contentEquals(bytes) }
            assertEquals(1, aCopies.size)
            assertEquals(1, cCopies.size)
            assertEquals(2, aCopies.single().senderId)
            assertEquals(2, cCopies.single().senderId)
            assertArrayEquals(aCopies.single().bytes, cCopies.single().bytes)
        } finally {
            hub.close()
            a.close()
            c.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 5. One transport can be a hub and a client; it never auto-forwards.
    // ------------------------------------------------------------------

    @Test
    fun hubAndClientNodeSeesBothSidesAndDoesNotForward() = runBlocking {
        val scope = testScope()
        val relay = Endpoint(id = 1, scope = scope)
        val second = Endpoint(id = 2, scope = scope)
        val client = Endpoint(id = 3, scope = scope)

        try {
            val secondPort = second.transport.listen(0)
            val relayPort = relay.transport.listen(0)

            relay.transport.connect(HOST, secondPort)
            client.transport.connect(HOST, relayPort)

            awaitUntil("the relay sees both its neighbors") {
                relay.transport.neighbors.value == setOf(2, 3)
            }
            awaitUntil("each peer sees the relay") {
                second.transport.neighbors.value == setOf(1) &&
                    client.transport.neighbors.value == setOf(1)
            }

            val bytes = FrameCodec.encode(
                Frame(1, FrameType.MSG, 0, 2, 1, 8, "not forwarded".toByteArray()),
            )
            second.transport.broadcast(bytes)

            awaitUntil("the relay received the frame from its upstream") {
                relay.received.any { it.bytes.contentEquals(bytes) }
            }

            // The transport is link-level only: without an explicit forward by
            // the layer above, the hub's client must not see it.
            val leaked = awaitUntilOrNull(500) {
                client.received.any { it.bytes.contentEquals(bytes) }
            }
            assertFalse("the transport forwarded a frame on its own", leaked)

            // Explicit forwarding by the layer above is what makes it travel.
            relay.transport.broadcast(bytes)
            awaitUntil("the client received the explicitly forwarded frame") {
                client.received.any { it.bytes.contentEquals(bytes) }
            }
            assertEquals(1, client.received.count { it.bytes.contentEquals(bytes) })
        } finally {
            relay.close()
            second.close()
            client.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 6. start() is idempotent and stop() + start() can listen again.
    // ------------------------------------------------------------------

    @Test
    fun startTwiceAndRestartAfterStop() = runBlocking {
        val scope = testScope()
        val restarted = Endpoint(id = 1, scope = scope)
        val firstClient = Endpoint(id = 2, scope = scope)
        val secondClient = Endpoint(id = 3, scope = scope)

        try {
            restarted.transport.start()
            restarted.transport.start()

            val firstPort = restarted.transport.listen(0)
            firstClient.transport.connect(HOST, firstPort)
            awaitUntil("the first listener works") {
                restarted.transport.neighbors.value == setOf(2) &&
                    firstClient.transport.neighbors.value == setOf(1)
            }

            restarted.transport.stop()
            awaitUntil("stop clears the neighbor set") {
                restarted.transport.neighbors.value.isEmpty()
            }
            awaitUntil("the first client notices the stop") {
                firstClient.transport.neighbors.value.isEmpty()
            }

            restarted.transport.start()
            restarted.transport.start()

            val secondPort = restarted.transport.listen(0)
            assertTrue("a new listener must have a real port", secondPort in 1..65535)

            secondClient.transport.connect(HOST, secondPort)
            awaitUntil("the restarted transport accepts a new client") {
                restarted.transport.neighbors.value == setOf(3) &&
                    secondClient.transport.neighbors.value == setOf(1)
            }
        } finally {
            restarted.close()
            firstClient.close()
            secondClient.close()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A transport plus a collector for everything it surfaces on `incoming`. */
    private class Endpoint(val id: Int, scope: CoroutineScope) {

        val transport = TcpMeshTransport(localNodeId = id)
        val received = CopyOnWriteArrayList<ReceivedPacket>()

        private val jobs = mutableListOf<Job>()

        init {
            jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.incoming.collect { received += it }
            }
        }

        fun close() {
            jobs.forEach { it.cancel() }
            transport.stop()
        }
    }

    /** A manually driven socket that completes the HELLO handshake. */
    private class RawSocket(
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream,
    ) : AutoCloseable {

        fun writeLength(size: Int) {
            output.writeShort(size)
            output.flush()
        }

        override fun close() {
            socket.close()
        }
    }

    private fun connectRaw(port: Int, nodeId: Int): RawSocket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.soTimeout = IO_TIMEOUT_MS.toInt()
        socket.connect(InetSocketAddress(HOST, port), IO_TIMEOUT_MS.toInt())

        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

        // The hub speaks first: consume its HELLO, then answer with ours.
        val hello = readFrame(input)
        check(
            FrameCodec.decode(hello).getOrThrow().type == FrameType.HELLO,
        ) { "expected a HELLO frame from the hub" }
        writeFrame(output, helloFrame(nodeId))

        return RawSocket(socket, input, output)
    }

    /** Fails unless the server closed [peer] rather than leaving it silent. */
    private suspend fun assertServerClosed(peer: RawSocket) {
        val result = withTimeout(IO_TIMEOUT_MS * 2) {
            withContext(Dispatchers.IO) {
                try {
                    peer.input.read()
                } catch (timeout: SocketTimeoutException) {
                    // Still open and silent: not closed by the server.
                    Int.MIN_VALUE
                } catch (error: IOException) {
                    // Reset counts as closed too.
                    -1
                }
            }
        }
        assertEquals("the transport must close the malformed socket", -1, result)
    }

    /** Proves the hub still works by sending one frame to [good]. */
    private suspend fun assertStillServing(hub: Endpoint, good: Endpoint, seq: Int) {
        val bytes = FrameCodec.encode(
            Frame(1, FrameType.MSG, 0, hub.id, seq, 8, "still alive".toByteArray()),
        )
        hub.transport.send(good.id, bytes)
        awaitUntil("the good neighbor still receives frames") {
            good.received.any { it.bytes.contentEquals(bytes) }
        }
        assertEquals(1, good.received.count { it.bytes.contentEquals(bytes) })
    }

    private suspend fun readFrameWithTimeout(peer: RawSocket): ByteArray =
        withTimeout(IO_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { readFrame(peer.input) }
        }

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
}

private fun helloFrame(nodeId: Int): ByteArray = FrameCodec.encode(
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
    check(size in 1..MAX_FRAME_SIZE) { "invalid frame length: $size" }
    val bytes = ByteArray(size)
    input.readFully(bytes)
    return bytes
}

private fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
    output.writeShort(bytes.size)
    output.write(bytes)
    output.flush()
}
