package com.airmesh.transport

import com.airmesh.node.AirMeshNode
import com.airmesh.node.EventLog
import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.StoreAndForward
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Handshake race: the hub writes its HELLO and, with no delay in between, a MSG
 * frame — either as one write or as two back-to-back writes. The client's
 * handshake reads exactly one length-prefixed frame (the HELLO), so the MSG
 * bytes must survive in the socket buffer until the reader loop is attached,
 * and the client must deliver that MSG to its Router.
 *
 * Every iteration uses a fresh TCP connection against a raw-socket hub; the
 * client is a real [AirMeshNode] over [TcpMeshTransport]. The last variant
 * delays the client's post-handshake registration by 50 ms through
 * `postHandshakeDelayMs`, so bytes pile up in the socket while no reader is
 * attached yet.
 */
class TcpMeshTransportHandshakeRaceTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val POLL_MS = 5L
        const val CLIENT_ID = 2
        const val HUB_ID = 7
        const val ITERATIONS = 200
    }

    @Test
    fun msgInSameWriteAsHelloIsDelivered() = runBlocking {
        race(sameWrite = true)
    }

    @Test
    fun msgInBackToBackWritesAfterHelloIsDelivered() = runBlocking {
        race(sameWrite = false)
    }

    @Test
    fun msgPiledUpWhileHandshakeCompletionIsDelayedIsDelivered() = runBlocking {
        // The client delays registration and reader attachment by 50 ms after
        // the HELLO exchange, so the hub's MSG sits in the socket buffer with
        // no reader attached.
        race(sameWrite = true, postHandshakeDelayMs = 50)
    }

    private suspend fun race(sameWrite: Boolean, postHandshakeDelayMs: Long = 0L) {
        val client = AirMeshNode(
            localNodeId = CLIENT_ID,
            transport = TcpMeshTransport(localNodeId = CLIENT_ID, postHandshakeDelayMs = postHandshakeDelayMs),
            eventLog = EventLog(),
            storeFactory = { send -> StoreAndForward(store = InMemoryPacketStore(), send = send) },
        )
        try {
            client.start()
            repeat(ITERATIONS) { i ->
                oneIteration(client, iteration = i + 1, sameWrite = sameWrite)
            }
        } finally {
            client.stop()
        }
    }

    private suspend fun oneIteration(client: AirMeshNode, iteration: Int, sameWrite: Boolean) = coroutineScope {
        val server = ServerSocket(0).also { it.reuseAddress = true }
        try {
            val hubError = java.util.concurrent.atomic.AtomicReference<Throwable>()
            launch(Dispatchers.IO) {
                runCatching { serveOne(server, iteration, sameWrite) }
                    .onFailure { hubError.set(it) }
            }

            val outcome = client.transport.connect(HOST, server.localPort)
            assertTrue("iteration $iteration: connect failed: $outcome", outcome is ConnectOutcome.Success)

            awaitUntil("iteration $iteration delivers the MSG that followed HELLO") {
                client.messages.value.any { it.text == "race-$iteration" }
            }
            hubError.get()?.let { throw AssertionError("iteration $iteration: raw hub failed", it) }
        } finally {
            runCatching { server.close() }
        }
    }

    /** Accepts one connection, reads the client's HELLO, then writes our own HELLO and a MSG frame. */
    private fun serveOne(server: ServerSocket, iteration: Int, sameWrite: Boolean) {
        val socket = server.accept().also { it.tcpNoDelay = true }
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val helloBytes = readLengthPrefixed(input) ?: throw IllegalStateException("client closed before HELLO")
            val hello = FrameCodec.decode(helloBytes).getOrNull()
                ?: throw IllegalStateException("client HELLO failed CRC or decoding")
            if (hello.type != FrameType.HELLO || hello.nodeId != CLIENT_ID) {
                throw IllegalStateException("expected client HELLO, got $hello")
            }

            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val helloFrame = FrameCodec.encode(hubHello())
            val msgFrame = FrameCodec.encode(msg(iteration))
            if (sameWrite) {
                // One write call: HELLO and MSG go out in the same segment.
                val combined = ByteArrayOutputStream()
                val dos = DataOutputStream(combined)
                dos.writeShort(helloFrame.size)
                dos.write(helloFrame)
                dos.writeShort(msgFrame.size)
                dos.write(msgFrame)
                output.write(combined.toByteArray())
            } else {
                // Two back-to-back write calls, no delay in between.
                output.writeShort(helloFrame.size)
                output.write(helloFrame)
                output.flush()
                output.writeShort(msgFrame.size)
                output.write(msgFrame)
            }
            output.flush()
        } finally {
            socket.close()
        }
    }

    private fun hubHello(): Frame = Frame(
        version = 1,
        type = FrameType.HELLO,
        flags = 0,
        nodeId = HUB_ID,
        seq = 0,
        ttl = 1,
        payload = ByteArray(0),
    )

    private fun msg(iteration: Int): Frame = Frame(
        version = 1,
        type = FrameType.MSG,
        flags = 0,
        nodeId = HUB_ID,
        seq = iteration,
        ttl = 8,
        payload = "race-$iteration".toByteArray(),
    )

    /** Reads one length-prefixed frame body; null on a clean end of stream. */
    private fun readLengthPrefixed(input: DataInputStream): ByteArray? {
        val high = input.read()
        if (high < 0) return null
        val low = input.read()
        if (low < 0) throw EOFException("truncated frame length")
        val size = (high shl 8) or low
        if (size <= 0 || size > 65_535) throw IllegalStateException("malformed frame length: $size")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return bytes
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
