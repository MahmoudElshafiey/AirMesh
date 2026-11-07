package com.airmesh.node

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
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
 * JVM tests for location sharing through the node: real localhost transports,
 * same harness as [AirMeshNodeTest]. Covers direct delivery (exactly once),
 * multi-hop TTL decrement on an A-B-C line, and malformed-payload drops.
 */
class AirMeshNodeLocationTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val FIXED_TIME = 1_700_000_000_000L
        const val POLL_MS = 5L
    }

    @Test
    fun sentLocationIsDeliveredExactlyOnceOnThePeer() = runBlocking {
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

            assertTrue(a.sendLocation(30_044_420, 31_235_760, 12))

            awaitUntil("B receives the location") {
                b.locations.value.isNotEmpty()
            }

            val incoming = b.locations.value.single()
            assertEquals(1, incoming.senderId)
            assertEquals(30_044_420, incoming.latitudeE6)
            assertEquals(31_235_760, incoming.longitudeE6)
            assertEquals(12, incoming.accuracyMeters)
            assertEquals(FIXED_TIME, incoming.timestamp)
            // Direct delivery: no hop was spent yet.
            assertEquals(AirMeshNode.INITIAL_TTL, incoming.ttl)

            // The sender sees its own outgoing item in the same flow.
            val outgoing = a.locations.value.single()
            assertEquals(1, outgoing.senderId)
            assertEquals(AirMeshNode.INITIAL_TTL, outgoing.ttl)

            assertTrue(a.events.value.any { it.text.contains("originated LOCATION") })

            // Location items stay in their own flow: no text was involved.
            assertTrue(b.messages.value.isEmpty())

            val duplicated = awaitUntilOrNull(300) { b.locations.value.size > 1 }
            assertFalse("the peer must deliver the location exactly once", duplicated)
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun locationTravelsTheLineWithTtlDecrement() = runBlocking {
        val a = newNode(1)
        val b = newNode(2) // relay in the middle of the A-B-C line
        val c = newNode(3)

        try {
            a.start()
            b.start()
            c.start()

            // B listens once; both ends connect to it (the proven hub pattern).
            val portB = b.transport.listen(0)
            a.transport.connect(HOST, portB)
            c.transport.connect(HOST, portB)
            awaitUntil("A and C are connected through B") {
                b.neighbors.value == setOf(1, 3) &&
                    a.neighbors.value == setOf(2) &&
                    c.neighbors.value == setOf(2)
            }

            assertTrue(a.sendLocation(-33_868_800, -151_209_300, 40))

            awaitUntil("C receives the location via B") {
                c.locations.value.isNotEmpty()
            }

            val atRelay = b.locations.value.single()
            assertEquals(1, atRelay.senderId)
            // The relay is one hop from A: full TTL.
            assertEquals(AirMeshNode.INITIAL_TTL, atRelay.ttl)

            val received = c.locations.value.single()
            assertEquals(1, received.senderId)
            assertEquals(-33_868_800, received.latitudeE6)
            assertEquals(-151_209_300, received.longitudeE6)
            assertEquals(40, received.accuracyMeters)
            // One hop through B: TTL decremented exactly once.
            assertEquals(AirMeshNode.INITIAL_TTL - 1, received.ttl)
        } finally {
            a.stop()
            b.stop()
            c.stop()
        }
    }

    @Test
    fun malformedLocationPayloadIsDroppedAndLogged() = runBlocking {
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

            // A frame with a valid CRC whose LOCATION payload is 9 bytes: the
            // frame decodes fine, but the payload must be rejected. Sent raw so
            // it bypasses A's own router (which would never craft such a frame).
            val badFrame = FrameCodec.encode(
                Frame(
                    version = 1,
                    type = FrameType.LOCATION,
                    flags = 0,
                    nodeId = 1,
                    seq = 7,
                    ttl = AirMeshNode.INITIAL_TTL,
                    payload = ByteArray(9),
                ),
            )
            assertTrue(a.transport.trySend(2, badFrame))

            awaitUntil("B logs the drop") {
                b.events.value.any { it.text.contains("LOCATION from 1 dropped") }
            }
            assertTrue(b.locations.value.isEmpty())
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
