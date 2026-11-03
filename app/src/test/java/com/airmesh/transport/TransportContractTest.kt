package com.airmesh.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the routing-facing contract can be implemented and used on the
 * JVM without Android. The fake here is test-only; the real transports come
 * later.
 */
class TransportContractTest {

    private class FakeTransport : Transport {

        private val packets = MutableSharedFlow<ReceivedPacket>(
            replay = 1,
            extraBufferCapacity = 16,
        )
        private val _neighbors = MutableStateFlow<Set<Int>>(emptySet())

        override val incoming: Flow<ReceivedPacket> = packets
        override val neighbors: StateFlow<Set<Int>> = _neighbors

        val broadcasts = mutableListOf<ByteArray>()
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        var running = false

        override suspend fun broadcast(bytes: ByteArray) {
            broadcasts += bytes
        }

        override suspend fun send(neighborId: Int, bytes: ByteArray) {
            sent += neighborId to bytes
        }

        override fun start() {
            running = true
            _neighbors.value = setOf(1, 2)
        }

        override fun stop() {
            running = false
            _neighbors.value = emptySet()
        }

        suspend fun receive(packet: ReceivedPacket) {
            packets.emit(packet)
        }
    }

    @Test
    fun startProvidesNeighborsAndStopClearsThem() {
        val transport = FakeTransport()
        assertTrue(transport.neighbors.value.isEmpty())

        transport.start()
        assertTrue(transport.running)
        assertEquals(setOf(1, 2), transport.neighbors.value)

        transport.stop()
        assertTrue(!transport.running)
        assertTrue(transport.neighbors.value.isEmpty())
    }

    @Test
    fun incomingEmitsSenderIdAndBytes() = runBlocking {
        val transport = FakeTransport()
        transport.receive(ReceivedPacket(senderId = 42, bytes = byteArrayOf(0x01, 0x02)))

        val packet = transport.incoming.first()

        assertEquals(42, packet.senderId)
        assertArrayEquals(byteArrayOf(0x01, 0x02), packet.bytes)
    }

    @Test
    fun broadcastReceivesExactBytes() = runBlocking {
        val transport = FakeTransport()
        val bytes = byteArrayOf(0x0A, 0x0B)

        transport.broadcast(bytes)

        assertEquals(1, transport.broadcasts.size)
        assertArrayEquals(bytes, transport.broadcasts.single())
    }

    @Test
    fun sendReceivesNeighborIdAndBytes() = runBlocking {
        val transport = FakeTransport()
        val bytes = byteArrayOf(0x0C)

        transport.send(neighborId = 7, bytes = bytes)

        assertEquals(1, transport.sent.size)
        assertEquals(7, transport.sent.single().first)
        assertArrayEquals(bytes, transport.sent.single().second)
    }
}
