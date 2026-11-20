package com.airmesh.transport

import com.airmesh.node.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM integration tests for the per-socket keepalive loops (TASK T1 part D1) over real loopback
 * sockets.
 *
 * The old single global writer could let one socket go silent for up to ~3 x interval while a
 * phase-offset peer suppressed it; now every live socket runs its own loop on the transport
 * dispatcher with a monotonic clock, so an idle link keeps writing and the gap between two writes
 * on one socket is bounded by about 2 x interval.
 *
 * Ports are never hard-coded (port 0 + OS-assigned), nothing sleeps to "let things settle", and
 * every assertion waits through [awaitUntil] on a condition that includes an elapsed-time bound.
 */
class TcpMeshTransportPerSocketKeepaliveTest {

    private companion object {
        const val HOST = "127.0.0.1"

        /** Poll interval for condition checks; the timeout does the bounding. */
        const val POLL_MS = 5L

        /** Scheduling slack added on top of the theoretical 2 x interval write-gap bound. */
        const val GAP_TOLERANCE_MS = 1_000L
    }

    // ------------------------------------------------------------------
    // (1) An idle link stays up for 20 full intervals: both sides keep sending and receiving
    //     keepalives while the silence timeout is far shorter than the observation window.
    // ------------------------------------------------------------------

    @Test
    fun idleLinkStaysUpForTwentyIntervals() = runBlocking {
        val scope = testScope()
        val intervalMs = 150L
        val deadAfterMs = 3_000L
        val logA = EventLog()
        val logB = EventLog()
        val a = TcpMeshTransport(localNodeId = 1, eventLog = logA, keepAliveIntervalMs = intervalMs, deadAfterMs = deadAfterMs)
        val b = TcpMeshTransport(localNodeId = 2, eventLog = logB, keepAliveIntervalMs = intervalMs, deadAfterMs = deadAfterMs)

        try {
            val port = b.listen(0)
            a.connect(HOST, port)
            awaitUntil("both sides handshook") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }

            // No traffic at all from here on: 20 intervals of pure keepalive exchange (~3 s or
            // more), while deadAfterMs is only 3 s — any real silence would have killed the link.
            awaitUntil("both sides sent >= 20 keepalives", timeoutMs = 30_000) {
                sent(a, 2) >= 20 && sent(b, 1) >= 20
            }

            assertTrue(
                "the idle link must still be up after 20 intervals on both sides",
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1),
            )
            assertTrue(
                "no side may have declared the other dead, got: ${logA.entries.value.map { it.text }} + ${logB.entries.value.map { it.text }}",
                logA.entries.value.none { it.text.contains("timed out") } &&
                    logB.entries.value.none { it.text.contains("timed out") },
            )
            // Each side's 20th write implies the other received at least its 19th.
            assertTrue(
                "keepalives must have flowed both ways (A recv=${received(a, 2)}, B recv=${received(b, 1)})",
                received(a, 2) >= 18 && received(b, 1) >= 18,
            )
        } finally {
            a.stop()
            b.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // (2) Two links in both directions: N1 -> N2 -> N3. The middle node holds one outbound and
    //     one inbound socket; the largest write gap on EVERY socket stays within about 2 x
    //     interval, far below the silence timeout.
    // ------------------------------------------------------------------

    @Test
    fun twoLinksInBothDirectionsNeverExceedDoubleIntervalSilence() = runBlocking {
        val scope = testScope()
        val intervalMs = 400L
        val deadAfterMs = 8_000L
        val n1 = TcpMeshTransport(localNodeId = 1, eventLog = EventLog(), keepAliveIntervalMs = intervalMs, deadAfterMs = deadAfterMs)
        val n2 = TcpMeshTransport(localNodeId = 2, eventLog = EventLog(), keepAliveIntervalMs = intervalMs, deadAfterMs = deadAfterMs)
        val n3 = TcpMeshTransport(localNodeId = 3, eventLog = EventLog(), keepAliveIntervalMs = intervalMs, deadAfterMs = deadAfterMs)

        try {
            val port2 = n2.listen(0)
            val port3 = n3.listen(0)
            n1.connect(HOST, port2)
            n2.connect(HOST, port3)
            awaitUntil("all three handshook") {
                n1.neighbors.value == setOf(2) &&
                    n2.neighbors.value == setOf(1, 3) &&
                    n3.neighbors.value == setOf(2)
            }

            // Every socket endpoint (4 in total) must have written at least 5 keepalives so the
            // max-gap measurement is stable...
            awaitUntil("all four socket endpoints sent >= 5 keepalives", timeoutMs = 30_000) {
                sent(n1, 2) >= 5 && sent(n2, 1) >= 5 && sent(n2, 3) >= 5 && sent(n3, 2) >= 5
            }

            // ...and the largest gap between consecutive writes on every socket must stay within
            // about 2 x interval (one delay + scheduling slack), far below deadAfterMs.
            val limit = 2 * intervalMs + GAP_TOLERANCE_MS
            for ((name, transport) in listOf("N1" to n1, "N2" to n2, "N3" to n3)) {
                for (s in transport.peerKeepaliveStats()) {
                    assertTrue(
                        "$name -> #${s.neighborId}: maxSilenceMs=${s.maxSilenceMs} must be <= $limit",
                        s.maxSilenceMs <= limit,
                    )
                }
            }
        } finally {
            n1.stop()
            n2.stop()
            n3.stop()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Test harness (same loopback pattern as TcpMeshTransportKeepaliveTest)
    // ------------------------------------------------------------------

    private fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Keepalives [transport] has sent on the socket to peer [id] (0 when not connected). */
    private fun sent(transport: TcpMeshTransport, id: Int): Long =
        transport.peerKeepaliveStats().firstOrNull { it.neighborId == id }?.sent ?: 0L

    /** Keepalives [transport] has received on the socket to peer [id]. */
    private fun received(transport: TcpMeshTransport, id: Int): Long =
        transport.peerKeepaliveStats().firstOrNull { it.neighborId == id }?.received ?: 0L

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
}
