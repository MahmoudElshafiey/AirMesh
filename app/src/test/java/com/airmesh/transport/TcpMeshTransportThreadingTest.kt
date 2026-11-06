package com.airmesh.transport

import com.airmesh.RecordingDispatcher
import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Threading tests for [TcpMeshTransport.send] and [TcpMeshTransport.broadcast]:
 * the socket write must run on the injected dispatcher, never on the caller's
 * thread. On Android the caller is the main thread and a write there throws
 * NetworkOnMainThreadException — JVM tests cannot catch that exception, so
 * these assert on recorded thread names instead of catching it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TcpMeshTransportThreadingTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val POLL_MS = 5L
    }

    @Test
    fun sendWritesOnTheInjectedDispatcherNotTheCallerThread() = runBlocking {
        // A pool like Dispatchers.IO: each socket reader blocks in input.read()
        // for the life of the link and pins a thread, so writes need spare
        // threads on the same dispatcher.
        val ioExecutor = Executors.newFixedThreadPool(4) { r -> Thread(r, "mesh-io") }
        val ioDispatcher = RecordingDispatcher(ioExecutor.asCoroutineDispatcher())
        val fakeMain = newSingleThreadContext("fake-main")

        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val a = TcpMeshTransport(localNodeId = 1, dispatcher = ioDispatcher)
            val b = TcpMeshTransport(localNodeId = 2)
            val received = CopyOnWriteArrayList<ReceivedPacket>()

            try {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    b.incoming.collect { received += it }
                }

                val port = b.listen(0)
                withContext(fakeMain) { a.connect(HOST, port) }
                awaitUntil("both sides handshook") {
                    a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
                }

                ioDispatcher.clearRecordedThreads()
                val bytes = FrameCodec.encode(msgFrame(seq = 1))
                withContext(fakeMain) { a.send(2, bytes) }

                awaitUntil("B received the frame") {
                    received.any { it.bytes.contentEquals(bytes) }
                }
                assertEquals("the frame must arrive exactly once", 1, received.size)

                assertTrue(
                    "the write must run on the injected dispatcher",
                    ioDispatcher.recordedThreads.isNotEmpty(),
                )
                assertFalse(
                    "the write must not run on the caller's thread",
                    ioDispatcher.recordedThreads.contains("fake-main"),
                )
            } finally {
                a.stop()
                b.stop()
                scope.cancel()
            }
        } finally {
            fakeMain.close()
            ioExecutor.shutdown()
        }
    }

    @Test
    fun broadcastWritesOnTheInjectedDispatcherNotTheCallerThread() = runBlocking {
        // A pool like Dispatchers.IO: each socket reader blocks in input.read()
        // for the life of the link and pins a thread, so writes need spare
        // threads on the same dispatcher.
        val ioExecutor = Executors.newFixedThreadPool(4) { r -> Thread(r, "mesh-io") }
        val ioDispatcher = RecordingDispatcher(ioExecutor.asCoroutineDispatcher())
        val fakeMain = newSingleThreadContext("fake-main")

        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val a = TcpMeshTransport(localNodeId = 1, dispatcher = ioDispatcher)
            val b = TcpMeshTransport(localNodeId = 2)
            val received = CopyOnWriteArrayList<ReceivedPacket>()

            try {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    b.incoming.collect { received += it }
                }

                val port = b.listen(0)
                withContext(fakeMain) { a.connect(HOST, port) }
                awaitUntil("both sides handshook") {
                    a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
                }

                ioDispatcher.clearRecordedThreads()
                val bytes = FrameCodec.encode(msgFrame(seq = 2))
                withContext(fakeMain) { a.broadcast(bytes) }

                awaitUntil("B received the broadcast") {
                    received.any { it.bytes.contentEquals(bytes) }
                }
                assertEquals("the frame must arrive exactly once", 1, received.size)

                assertTrue(
                    "the write must run on the injected dispatcher",
                    ioDispatcher.recordedThreads.isNotEmpty(),
                )
                assertFalse(
                    "the write must not run on the caller's thread",
                    ioDispatcher.recordedThreads.contains("fake-main"),
                )
            } finally {
                a.stop()
                b.stop()
                scope.cancel()
            }
        } finally {
            fakeMain.close()
            ioExecutor.shutdown()
        }
    }

    private fun msgFrame(seq: Int): Frame = Frame(
        version = 1,
        type = FrameType.MSG,
        flags = 0,
        nodeId = 1,
        seq = seq,
        ttl = 8,
        payload = "threading test".toByteArray(),
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
