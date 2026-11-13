package com.airmesh.node

import com.airmesh.protocol.DirectPayload
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for direct (one-to-one) messages over real localhost transports:
 * delivery, receipts, relay-only behavior on the middle node, store-and-forward
 * to an offline recipient, dedup and malformed-payload handling (TASK D1 part 4).
 */
class AirMeshNodeDirectTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val FIXED_TIME = 1_700_000_000_000L
        const val POLL_MS = 5L
    }

    @Test
    fun directMessageIsDeliveredOnceAndTheReceiptArrives() = runBlocking {
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

            val result = a.sendDirect(2, "dm hello")
            assertTrue("sendDirect must succeed", result != null)
            assertTrue("B accepted the first flood", result!!.handed)

            awaitUntil("B receives the DM") {
                b.directMessages.value.any { it.text == "dm hello" }
            }
            val dm = b.directMessages.value.single()
            assertEquals(1, dm.fromNodeId)
            assertEquals(result.seq, dm.seq)
            assertEquals(FIXED_TIME, dm.receivedAt)

            awaitUntil("A gets the receipt") {
                a.deliveryReceipts.value.any { it.ackedSeq == result.seq }
            }
            val receipt = a.deliveryReceipts.value.single()
            assertEquals(2, receipt.fromNodeId)
            assertEquals(result.seq, receipt.ackedSeq)
            assertEquals(FIXED_TIME, receipt.receivedAt)

            // Exactly once: no duplicate within the window.
            val duplicatedDm = awaitUntilOrNull(500) {
                b.directMessages.value.count { it.text == "dm hello" } > 1
            }
            assertFalse("the DM must be delivered exactly once", duplicatedDm)
            val duplicatedAck = awaitUntilOrNull(500) {
                a.deliveryReceipts.value.count { it.ackedSeq == result.seq } > 1
            }
            assertFalse("exactly one receipt per (origin, seq)", duplicatedAck)
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun dmThroughTheMiddleIsNotSurfacedThereAndStopsAtTheRecipient() = runBlocking {
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

            val result = a.sendDirect(3, "to C only")!!
            assertTrue("B accepted the first flood", result.handed)

            awaitUntil("C receives the DM via B") {
                c.directMessages.value.any { it.text == "to C only" }
            }
            val dm = c.directMessages.value.single()
            assertEquals(1, dm.fromNodeId)
            assertEquals(result.seq, dm.seq)

            // By the time A holds the receipt, B has already seen both the DM
            // (it relayed it) and C's ack — so "not surfaced" is checkable now.
            awaitUntil("A gets the receipt from C") {
                a.deliveryReceipts.value.any { it.ackedSeq == result.seq }
            }
            val receipt = a.deliveryReceipts.value.single()
            assertEquals(3, receipt.fromNodeId)

            assertTrue("the middle node must not surface someone else's DM", b.directMessages.value.isEmpty())

            // Frame count via TX logs: B relayed A's DM exactly once...
            awaitUntil("B has logged the relay") {
                b.events.value.count { it.text.startsWith("TX DIRECT node=1 ") } == 1
            }
            // ...and C never forwarded it further (C is the recipient).
            val cForwarded = awaitUntilOrNull(500) {
                c.events.value.any { it.text.startsWith("TX DIRECT node=1 ") }
            }
            assertFalse("the recipient must not forward a DM addressed to it", cForwarded)
        } finally {
            a.stop()
            b.stop()
            c.stop()
        }
    }

    @Test
    fun acksForOthersNeverSurfaceOnTheRelay() = runBlocking {
        val a = newNode(1)
        val b = newNode(2) // relay in the middle of the A-B-C line
        val c = newNode(3)

        try {
            a.start()
            b.start()
            c.start()

            val portB = b.transport.listen(0)
            a.transport.connect(HOST, portB)
            c.transport.connect(HOST, portB)
            awaitUntil("A and C are connected through B") {
                b.neighbors.value == setOf(1, 3) &&
                    a.neighbors.value == setOf(2) &&
                    c.neighbors.value == setOf(2)
            }

            val result = a.sendDirect(3, "receipt for A only")!!

            // The receipt can only reach A through B, so once it is there the
            // ack has definitely passed the relay.
            awaitUntil("A gets the receipt via B") {
                a.deliveryReceipts.value.any { it.ackedSeq == result.seq }
            }

            val surfaced = awaitUntilOrNull(500) { b.deliveryReceipts.value.isNotEmpty() }
            assertFalse("B must not surface receipts addressed to A", surfaced)
        } finally {
            a.stop()
            b.stop()
            c.stop()
        }
    }

    @Test
    fun dmToAnOfflineNodeIsDeliveredAfterItConnectsAndTheReceiptFollows() = runBlocking {
        // Real clock: the store prunes by wall-clock age, so FIXED_TIME frames
        // would look 10+ minutes old and be deleted before the replay.
        val a = newNode(1, clock = System::currentTimeMillis)
        val c = newNode(3, clock = System::currentTimeMillis)

        try {
            a.start()
            c.start()

            // No link yet: the frame is queued for store-and-forward only.
            val result = a.sendDirect(3, "later")!!
            assertFalse("no neighbor accepted the write", result.handed)

            val port = a.transport.listen(0)
            c.transport.connect(HOST, port)

            // The neighbor-up replay delivers it; C's ack comes back on the same link.
            awaitUntil("C receives the replayed DM") {
                c.directMessages.value.any { it.text == "later" }
            }
            assertEquals(1, c.directMessages.value.single().fromNodeId)

            awaitUntil("A gets the receipt after delivery") {
                a.deliveryReceipts.value.any { it.ackedSeq == result.seq }
            }
            assertEquals(3, a.deliveryReceipts.value.single().fromNodeId)
        } finally {
            a.stop()
            c.stop()
        }
    }

    @Test
    fun duplicateDirectFrameIsDeliveredExactlyOnce() = runBlocking {
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

            val result = a.sendDirect(2, "dup me")!!
            awaitUntil("B receives the DM") {
                b.directMessages.value.any { it.text == "dup me" }
            }

            // Re-inject the exact same frame bytes: B's dedup must drop the copy.
            val duplicate = FrameCodec.encode(
                Frame(
                    version = 1,
                    type = FrameType.DIRECT,
                    flags = 0,
                    nodeId = 1,
                    seq = result.seq,
                    ttl = AirMeshNode.INITIAL_TTL,
                    payload = DirectPayload(destNodeId = 2, text = "dup me").encode(),
                ),
            )
            assertTrue(a.transport.trySend(2, duplicate))

            awaitUntil("A gets the receipt") {
                a.deliveryReceipts.value.any { it.ackedSeq == result.seq }
            }

            val duplicatedDm = awaitUntilOrNull(500) {
                b.directMessages.value.count { it.text == "dup me" } > 1
            }
            assertFalse("a duplicate frame must not be delivered twice", duplicatedDm)
            val duplicatedAck = awaitUntilOrNull(500) {
                a.deliveryReceipts.value.count { it.ackedSeq == result.seq } > 1
            }
            assertFalse("exactly one ack per (origin, seq)", duplicatedAck)
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun malformedDirectPayloadIsDroppedWithoutCrashing() = runBlocking {
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

            // A frame with a valid CRC whose DIRECT payload has an unknown
            // version: the frame decodes fine, but the payload must be rejected.
            // Sent raw so it bypasses A's own router (which would never craft
            // such a frame).
            val badFrame = FrameCodec.encode(
                Frame(
                    version = 1,
                    type = FrameType.DIRECT,
                    flags = 0,
                    nodeId = 99,
                    seq = 7,
                    ttl = AirMeshNode.INITIAL_TTL,
                    payload = byteArrayOf(2, 0x00, 0x05), // version 2: unsupported
                ),
            )
            assertTrue(a.transport.trySend(2, badFrame))

            awaitUntil("B logs the drop") {
                b.events.value.any { it.text.contains("DIRECT from 99 dropped") }
            }
            assertTrue(b.directMessages.value.isEmpty())

            // The node must still work: a real DM is delivered and acked.
            val result = a.sendDirect(2, "still alive")!!
            awaitUntil("B receives the follow-up DM") {
                b.directMessages.value.any { it.text == "still alive" }
            }
            assertEquals(listOf("still alive"), b.directMessages.value.map { it.text })
            awaitUntil("A gets the receipt for the follow-up") {
                a.deliveryReceipts.value.any { it.ackedSeq == result.seq }
            }
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun selfSendAndInvalidDirectSendsAreRejected() = runBlocking {
        val a = newNode(1)

        try {
            a.start()

            assertNull("self-send must be rejected", a.sendDirect(1, "to me"))
            assertNull("destination 0 is out of range", a.sendDirect(0, "x"))
            assertNull("destination 65536 is out of range", a.sendDirect(65536, "x"))
            assertNull("empty text must be rejected", a.sendDirect(2, ""))
            assertNull(
                "oversized text must be rejected",
                a.sendDirect(2, "a".repeat(DirectPayload.MAX_TEXT_BYTES + 1)),
            )

            val events = a.events.value.map { it.text }
            assertTrue(events.any { it.contains("direct send rejected: self-send") })
            assertTrue(events.any { it.contains("destination 0 out of range") })
            assertTrue(events.any { it.contains("destination 65536 out of range") })
            assertTrue(events.any { it.contains("direct send rejected: empty text") })
            assertTrue(events.any { it.contains("UTF-8 bytes exceed") })

            // Rejections happen before origination: the first valid send gets seq 1.
            val ok = a.sendDirect(2, "valid")!!
            assertEquals(1, ok.seq)
            assertFalse("no neighbor is connected yet", ok.handed)
        } finally {
            a.stop()
        }
    }

    private fun newNode(id: Int, clock: () -> Long = { FIXED_TIME }): AirMeshNode = AirMeshNode(
        localNodeId = id,
        transport = TcpMeshTransport(localNodeId = id),
        eventLog = EventLog(clock = clock),
        storeFactory = { send ->
            StoreAndForward(store = InMemoryPacketStore(), send = send)
        },
        clock = clock,
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
