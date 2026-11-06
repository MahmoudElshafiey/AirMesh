package com.airmesh.node

import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.StoreAndForward
import com.airmesh.transport.ConnectOutcome
import com.airmesh.transport.TcpMeshTransport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end flake hunt for store-and-forward over real localhost TCP: node A
 * originates a message while isolated, then B connects (or reconnects), and B
 * must deliver that message exactly once.
 *
 * The first test runs 100 iterations, alternating which side is hub/client and
 * varying how long after origination B connects (0/5/50 ms). The second test
 * reproduces the exact device sequence: a link comes up, goes down, A
 * originates while disconnected, then the same node id reconnects — the queued
 * frame must reach it exactly once.
 */
class AirMeshNodeReconnectFlakeTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val POLL_MS = 5L

        /** Connect delays cycled through, in ms, after A's origination. */
        val CONNECT_DELAYS_MS = longArrayOf(0, 5, 50)

        /** How long to keep watching for duplicate deliveries after the first one arrives. */
        const val SETTLE_MS = 200L
    }

    @Test
    fun queuedFrameIsDeliveredExactlyOnceAfterConnect() = runBlocking {
        repeat(100) { i ->
            oneIteration(iteration = i + 1, aIsHub = i % 2 == 0, connectDelayMs = CONNECT_DELAYS_MS[i % 3])
        }
    }

    @Test
    fun queuedFrameIsDeliveredExactlyOnceAfterReconnect() = runBlocking {
        repeat(10) { i -> reconnectIteration(iteration = i + 1) }
    }

    private suspend fun oneIteration(iteration: Int, aIsHub: Boolean, connectDelayMs: Long) {
        val a = newNode(1)
        val b = newNode(2)
        try {
            a.start()
            b.start()
            val hubPort = if (aIsHub) a.transport.listen(0) else b.transport.listen(0)

            // A originates while isolated: no neighbors, so the frame is queued.
            assertTrue("iteration $iteration: originate failed", a.sendText("flake-$iteration"))

            delay(connectDelayMs)
            val outcome = if (aIsHub) {
                b.transport.connect(HOST, hubPort)
            } else {
                a.transport.connect(HOST, hubPort)
            }
            assertTrue("iteration $iteration: connect failed: $outcome", outcome is ConnectOutcome.Success)

            awaitUntil("iteration $iteration: B receives the queued frame") {
                b.messages.value.count { it.text == "flake-$iteration" } >= 1
            }
            assertNoDuplicates(b, "flake-$iteration", iteration)
        } finally {
            a.stop()
            b.stop()
        }
    }

    private suspend fun reconnectIteration(iteration: Int) {
        val a = newNode(1) // A is always the hub here (device scenario: A owns the group).
        try {
            a.start()
            val port = a.transport.listen(0)

            val b1 = newNode(2)
            b1.start()
            assertTrue(
                "iteration $iteration: first connect failed",
                b1.transport.connect(HOST, port) is ConnectOutcome.Success,
            )
            awaitUntil("iteration $iteration: first link up") {
                a.neighbors.value == setOf(2) && b1.neighbors.value == setOf(1)
            }

            // B disconnects; A must notice.
            b1.stop()
            awaitUntil("iteration $iteration: A sees the link down") { a.neighbors.value.isEmpty() }

            // Isolated again: this frame is queued in A's store.
            assertTrue("iteration $iteration: originate failed", a.sendText("reconnect-$iteration"))

            val b2 = newNode(2)
            b2.start()
            try {
                assertTrue(
                    "iteration $iteration: reconnect failed",
                    b2.transport.connect(HOST, port) is ConnectOutcome.Success,
                )
                awaitUntil("iteration $iteration: B receives the queued frame after reconnect") {
                    b2.messages.value.count { it.text == "reconnect-$iteration" } >= 1
                }
                assertNoDuplicates(b2, "reconnect-$iteration", iteration)
            } finally {
                b2.stop()
            }
        } finally {
            a.stop()
        }
    }

    /** Keeps watching for [SETTLE_MS] after the first delivery; a second copy is a failure. */
    private suspend fun assertNoDuplicates(node: AirMeshNode, text: String, iteration: Int) {
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            val count = node.messages.value.count { it.text == text }
            assertTrue("iteration $iteration: duplicate delivery of '$text' (count=$count)", count <= 1)
            delay(20)
        }
    }

    private fun newNode(id: Int): AirMeshNode = AirMeshNode(
        localNodeId = id,
        transport = TcpMeshTransport(localNodeId = id),
        eventLog = EventLog(),
        storeFactory = { send -> StoreAndForward(store = InMemoryPacketStore(), send = send) },
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
