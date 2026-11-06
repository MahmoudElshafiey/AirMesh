package com.airmesh.node

import com.airmesh.RecordingDispatcher
import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.StoreAndForward
import com.airmesh.transport.TcpMeshTransport
import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Threading tests for [AirMeshNode.sendText]: the UI calls it from the main
 * thread; the socket write must happen on the node's background dispatcher,
 * not there. JVM tests cannot throw NetworkOnMainThreadException, so these
 * assert on recorded thread names instead of catching the Android exception.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AirMeshNodeThreadingTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val FIXED_TIME = 1_700_000_000_000L
        const val POLL_MS = 5L
    }

    @Test
    fun sendTextFromTheMainThreadWritesOnTheBackgroundDispatcher() = runBlocking {
        // A pool like Dispatchers.IO: the accept loop and each socket reader
        // block in server.accept()/input.read() for the life of the link and pin
        // a thread each, so writes need spare threads on the same dispatcher.
        val ioExecutor = Executors.newFixedThreadPool(4) { r -> Thread(r, "mesh-io") }
        val ioDispatcher = RecordingDispatcher(ioExecutor.asCoroutineDispatcher())
        val fakeMain = newSingleThreadContext("fake-main")

        // A: node and transport share the recording dispatcher. B is plain IO.
        val aStore = InMemoryPacketStore()
        val a = AirMeshNode(
            localNodeId = 1,
            transport = TcpMeshTransport(localNodeId = 1, dispatcher = ioDispatcher),
            eventLog = EventLog(clock = { FIXED_TIME }),
            storeFactory = { send -> StoreAndForward(store = aStore, send = send) },
            clock = { FIXED_TIME },
            dispatcher = ioDispatcher,
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

            ioDispatcher.clearRecordedThreads()
            withContext(fakeMain) {
                assertTrue(a.sendText("hello from main"))
            }

            awaitUntil("B receives the message") {
                b.messages.value.any { it.text == "hello from main" }
            }

            assertTrue(
                "sendText must hop to the background dispatcher",
                ioDispatcher.recordedThreads.isNotEmpty(),
            )
            assertFalse(
                "socket writes must not run on the caller's thread",
                ioDispatcher.recordedThreads.contains("fake-main"),
            )
        } finally {
            a.stop()
            b.stop()
            fakeMain.close()
            ioExecutor.shutdown()
        }
    }

    @Test
    fun failedSendDoesNotThrowAndKeepsTheFrameQueuedForReplay() = runBlocking {
        val aStore = InMemoryPacketStore()
        val a = newNodeWithStore(1, aStore)

        try {
            a.start()

            // B joins first so the failure is a real transient one: the link
            // goes down, the send cannot reach anyone, then the link returns.
            val b = newNode(2)
            try {
                b.start()
                val port = a.transport.listen(0)
                b.transport.connect(HOST, port)
                awaitUntil("A and B handshake") {
                    a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
                }

                // Transient failure: B drops off the mesh.
                b.stop()
                awaitUntil("A notices that B is gone") {
                    a.neighbors.value.isEmpty()
                }

                // The send must not throw into the caller...
                assertTrue(a.sendText("stranded"))
                // ...and the frame must stay in the store-and-forward queue.
                awaitUntil("the frame is queued in A's store") {
                    aStore.all.any { it.frame.payload.contentEquals("stranded".toByteArray()) }
                }

                // B comes back; the neighbor-up replay delivers the stored frame.
                b.start()
                val port2 = b.transport.listen(0)
                a.transport.connect(HOST, port2)
                awaitUntil("A and B handshake again") {
                    a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
                }
                awaitUntil("B receives the stranded frame from store-and-forward") {
                    b.messages.value.any { it.text == "stranded" }
                }
            } finally {
                b.stop()
            }
        } finally {
            a.stop()
        }
    }

    private fun newNode(id: Int): AirMeshNode = newNodeWithStore(id, InMemoryPacketStore())

    private fun newNodeWithStore(id: Int, store: InMemoryPacketStore): AirMeshNode = AirMeshNode(
        localNodeId = id,
        transport = TcpMeshTransport(localNodeId = id),
        eventLog = EventLog(clock = { FIXED_TIME }),
        // Same clock as the node/Router: a mismatch would make StoreAndForward
        // treat freshly saved frames as expired on replay.
        storeFactory = { send -> StoreAndForward(store = store, send = send, clock = { FIXED_TIME }) },
        clock = { FIXED_TIME },
    )

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
