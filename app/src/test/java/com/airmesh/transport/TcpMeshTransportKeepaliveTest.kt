package com.airmesh.transport

import com.airmesh.node.EventLog
import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM integration tests for the optional link keepalive (TASK U5 part 5) over real loopback
 * sockets.
 *
 * Ports are never hard-coded: every listener binds port 0 and the transport returns the port the
 * OS picked. Nothing sleeps to "let things settle"; survival assertions wait on a condition that
 * includes an elapsed-time bound, and every other assertion waits through [awaitUntil].
 *
 * Intervals are shrunk for tests (250 ms keepalive instead of production's 8 s) but the silence
 * timeout stays >= 1 s so the "no data for Xs" log line is meaningful.
 */
class TcpMeshTransportKeepaliveTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val RAW_PEER_ID = 99
        const val MAX_TEST_FRAME_SIZE = FrameCodec.MIN_FRAME_SIZE + FrameCodec.MAX_PAYLOAD

        /** Poll interval for condition checks; the timeout does the bounding. */
        const val POLL_MS = 5L

        /** Keepalive probe interval used by every test here (production uses 8 s). */
        const val KEEPALIVE_MS = 250L

        /** Silence that declares a link dead; >= 1 s so "no data for Xs" is meaningful. */
        const val DEAD_AFTER_MS = 1_500L
    }

    // ------------------------------------------------------------------
    // (a) An idle but healthy link stays up: keepalive HELLOs keep both readers busy.
    // ------------------------------------------------------------------

    @Test
    fun idleLinkStaysUpWithKeepaliveEnabled() = runBlocking {
        val scope = testScope()
        val a = transport(1, EventLog(), keepAlive = true)
        val b = transport(2, EventLog(), keepAlive = true)

        try {
            val port = b.listen(0)
            a.connect(HOST, port)
            awaitUntil("both sides handshook") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }

            // No traffic at all from here on: the keepalive HELLOs are what keeps both readers
            // inside their silence window. Survive well past deadAfterMs.
            val startedAt = System.currentTimeMillis()
            awaitUntil("idle link survives past ${DEAD_AFTER_MS}ms of allowed silence") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1) &&
                    System.currentTimeMillis() - startedAt >= 2_400L
            }
        } finally {
            a.stop()
            b.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // (b) A peer that handshakes and then goes silent is dropped after deadAfterMs, with the
    //     exact timeout line in the log.
    // ------------------------------------------------------------------

    @Test
    fun silentPeerIsDroppedAfterDeadAfterMs() = runBlocking {
        val scope = testScope()
        val logA = EventLog()
        val a = transport(1, logA, keepAlive = true)

        try {
            val port = a.listen(0)
            Socket().use { socket ->
                openRawPeer(socket, port)
                awaitUntil("A registered the raw peer") {
                    a.neighbors.value == setOf(RAW_PEER_ID)
                }
                // From here on the peer sends nothing but keeps the socket open: a dead link,
                // not a clean close.

                awaitUntil("the silent peer is dropped after ${DEAD_AFTER_MS}ms of silence") {
                    !a.neighbors.value.contains(RAW_PEER_ID)
                }
            }

            assertTrue(
                "expected the exact timeout line, got: ${logA.entries.value.map { it.text }}",
                logA.entries.value.any {
                    it.text == "TCP: link to #$RAW_PEER_ID timed out (no data for 1s)"
                },
            )
        } finally {
            a.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // (c) Keepalive HELLOs are consumed by the transport and never reach incoming.
    // ------------------------------------------------------------------

    @Test
    fun keepaliveHellosNeverReachIncoming() = runBlocking {
        val scope = testScope()
        val logA = EventLog()
        val logB = EventLog()
        val a = transport(1, logA, keepAlive = true)
        val b = transport(2, logB, keepAlive = true)
        val aReceived = CopyOnWriteArrayList<ReceivedPacket>()
        val bReceived = CopyOnWriteArrayList<ReceivedPacket>()

        try {
            // Subscribe before connecting so nothing can be missed.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                a.incoming.collect { aReceived += it }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                b.incoming.collect { bReceived += it }
            }

            val port = b.listen(0)
            a.connect(HOST, port)
            awaitUntil("both sides handshook") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }

            // Wait until keepalive HELLOs have demonstrably flowed in both directions and been
            // consumed by the readers (the drop line is proof they were actually sent).
            awaitUntil("keepalive HELLOs flowed both ways") {
                logA.entries.value.any { it.text.contains("type=HELLO") && it.text.contains("from 2") } &&
                    logB.entries.value.any { it.text.contains("type=HELLO") && it.text.contains("from 1") }
            }

            assertTrue(
                "keepalive HELLOs must never reach incoming, got: $aReceived",
                aReceived.isEmpty(),
            )
            assertTrue(
                "keepalive HELLOs must never reach incoming, got: $bReceived",
                bReceived.isEmpty(),
            )
        } finally {
            a.stop()
            b.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // (d) Default constructor parameters behave exactly as before: no keepalive writes, and a
    //     silent-but-open peer is never declared dead.
    // ------------------------------------------------------------------

    @Test
    fun defaultParamsKeepTodayBehaviorSilentPeerStays() = runBlocking {
        val scope = testScope()
        val logA = EventLog()
        // No keepalive parameters: exactly today's constructor defaults (0/0).
        val a = transport(1, logA, keepAlive = false)

        try {
            val port = a.listen(0)
            Socket().use { socket ->
                openRawPeer(socket, port)
                awaitUntil("A registered the raw peer") {
                    a.neighbors.value == setOf(RAW_PEER_ID)
                }

                // With keepalive off the reader blocks forever: a silent-but-open peer stays a
                // neighbor indefinitely, exactly as before this feature.
                val startedAt = System.currentTimeMillis()
                awaitUntil("silent peer stays a neighbor with default params") {
                    a.neighbors.value == setOf(RAW_PEER_ID) &&
                        System.currentTimeMillis() - startedAt >= 2_400L
                }
            }

            assertTrue(
                "no timeout line may appear with default params, got: ${logA.entries.value.map { it.text }}",
                logA.entries.value.none { it.text.contains("timed out") },
            )
        } finally {
            a.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // (e) Active traffic suppresses keepalive writes: while the peer sends, A writes nothing;
    //     once it goes idle, probing resumes.
    // ------------------------------------------------------------------

    @Test
    fun activeTrafficSuppressesKeepaliveWrites() = runBlocking {
        val scope = testScope()
        val logA = EventLog()
        val a = transport(1, logA, keepAlive = true)

        try {
            val port = a.listen(0)
            Socket().use { socket ->
                openRawPeer(socket, port)
                awaitUntil("A registered the raw peer") {
                    a.neighbors.value == setOf(RAW_PEER_ID)
                }

                // Active phase: the peer sends a real MSG frame every 40 ms — well inside the
                // keepalive interval — so A must not need to probe at all. (Only keepalives can
                // produce "wrote N bytes to 99" lines here; A never broadcasts.)
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                repeat(15) { i ->
                    writeFrame(output, msgBytes(RAW_PEER_ID, i))
                    delay(40)
                }

                assertEquals(
                    "no frame may be written to an actively sending peer",
                    0,
                    logA.entries.value.count { it.text.contains("bytes to $RAW_PEER_ID") },
                )

                // Idle phase: once the traffic stops, probing must resume. (That the HELLO is
                // consumed by the peer's reader and never routed is covered by test (c), where
                // both sides are real transports.)
                awaitUntil("keepalive resumes once the link goes idle") {
                    logA.entries.value.any { it.text.contains("bytes to $RAW_PEER_ID") }
                }
            }
        } finally {
            a.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // (f) A peer that keeps sending but never reads is NOT declared dead: any received frame
    //     counts as liveness, so the reader's silence window never elapses.
    // ------------------------------------------------------------------

    @Test
    fun peerThatKeepsSendingButNeverReadsIsNotDeclaredDead() = runBlocking {
        val scope = testScope()
        val logA = EventLog()
        val a = transport(1, logA, keepAlive = true)

        try {
            val port = a.listen(0)
            Socket().use { socket ->
                openRawPeer(socket, port)
                awaitUntil("A registered the raw peer") {
                    a.neighbors.value == setOf(RAW_PEER_ID)
                }

                // The peer keeps sending but never reads again: A's reader stays busy with real
                // frames, so the link is alive well past the silence window.
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val sender = scope.launch {
                    var seq = 0
                    while (true) {
                        writeFrame(output, msgBytes(RAW_PEER_ID, seq++))
                        delay(40)
                    }
                }

                try {
                    val startedAt = System.currentTimeMillis()
                    awaitUntil("link survives well past ${DEAD_AFTER_MS}ms of allowed silence") {
                        a.neighbors.value.contains(RAW_PEER_ID) &&
                            System.currentTimeMillis() - startedAt >= 3_200L
                    }
                } finally {
                    sender.cancel()
                }
            }

            assertTrue(
                "a peer that keeps sending must not be declared dead, got: ${logA.entries.value.map { it.text }}",
                logA.entries.value.none { it.text.contains("timed out") },
            )
        } finally {
            a.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Test harness
    // ------------------------------------------------------------------

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One transport; [keepAlive] selects the part-5 parameters or today's defaults. */
    private fun transport(id: Int, eventLog: EventLog, keepAlive: Boolean): TcpMeshTransport =
        if (keepAlive) {
            TcpMeshTransport(
                localNodeId = id,
                eventLog = eventLog,
                keepAliveIntervalMs = KEEPALIVE_MS,
                deadAfterMs = DEAD_AFTER_MS,
            )
        } else {
            TcpMeshTransport(localNodeId = id, eventLog = eventLog)
        }

    /** Connects [socket] to the listener and completes the HELLO exchange as node 99. */
    private fun openRawPeer(socket: Socket, port: Int) {
        socket.connect(InetSocketAddress(HOST, port), 5_000)
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        // A sends its HELLO immediately; answer it to complete the handshake.
        assertEquals(
            FrameType.HELLO,
            FrameCodec.decode(readFrame(input)).getOrThrow().type,
        )
        writeFrame(DataOutputStream(BufferedOutputStream(socket.getOutputStream())), helloBytes(RAW_PEER_ID))
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

    private fun msgBytes(nodeId: Int, seq: Int): ByteArray = FrameCodec.encode(
        Frame(
            version = 1,
            type = FrameType.MSG,
            flags = 0,
            nodeId = nodeId,
            seq = seq,
            ttl = 8,
            payload = "keepalive test".toByteArray(),
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
