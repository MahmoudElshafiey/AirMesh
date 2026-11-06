package com.airmesh.transport

import com.airmesh.node.EventLog
import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure semantics of [TcpMeshTransport.trySend]: the send callback used by
 * Router/StoreAndForward must report FAILURE when a frame cannot actually be
 * handed to a live peer, so the frame stays queued and is retried later.
 */
class TcpMeshTransportTrySendTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val POLL_MS = 5L
        const val HUB_ID = 3
    }

    @Test
    fun trySendToUnknownNeighborReturnsFalse() = runBlocking {
        val log = EventLog()
        val a = TcpMeshTransport(localNodeId = 1, eventLog = log)
        try {
            a.start()
            assertFalse("unknown neighbor must report failure", a.trySend(99, frameBytes()))
            assertTrue(
                "the drop must be logged",
                log.entries.value.any { it.text == "TCP: send dropped: unknown neighbor 99" },
            )
        } finally {
            a.stop()
        }
    }

    @Test
    fun trySendAfterPeerIsRemovedReturnsFalse() = runBlocking {
        val a = TcpMeshTransport(localNodeId = 1)
        val b = TcpMeshTransport(localNodeId = HUB_ID)
        try {
            a.start()
            b.start()
            val port = b.listen(0)
            assertTrue("connect failed", a.connect(HOST, port) is ConnectOutcome.Success)
            awaitUntil("both sides handshook") { a.neighbors.value == setOf(HUB_ID) }

            // Closes the socket; A's reader notices and removes the peer.
            b.stop()
            awaitUntil("A forgets the dead peer") { a.neighbors.value.isEmpty() }

            assertFalse("send to a removed peer must report failure", a.trySend(HUB_ID, frameBytes()))
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun trySendReportsFailureWhenTheRemoteResets() = runBlocking {
        // A raw hub that completes the HELLO exchange and then waits: the test
        // decides when the socket is reset (SO_LINGER(0) close sends RST), so
        // the writes below race the in-flight RST. Holding the socket open until
        // signaled keeps the neighbor state observable; an immediate close would
        // make the link live for microseconds, shorter than the poll interval.
        val a = TcpMeshTransport(localNodeId = 1)
        val server = ServerSocket(0).also { it.reuseAddress = true }
        try {
            a.start()
            val resetSignal = CompletableDeferred<Unit>()
            launch(Dispatchers.IO) {
                runCatching {
                    val socket = server.accept().also { it.tcpNoDelay = true }
                    try {
                        readClientHello(socket)
                        writeHubHello(socket)
                        resetSignal.await()
                    } finally {
                        // Close sends RST instead of a graceful FIN.
                        socket.setSoLinger(true, 0)
                        socket.close()
                    }
                }
            }

            val outcome = a.connect(HOST, server.localPort)
            assertTrue("connect failed: $outcome", outcome is ConnectOutcome.Success)
            awaitUntil("A sees the hub as a neighbor") { a.neighbors.value == setOf(HUB_ID) }

            // Reset the socket now; subsequent writes race the RST in flight.
            resetSignal.complete(Unit)

            // The first write may still land in the kernel buffer before the RST
            // is processed; eventually one must report failure.
            var result = true
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline) {
                result = a.trySend(HUB_ID, frameBytes())
                if (!result) break
                delay(10)
            }
            assertFalse("a write to an RST'd peer must eventually report failure", result)
            awaitUntil("A removes the dead peer") { a.neighbors.value.isEmpty() }
        } finally {
            runCatching { server.close() }
            a.stop()
        }
    }

    /**
     * Documents current behavior when a write succeeds but the remote never
     * processes the frame: [TcpMeshTransport.trySend] keeps returning true and
     * the neighbor stays registered, because success only means the local
     * kernel accepted the bytes into its send buffer. A peer that never reads
     * keeps accepting (until its receive buffer fills) and the sender cannot
     * detect the loss synchronously — recovery relies on store-and-forward
     * replay plus TTL expiry, not on write feedback.
     */
    @Test
    fun documentedBehavior_writeSucceedsWhileRemoteNeverReads(): Unit = runBlocking {
        val a = TcpMeshTransport(localNodeId = 1)
        val server = ServerSocket(0).also { it.reuseAddress = true }
        try {
            a.start()
            val released = CompletableDeferred<Unit>()
            launch(Dispatchers.IO) {
                runCatching {
                    val socket = server.accept().also { it.tcpNoDelay = true }
                    try {
                        readClientHello(socket)
                        writeHubHello(socket)
                        // Hold the socket open but never read again.
                        released.await()
                    } finally {
                        socket.close()
                    }
                }
            }

            val outcome = a.connect(HOST, server.localPort)
            assertTrue("connect failed: $outcome", outcome is ConnectOutcome.Success)
            awaitUntil("A sees the hub as a neighbor") { a.neighbors.value == setOf(HUB_ID) }

            repeat(50) { i ->
                assertTrue("write #$i must succeed while the kernel buffers it", a.trySend(HUB_ID, frameBytes(i)))
            }
            assertTrue("the silent peer is still registered", HUB_ID in a.neighbors.value)

            released.complete(Unit)
        } finally {
            runCatching { server.close() }
            a.stop()
        }
    }

    /** Reads and validates the client's HELLO frame from [socket]. */
    private fun readClientHello(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val high = input.read()
        if (high < 0) throw IllegalStateException("client closed before HELLO")
        val low = input.read()
        if (low < 0) throw java.io.EOFException("truncated frame length")
        val size = (high shl 8) or low
        val bytes = ByteArray(size)
        input.readFully(bytes)
        val hello = FrameCodec.decode(bytes).getOrNull() ?: throw IllegalStateException("bad client HELLO")
        if (hello.type != FrameType.HELLO || hello.nodeId != 1) {
            throw IllegalStateException("expected client HELLO, got $hello")
        }
    }

    /** Writes the hub's length-prefixed HELLO frame to [socket]. */
    private fun writeHubHello(socket: Socket) {
        val output = java.io.DataOutputStream(java.io.BufferedOutputStream(socket.getOutputStream()))
        val hello = FrameCodec.encode(
            Frame(
                version = 1,
                type = FrameType.HELLO,
                flags = 0,
                nodeId = HUB_ID,
                seq = 0,
                ttl = 1,
                payload = ByteArray(0),
            ),
        )
        output.writeShort(hello.size)
        output.write(hello)
        output.flush()
    }

    private fun frameBytes(seq: Int = 1): ByteArray = FrameCodec.encode(
        Frame(
            version = 1,
            type = FrameType.MSG,
            flags = 0,
            nodeId = 1,
            seq = seq,
            ttl = 8,
            payload = "try".toByteArray(),
        ),
    )

    /** Waits for [condition] in real time, failing the test instead of hanging. */
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
}
