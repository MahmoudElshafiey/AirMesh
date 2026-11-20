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
 * JVM integration tests for the silence grace write and the flap guard (TASK T1 part D2) over real
 * loopback sockets.
 *
 * The grace check is a plain lambda in every test ({true}, {false} or null), so no Wi-Fi Direct is
 * involved; production passes "group formed AND p2p interface has its mesh address". Ports are
 * never hard-coded (port 0 + OS-assigned) and nothing sleeps to "let things settle" — the late
 * reply in [graceKeepaliveKeepsLinkWhenPeerAnswersLate] is a scheduled action, not a settle sleep.
 */
class TcpMeshTransportSilenceGraceTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val RAW_PEER_ID = 99
        const val MAX_TEST_FRAME_SIZE = FrameCodec.MIN_FRAME_SIZE + FrameCodec.MAX_PAYLOAD

        /** Poll interval for condition checks; the timeout does the bounding. */
        const val POLL_MS = 5L

        /** Keepalive probe interval used by every socket test here. */
        const val KEEPALIVE_MS = 250L

        /** Base silence deadline: "no data for 2s", raised to 3 s by the flap guard. */
        const val DEAD_AFTER_MS = 2_000L
    }

    // ------------------------------------------------------------------
    // (1) More than FLAP_GUARD_THRESHOLD silent episodes for the same peer inside the window raise
    //     its effective dead timeout by 50 %; the next drop uses the raised deadline and is logged.
    // ------------------------------------------------------------------

    @Test
    fun flapGuardRaisesDeadTimeoutAfterRepeatedSilence() = runBlocking {
        val scope = testScope()
        val logA = EventLog()
        val a = TcpMeshTransport(localNodeId = 1, eventLog = logA, keepAliveIntervalMs = KEEPALIVE_MS, deadAfterMs = DEAD_AFTER_MS)

        try {
            val port = a.listen(0)
            // Four silent episodes at the base deadline (~2 s each), then one more that must run on
            // the raised 3 s deadline. The flap state is kept across reconnects on purpose: the
            // flapping pattern IS timeout -> close -> reconnect -> timeout again.
            repeat(5) {
                Socket().use { socket ->
                    openRawPeer(socket, port)
                    awaitUntil("A registered the raw peer") { a.neighbors.value == setOf(RAW_PEER_ID) }
                    awaitUntil("the silent peer is dropped", timeoutMs = 10_000) {
                        !a.neighbors.value.contains(RAW_PEER_ID)
                    }
                }
            }

            assertEquals(
                "after >3 timeouts in the window the effective deadline is raised by 50 %",
                3_000L,
                a.deadAfterMsFor(RAW_PEER_ID),
            )
            assertTrue(
                "the raise must be logged, got: ${logA.entries.value.map { it.text }}",
                logA.entries.value.any { it.text.contains("dead timeout raised to 3s") },
            )
            assertEquals(
                "exactly the first four drops run on the base deadline",
                4,
                logA.entries.value.count { it.text == "TCP: link to #$RAW_PEER_ID timed out (no data for 2s)" },
            )
            assertTrue(
                "the fifth drop must use the raised deadline, got: ${logA.entries.value.map { it.text }}",
                logA.entries.value.any { it.text == "TCP: link to #$RAW_PEER_ID timed out (no data for 3s)" },
            )
        } finally {
            a.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // (2) The raise math is pure and capped at FLAP_GUARD_MAX_DEAD_AFTER_MS.
    // ------------------------------------------------------------------

    @Test
    fun flapGuardRaiseIsFiftyPercentCappedAtNinetySeconds() {
        assertEquals("8 s raises to 12 s", 12_000L, flapGuardRaisedTimeout(8_000L))
        assertEquals("45 s raises to 67.5 s", 67_500L, flapGuardRaisedTimeout(45_000L))
        assertEquals("70 s would raise to 105 s but is capped at 90 s", 90_000L, flapGuardRaisedTimeout(70_000L))
        assertEquals("the cap holds for any larger base", FLAP_GUARD_MAX_DEAD_AFTER_MS, flapGuardRaisedTimeout(240_000L))
    }

    // ------------------------------------------------------------------
    // (3) A peer that goes silent past the deadline but answers once during the grace window keeps
    //     the link: one keepalive is written, the late frame reaches incoming, no timeout line.
    // ------------------------------------------------------------------

    @Test
    fun graceKeepaliveKeepsLinkWhenPeerAnswersLate() = runBlocking {
        val scope = testScope()
        val logA = EventLog()
        val a = TcpMeshTransport(
            localNodeId = 1,
            eventLog = logA,
            keepAliveIntervalMs = KEEPALIVE_MS,
            deadAfterMs = DEAD_AFTER_MS,
            silenceGraceCheck = { true },
        )
        val received = CopyOnWriteArrayList<ReceivedPacket>()

        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                a.incoming.collect { received += it }
            }
            val port = a.listen(0)
            Socket().use { socket ->
                openRawPeer(socket, port)
                awaitUntil("A registered the raw peer") { a.neighbors.value == setOf(RAW_PEER_ID) }

                // The peer goes silent past the 2 s deadline, then answers once at +2.5 s — inside
                // the grace window (deadline + 5 s). A scheduled action, not a settle sleep.
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val lateReply = scope.launch {
                    delay(2_500L)
                    writeFrame(output, msgBytes(RAW_PEER_ID, 0))
                }

                try {
                    val startedAt = System.currentTimeMillis()
                    awaitUntil("either dropped or survived past deadline + grace window", timeoutMs = 15_000) {
                        !a.neighbors.value.contains(RAW_PEER_ID) ||
                            System.currentTimeMillis() - startedAt >= 8_000L
                    }

                    assertTrue(
                        "the late answer must have kept the link up, got: ${logA.entries.value.map { it.text }}",
                        a.neighbors.value.contains(RAW_PEER_ID),
                    )
                    assertTrue(
                        "no timeout line may appear when the grace write was answered",
                        logA.entries.value.none { it.text.contains("timed out") },
                    )
                    assertTrue(
                        "the grace attempt must be logged, got: ${logA.entries.value.map { it.text }}",
                        logA.entries.value.any { it.text.contains("one keepalive and 5 s more") },
                    )
                    assertTrue("the late frame must reach incoming", received.any { it.senderId == RAW_PEER_ID })
                } finally {
                    lateReply.cancel()
                }
            }
        } finally {
            a.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // (4) A peer that stays silent through the grace window is dropped at deadline + 5 s, with the
    //     exact timeout line and the logged grace attempt.
    // ------------------------------------------------------------------

    @Test
    fun graceFailsWhenPeerStaysSilent() = runBlocking {
        val scope = testScope()
        val logA = EventLog()
        val a = TcpMeshTransport(
            localNodeId = 1,
            eventLog = logA,
            keepAliveIntervalMs = KEEPALIVE_MS,
            deadAfterMs = DEAD_AFTER_MS,
            silenceGraceCheck = { true },
        )

        try {
            val port = a.listen(0)
            Socket().use { socket ->
                openRawPeer(socket, port)
                awaitUntil("A registered the raw peer") { a.neighbors.value == setOf(RAW_PEER_ID) }
                // The peer never answers: drop at ~deadline + grace (2 s + 5 s).
                awaitUntil("the silent peer is dropped after deadline + grace", timeoutMs = 15_000) {
                    !a.neighbors.value.contains(RAW_PEER_ID)
                }
            }

            assertTrue(
                "the grace attempt must be logged, got: ${logA.entries.value.map { it.text }}",
                logA.entries.value.any { it.text.contains("one keepalive and 5 s more") },
            )
            assertTrue(
                "the exact timeout line must appear, got: ${logA.entries.value.map { it.text }}",
                logA.entries.value.any { it.text == "TCP: link to #$RAW_PEER_ID timed out (no data for 2s)" },
            )
        } finally {
            a.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // (5) A grace check that says no drops the peer at the plain deadline, with no grace attempt.
    //     (null — the default — behaves identically and is covered by the existing keepalive test.)
    // ------------------------------------------------------------------

    @Test
    fun graceCheckFalseDropsImmediatelyWithoutGrace() = runBlocking {
        val scope = testScope()
        val logA = EventLog()
        val a = TcpMeshTransport(
            localNodeId = 1,
            eventLog = logA,
            keepAliveIntervalMs = KEEPALIVE_MS,
            deadAfterMs = DEAD_AFTER_MS,
            silenceGraceCheck = { false },
        )

        try {
            val port = a.listen(0)
            Socket().use { socket ->
                openRawPeer(socket, port)
                awaitUntil("A registered the raw peer") { a.neighbors.value == setOf(RAW_PEER_ID) }
                awaitUntil("the silent peer is dropped at the plain deadline", timeoutMs = 10_000) {
                    !a.neighbors.value.contains(RAW_PEER_ID)
                }
            }

            assertTrue(
                "no grace attempt may be logged when the check says no, got: ${logA.entries.value.map { it.text }}",
                logA.entries.value.none { it.text.contains("one keepalive") },
            )
            assertTrue(
                "the exact timeout line must appear, got: ${logA.entries.value.map { it.text }}",
                logA.entries.value.any { it.text == "TCP: link to #$RAW_PEER_ID timed out (no data for 2s)" },
            )
        } finally {
            a.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Test harness (same loopback pattern as TcpMeshTransportKeepaliveTest)
    // ------------------------------------------------------------------

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            payload = "silence grace test".toByteArray(),
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
