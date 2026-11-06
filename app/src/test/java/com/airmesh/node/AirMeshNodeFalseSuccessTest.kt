package com.airmesh.node

import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.StoreAndForward
import com.airmesh.transport.ConnectOutcome
import com.airmesh.transport.TcpMeshTransport
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Node-level false-success hunt: A sends to a neighbor whose socket is reset
 * (SO_LINGER(0) close) at exactly the moment of the send, decided by a test
 * signal so the write races the in-flight RST. The send callback used by
 * Router/StoreAndForward must report FAILURE when the frame cannot actually be
 * handed to a live peer, so it stays queued and is retried — it must not be
 * marked delivered when the peer never got it.
 *
 * A's transport runs on a single-threaded dispatcher: its reader coroutine
 * blocks in socket read() and pins that thread, so any write task queued behind
 * it can only run after the reader has observed the reset and removed the peer.
 * That makes the "peer is gone but send still reported success" window
 * deterministic instead of racy.
 *
 * Invariant checked per iteration: either the raw hub actually read a MSG frame
 * from A, or A's store still holds that frame pending for the hub. Before the
 * fix, [TcpMeshTransport.send] returned Unit and swallowed both the
 * unknown-peer no-op and write exceptions, so AirMeshNode marked the frame
 * delivered even though it was lost — exactly the on-device symptom where a
 * queued message never arrives after reconnect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AirMeshNodeFalseSuccessTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val POLL_MS = 5L
        const val NODE_A = 1
        const val HUB_ID = 3
        const val ITERATIONS = 20
    }

    @Test
    fun sendToResetNeighborMustNotMarkFrameDelivered() = runBlocking {
        // Single thread: the reader pins it while blocked in read(), so writes
        // queued behind it only run after the reset has been observed.
        val ioContext = newSingleThreadContext("a-transport-io")
        try {
            repeat(ITERATIONS) { i -> oneIteration(ioContext, iteration = i + 1) }
        } finally {
            ioContext.close()
        }
    }

    private suspend fun oneIteration(dispatcher: CoroutineDispatcher, iteration: Int) = coroutineScope {
        val aStore = InMemoryPacketStore()
        val a = AirMeshNode(
            localNodeId = NODE_A,
            transport = TcpMeshTransport(localNodeId = NODE_A, dispatcher = dispatcher),
            eventLog = EventLog(),
            storeFactory = { send -> StoreAndForward(store = aStore, send = send) },
        )
        val server = ServerSocket(0).also { it.reuseAddress = true }
        try {
            a.start()

            val framesReadByHub = AtomicInteger(0)
            // The hub completes the HELLO exchange and then waits here: the test
            // decides when the socket is reset, so the send below races the RST.
            val resetSignal = CompletableDeferred<Unit>()
            launch(Dispatchers.IO) {
                runCatching {
                    val socket = server.accept().also { it.tcpNoDelay = true }
                    try {
                        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                        // The hub reads the client's HELLO, answers, and waits.
                        if (readLengthPrefixed(input) != null) framesReadByHub.incrementAndGet()
                        writeHubHello(socket)
                        resetSignal.await()
                    } finally {
                        // Close sends RST instead of a graceful FIN.
                        socket.setSoLinger(true, 0)
                        socket.close()
                    }
                }
            }

            val outcome = a.transport.connect(HOST, server.localPort)
            assertTrue("iteration $iteration: connect failed: $outcome", outcome is ConnectOutcome.Success)
            awaitUntil("iteration $iteration: A sees the hub") { a.neighbors.value == setOf(HUB_ID) }

            // Reset the socket now; this send races the RST in flight.
            resetSignal.complete(Unit)
            assertTrue("iteration $iteration: originate failed", a.sendText("lost-$iteration"))

            val pending = aStore.pendingFor(HUB_ID, 0L)
            assertTrue(
                "iteration $iteration: false success — A marked the frame delivered to hub $HUB_ID " +
                    "(or dropped it from the store), but the hub only read ${framesReadByHub.get()} frame(s) " +
                    "(its HELLO). The message was lost without a retry.",
                pending.isNotEmpty() || framesReadByHub.get() > 1,
            )

            awaitUntil("iteration $iteration: A forgets the dead hub", timeoutMs = 3_000) {
                a.neighbors.value.isEmpty()
            }
        } finally {
            runCatching { server.close() }
            a.stop()
        }
    }

    /** Reads one length-prefixed frame body; null on a clean end of stream. */
    private fun readLengthPrefixed(input: DataInputStream): ByteArray? {
        val high = input.read()
        if (high < 0) return null
        val low = input.read()
        if (low < 0) throw java.io.EOFException("truncated frame length")
        val size = (high shl 8) or low
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return bytes
    }

    /** Writes the hub's length-prefixed HELLO frame to [socket]. */
    private fun writeHubHello(socket: java.net.Socket) {
        val output = java.io.DataOutputStream(java.io.BufferedOutputStream(socket.getOutputStream()))
        val hello = com.airmesh.protocol.FrameCodec.encode(
            com.airmesh.protocol.Frame(
                version = 1,
                type = com.airmesh.protocol.FrameType.HELLO,
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
