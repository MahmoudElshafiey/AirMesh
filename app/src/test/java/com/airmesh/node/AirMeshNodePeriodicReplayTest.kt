package com.airmesh.node

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameType
import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.StoreAndForward
import com.airmesh.transport.TcpMeshTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Store-and-forward retry test for [AirMeshNode]: a frame whose send failed
 * stays queued and must be retried for neighbors that are already connected —
 * not only when a neighbor disconnects and reconnects.
 *
 * The node's coroutines run on a manually driven [TestCoroutineScheduler], so
 * the 5-second replay interval is virtual time, while both transports use real
 * Dispatchers.IO sockets. Advancing the scheduler past one interval is what
 * triggers the retry; no neighbor event fires in between, so only the periodic
 * loop can deliver the frame. All clocks are the real system clock, matching
 * production wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AirMeshNodePeriodicReplayTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val POLL_MS = 5L
    }

    @Test
    fun queuedFramesAreRetriedForConnectedNeighborsWithoutAReconnect() = runBlocking {
        // A's node logic runs on virtual time; its transport (and all of B)
        // uses real Dispatchers.IO, so socket I/O is unaffected.
        val scheduler = TestCoroutineScheduler()
        val aStore = InMemoryPacketStore()
        val a = AirMeshNode(
            localNodeId = 1,
            transport = TcpMeshTransport(localNodeId = 1),
            eventLog = EventLog(),
            storeFactory = { send -> StoreAndForward(store = aStore, send = send) },
            dispatcher = StandardTestDispatcher(scheduler),
        )
        val b = newNode(2)

        try {
            a.start()
            b.start()

            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("A and B handshake") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }

            // Let A's (virtual-time) neighbor collector process the link-up so
            // that only the periodic loop can deliver what we queue next.
            scheduler.advanceTimeBy(1)
            awaitUntilAdvancing(scheduler, "A logs the neighbor up") {
                a.events.value.any { it.text == "neighbor up: 2" }
            }

            // Queue a frame directly in A's store, as a failed send would.
            aStore.save(strandedFrame(), emptySet(), System.currentTimeMillis())
            assertFalse(
                "B must not have the frame before any replay tick",
                b.messages.value.any { it.text == "stranded" },
            )

            // Cross one 5-second replay interval in virtual time; the socket
            // write itself still happens on a real IO thread.
            scheduler.advanceTimeBy(6_000)
            awaitUntilAdvancing(scheduler, "B receives the queued frame") {
                b.messages.value.any { it.text == "stranded" }
            }
        } finally {
            a.stop()
            b.stop()
        }
    }

    private fun strandedFrame(): Frame = Frame(
        version = 1,
        type = FrameType.MSG,
        flags = 0,
        nodeId = 1,
        seq = 99,
        ttl = 8,
        payload = "stranded".toByteArray(),
    )

    private fun newNode(id: Int): AirMeshNode = AirMeshNode(
        localNodeId = id,
        transport = TcpMeshTransport(localNodeId = id),
        eventLog = EventLog(),
        storeFactory = { send -> StoreAndForward(store = InMemoryPacketStore(), send = send) },
    )

    /** Real-time wait that also advances [scheduler] so virtual coroutines run. */
    private suspend fun awaitUntilAdvancing(
        scheduler: TestCoroutineScheduler,
        description: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out after ${timeoutMs}ms waiting for $description")
            }
            scheduler.advanceTimeBy(1)
            delay(POLL_MS)
        }
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
