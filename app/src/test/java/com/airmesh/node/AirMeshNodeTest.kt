package com.airmesh.node

import com.airmesh.protocol.FrameCodec
import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.StoreAndForward
import com.airmesh.transport.TcpMeshTransport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the node wiring: two real localhost transports plus an
 * in-memory store, exactly like the transport end-to-end test but through the
 * [AirMeshNode] facade.
 */
class AirMeshNodeTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val FIXED_TIME = 1_700_000_000_000L
        const val POLL_MS = 5L
    }

    @Test
    fun sentTextIsDeliveredOnceOnThePeer() = runBlocking {
        val a = newNode(1)
        val b = newNode(2)

        try {
            a.start()
            b.start()

            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("A and B handshake") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }

            assertTrue(a.sendText("hello mesh"))

            awaitUntil("B receives the message") {
                b.messages.value.any { it.text == "hello mesh" }
            }

            val incoming = b.messages.value.single { it.text == "hello mesh" }
            assertEquals(1, incoming.senderId)
            assertFalse(incoming.outgoing)
            assertEquals(FIXED_TIME, incoming.timestamp)
            assertEquals(AirMeshNode.INITIAL_TTL, incoming.ttl)

            val outgoing = a.messages.value.single { it.text == "hello mesh" }
            assertEquals(1, outgoing.senderId)
            assertTrue(outgoing.outgoing)
            assertEquals(FIXED_TIME, outgoing.timestamp)

            val duplicated = awaitUntilOrNull(300) {
                b.messages.value.count { it.text == "hello mesh" } > 1
            }
            assertFalse("the peer must deliver the message exactly once", duplicated)
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun textLongerThanMaxPayloadIsRejected() = runBlocking {
        val node = newNode(3)

        try {
            node.start()

            // MAX_PAYLOAD counts bytes, not characters.
            assertTrue(node.sendText("x".repeat(FrameCodec.MAX_PAYLOAD)))
            assertEquals(1, node.messages.value.size)

            assertFalse(node.sendText("x".repeat(FrameCodec.MAX_PAYLOAD + 1)))
            // Two bytes per character: 513 chars = 1026 UTF-8 bytes.
            assertFalse(node.sendText("é".repeat(FrameCodec.MAX_PAYLOAD / 2 + 1)))

            assertEquals("rejected texts must not enter the message list", 1, node.messages.value.size)
            assertTrue(node.events.value.any { it.text.contains("send rejected") })
        } finally {
            node.stop()
        }
    }

    @Test
    fun stopCancelsCoroutinesAndStopsDelivery() = runBlocking {
        val a = newNode(1)
        val b = newNode(2)

        try {
            a.start()
            b.start()

            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("A and B handshake") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }

            a.stop()
            awaitUntil("B notices that A is gone") {
                b.neighbors.value.isEmpty()
            }

            assertTrue(b.sendText("after stop"))
            val delivered = awaitUntilOrNull(500) {
                a.messages.value.any { it.text == "after stop" }
            }
            assertFalse("a stopped node must not deliver messages", delivered)
            assertTrue(a.messages.value.isEmpty())
            assertTrue(a.events.value.any { it.text.contains("node 1 stopped") })
        } finally {
            a.stop()
            b.stop()
        }
    }

    private fun newNode(id: Int): AirMeshNode = AirMeshNode(
        localNodeId = id,
        transport = TcpMeshTransport(localNodeId = id),
        eventLog = EventLog(clock = { FIXED_TIME }),
        storeFactory = { send ->
            StoreAndForward(store = InMemoryPacketStore(), send = send)
        },
        clock = { FIXED_TIME },
    )

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

    private suspend fun awaitUntilOrNull(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!condition()) delay(POLL_MS)
        true
    } ?: false
}
