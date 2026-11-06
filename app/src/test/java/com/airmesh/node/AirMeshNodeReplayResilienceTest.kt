package com.airmesh.node

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameType
import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.PacketStore
import com.airmesh.routing.StoredFrame
import com.airmesh.routing.StoreAndForward
import com.airmesh.transport.ConnectOutcome
import com.airmesh.transport.TcpMeshTransport
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The replay coroutines must survive a store failure: one exception in
 * `onNeighborAppeared` (for example a Room hiccup) used to kill both the
 * neighbor collector and the periodic replay loop forever, leaving queued
 * frames undelivered with no log line at all — the exact silence seen on real
 * devices after "neighbor up".
 *
 * The store here fails its first two `pendingFor` calls: one consumed by the
 * neighbor-up trigger (collector), one by the first periodic tick. After that
 * it works, and a frame queued in between must still be delivered to B.
 */
class AirMeshNodeReplayResilienceTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val POLL_MS = 5L
    }

    @Test
    fun replayCoroutinesSurviveStoreFailures() = runBlocking {
        val flaky = FlakyStore(InMemoryPacketStore(), failingCalls = 2)
        val a = AirMeshNode(
            localNodeId = 1,
            transport = TcpMeshTransport(localNodeId = 1),
            eventLog = EventLog(),
            storeFactory = { send -> StoreAndForward(store = flaky, send = send) },
            replayIntervalMs = 200,
        )
        val b = newNode(2)
        try {
            a.start()
            b.start()

            val port = a.transport.listen(0)
            assertTrue("connect failed", b.transport.connect(HOST, port) is ConnectOutcome.Success)
            awaitUntil("link up") { a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1) }

            // The neighbor-up trigger hits the first store failure; the node
            // must log it instead of dying silently.
            awaitUntil("A logs the first replay failure") {
                a.events.value.any { it.text.startsWith("replay failed for neighbor 2") }
            }

            // Queue a frame the way a failed send would; later replays must deliver it.
            flaky.delegate.save(strandedFrame(), emptySet(), System.currentTimeMillis())

            awaitUntil("B receives the queued frame despite two store failures") {
                b.messages.value.any { it.text == "stranded" }
            }

            // The neighbor collector must still be alive to log down/up cycles.
            b.stop()
            awaitUntil("A logs the neighbor going down (collector survived)") {
                a.events.value.any { it.text == "neighbor down: 2" }
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

    /** Delegates to [delegate] but throws IllegalStateException from `pendingFor` for the first [failingCalls] calls. */
    private class FlakyStore(val delegate: InMemoryPacketStore, failingCalls: Int) : PacketStore by delegate {
        private val remaining = AtomicInteger(failingCalls)

        override suspend fun pendingFor(neighborId: Int, notBefore: Long): List<StoredFrame> {
            if (remaining.decrementAndGet() >= 0) throw IllegalStateException("simulated store failure")
            return delegate.pendingFor(neighborId, notBefore)
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
