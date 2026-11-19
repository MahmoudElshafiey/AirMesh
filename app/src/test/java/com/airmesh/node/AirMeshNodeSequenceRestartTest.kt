package com.airmesh.node

import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.SequenceKeeper
import com.airmesh.routing.SequenceStorage
import com.airmesh.routing.StoreAndForward
import com.airmesh.transport.TcpMeshTransport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK T1 part B2: end-to-end proof over real localhost sockets that a restarted node never
 * reuses an origin seq. Node A sends 20 messages and a DM, is stopped (clean stop persists the
 * high-water mark) and rebuilt with the SAME storage; it then sends again. Node B — which was
 * never restarted, so its dedup window still holds every phase-1 seq — must deliver each message
 * exactly once and ack both DMs. Without the keeper A would restart at seq 1 and B would drop
 * the whole second batch as duplicates (evidence run2-A54/A55).
 */
class AirMeshNodeSequenceRestartTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val FIXED_TIME = 1_700_000_000_000L
        const val POLL_MS = 5L
    }

    /** In-memory [SequenceStorage] shared by both incarnations of node A (the "disk"). */
    private class SharedSeqStorage : SequenceStorage {
        @Volatile var raw: String? = null
            private set
        override fun readRaw(): String? = raw
        override fun write(value: Int) {
            raw = value.toString()
        }
    }

    private fun newNode(id: Int, seqStorage: SequenceStorage?): AirMeshNode = AirMeshNode(
        localNodeId = id,
        transport = TcpMeshTransport(localNodeId = id),
        eventLog = EventLog(clock = { FIXED_TIME }),
        storeFactory = { send ->
            StoreAndForward(store = InMemoryPacketStore(), send = send)
        },
        clock = { FIXED_TIME },
        sequenceKeeper = seqStorage?.let { SequenceKeeper(it) },
    )

    @Test
    fun restartedNodeDeliversEveryMessageExactlyOnceAndBothDmsAreAcked() = runBlocking {
        val storage = SharedSeqStorage()
        val b = newNode(2, null) // B never restarts: its dedup window keeps every phase-1 seq.
        b.start()

        val phaseOne = (1..20).map { "m$it" }
        val phaseTwo = (1..5).map { "n$it" }

        try {
            // B listens once; both incarnations of A connect to it (the proven hub pattern).
            val portB = b.transport.listen(0)

            // --- Phase 1: the first process sends 20 messages and one DM. ---
            val a1 = newNode(1, storage)
            try {
                a1.start()
                a1.transport.connect(HOST, portB)
                awaitUntil("A and B handshake") {
                    a1.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
                }

                for (text in phaseOne) assertTrue("sendText must originate: $text", a1.sendText(text))
                awaitUntil("B has all 20 messages") {
                    b.messages.value.count { it.senderId == 1 && !it.outgoing } >= 20
                }

                val dm = a1.sendDirect(2, "dm one")!!
                assertTrue("B accepted the first DM", dm.handed)
                awaitUntil("B receives the first DM") {
                    b.directMessages.value.any { it.text == "dm one" }
                }
                awaitUntil("A gets the receipt for the first DM") {
                    a1.deliveryReceipts.value.any { it.ackedSeq == dm.seq }
                }
            } finally {
                // Clean stop: persists last used + margin to the shared storage.
                a1.stop()
            }

            // --- Phase 2: rebuild A with the same storage and send again. ---
            val a2 = newNode(1, storage)
            try {
                a2.start()
                a2.transport.connect(HOST, portB)
                awaitUntil("A' and B handshake") {
                    a2.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
                }

                for (text in phaseTwo) assertTrue("sendText must originate: $text", a2.sendText(text))
                awaitUntil("B has all 25 messages") {
                    b.messages.value.count { it.senderId == 1 && !it.outgoing } >= 25
                }

                val dm2 = a2.sendDirect(2, "dm two")!!
                assertTrue("B accepted the second DM", dm2.handed)
                awaitUntil("B receives the second DM") {
                    b.directMessages.value.any { it.text == "dm two" }
                }
                awaitUntil("A' gets the receipt for the second DM") {
                    a2.deliveryReceipts.value.any { it.ackedSeq == dm2.seq }
                }

                // Exactly once: every text appears exactly once on B...
                val texts = b.messages.value.filter { it.senderId == 1 && !it.outgoing }.map { it.text }
                assertEquals("all 25 messages delivered", (phaseOne + phaseTwo).size, texts.size)
                for (text in phaseOne + phaseTwo) {
                    assertEquals("each text exactly once: $text", 1, texts.count { it == text })
                }

                // ...and B never dropped one of A's frames as a duplicate.
                assertFalse(
                    "B must not have dropped any of A's frames as duplicates",
                    b.events.value.any {
                        it.text.contains("dropped duplicate") && it.text.contains("node=1 ")
                    },
                )
            } finally {
                a2.stop()
            }
        } finally {
            b.stop()
        }
    }

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
