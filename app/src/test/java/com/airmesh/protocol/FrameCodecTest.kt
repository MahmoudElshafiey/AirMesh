package com.airmesh.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FrameCodecTest {

    // --- Frame model -------------------------------------------------------

    @Test
    fun frameTypeWireValuesMatchSpec() {
        assertEquals(0, FrameType.MSG.wireValue)
        assertEquals(1, FrameType.LOCATION.wireValue)
        assertEquals(2, FrameType.ACK.wireValue)
        assertEquals(3, FrameType.HELLO.wireValue)

        assertEquals(FrameType.LOCATION, FrameType.fromWire(1))
        assertNull(FrameType.fromWire(4))
        assertNull(FrameType.fromWire(15))
    }

    // --- Encoding ----------------------------------------------------------

    @Test
    fun encodePacksHeaderBigEndian() {
        val encoded = FrameCodec.encode(
            Frame(
                version = 1,
                type = FrameType.ACK,
                flags = 0x81,
                nodeId = 0x1234,
                seq = 0xABCD,
                ttl = 5,
                payload = "hi".toByteArray(),
            ),
        )

        val expectedHeaderAndPayload = byteArrayOf(
            0x12,                  // version 1 (high nibble), type ACK = 2 (low nibble)
            0x81.toByte(),         // flags
            0x12, 0x34,            // nodeId
            0xAB.toByte(), 0xCD.toByte(), // seq
            0x05,                  // ttl
            0x00, 0x02,            // payload length
            0x68, 0x69,            // "hi"
        )

        assertEquals(13, encoded.size)
        assertArrayEquals(expectedHeaderAndPayload, encoded.copyOfRange(0, 11))

        val crc = Crc16.compute(encoded, 0, 11)
        assertArrayEquals(
            byteArrayOf((crc shr 8).toByte(), crc.toByte()),
            encoded.copyOfRange(11, 13),
        )
    }

    // --- Round trips -------------------------------------------------------

    @Test
    fun roundTripForEveryFrameType() {
        FrameType.entries.forEach { type ->
            val original = sampleFrame(type = type, payload = "payload-$type".toByteArray())
            val decoded = FrameCodec.decode(FrameCodec.encode(original)).getOrThrow()
            assertFrameEquals(original, decoded)
        }
    }

    @Test
    fun roundTripEmptyPayload() {
        val original = sampleFrame(payload = ByteArray(0))
        val encoded = FrameCodec.encode(original)

        assertEquals(FrameCodec.MIN_FRAME_SIZE, encoded.size)
        assertFrameEquals(original, FrameCodec.decode(encoded).getOrThrow())
    }

    @Test
    fun roundTripMaxPayload() {
        val payload = ByteArray(FrameCodec.MAX_PAYLOAD) { (it % 256).toByte() }
        val original = sampleFrame(payload = payload)
        val encoded = FrameCodec.encode(original)

        assertEquals(FrameCodec.MIN_FRAME_SIZE + FrameCodec.MAX_PAYLOAD, encoded.size)
        assertFrameEquals(original, FrameCodec.decode(encoded).getOrThrow())
    }

    @Test
    fun roundTripFieldBoundaries() {
        listOf(
            sampleFrame(version = 0, flags = 0, nodeId = 0, seq = 0, ttl = 0, payload = ByteArray(0)),
            sampleFrame(
                version = 15,
                flags = 255,
                nodeId = 65535,
                seq = 65535,
                ttl = 255,
                payload = byteArrayOf(0xFF.toByte()),
            ),
        ).forEach { original ->
            val decoded = FrameCodec.decode(FrameCodec.encode(original)).getOrThrow()
            assertFrameEquals(original, decoded)
        }
    }

    // --- Rejections --------------------------------------------------------

    @Test
    fun tooShortInputRejected() {
        assertFailure(DecodeError.TOO_SHORT, FrameCodec.decode(ByteArray(0)))
        assertFailure(
            DecodeError.TOO_SHORT,
            FrameCodec.decode(ByteArray(FrameCodec.MIN_FRAME_SIZE - 1)),
        )
    }

    @Test
    fun truncatedFrameRejected() {
        val nonEmpty = FrameCodec.encode(sampleFrame(payload = "hello".toByteArray()))
        assertFailure(
            DecodeError.LENGTH_MISMATCH,
            FrameCodec.decode(nonEmpty.copyOf(nonEmpty.size - 1)),
        )

        // An empty-payload frame is exactly MIN_FRAME_SIZE bytes, so cutting one
        // byte leaves it too short to even contain header + CRC.
        val empty = FrameCodec.encode(sampleFrame(payload = ByteArray(0)))
        assertFailure(
            DecodeError.TOO_SHORT,
            FrameCodec.decode(empty.copyOf(empty.size - 1)),
        )
    }

    @Test
    fun declaredLengthMismatchRejected() {
        val bytes = FrameCodec.encode(sampleFrame(payload = "hello".toByteArray()))
        bytes[8] = (bytes[8] - 1).toByte() // declare 4 bytes while 5 are present

        assertFailure(DecodeError.LENGTH_MISMATCH, FrameCodec.decode(bytes))
    }

    @Test
    fun payloadTooLargeRejected() {
        val declaredLength = FrameCodec.MAX_PAYLOAD + 1
        val bytes = ByteArray(FrameCodec.MIN_FRAME_SIZE + declaredLength)
        bytes[0] = 0x10 // version 1, type MSG
        bytes[6] = 1 // ttl
        bytes[7] = (declaredLength shr 8).toByte()
        bytes[8] = declaredLength.toByte()
        writeCrc(bytes)

        assertFailure(DecodeError.PAYLOAD_TOO_LARGE, FrameCodec.decode(bytes))
    }

    @Test
    fun badCrcRejected() {
        val bytes = FrameCodec.encode(sampleFrame())
        bytes[FrameCodec.HEADER_SIZE] = (bytes[FrameCodec.HEADER_SIZE].toInt() xor 0x01).toByte()

        assertFailure(DecodeError.BAD_CRC, FrameCodec.decode(bytes))
    }

    @Test
    fun unknownTypeRejected() {
        val bytes = FrameCodec.encode(sampleFrame(payload = ByteArray(0)))
        bytes[0] = ((bytes[0].toInt() and 0xF0) or 0x0F).toByte() // type 15
        writeCrc(bytes)

        assertFailure(DecodeError.UNKNOWN_TYPE, FrameCodec.decode(bytes))
    }

    @Test
    fun singleBitFlipInEveryPositionRejected() {
        val encoded = FrameCodec.encode(sampleFrame(payload = "AirMesh".toByteArray()))

        for (byteIndex in encoded.indices) {
            for (bit in 0 until 8) {
                val corrupted = encoded.copyOf()
                corrupted[byteIndex] = (corrupted[byteIndex].toInt() xor (1 shl bit)).toByte()
                assertTrue(
                    "flipping bit $bit of byte $byteIndex was not rejected",
                    FrameCodec.decode(corrupted).isFailure,
                )
            }
        }
    }

    @Test
    fun encodeRejectsOversizedPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(sampleFrame(payload = ByteArray(FrameCodec.MAX_PAYLOAD + 1)))
        }
    }

    @Test
    fun encodeRejectsOutOfRangeFields() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(sampleFrame(version = 16))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(sampleFrame(flags = 256))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(sampleFrame(nodeId = 65536))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(sampleFrame(seq = -1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(sampleFrame(ttl = 256))
        }
    }

    @Test
    fun randomInputReturnsTypedResult() {
        val random = Random(42)
        repeat(200) {
            val bytes = ByteArray(random.nextInt(41))
            random.nextBytes(bytes)

            FrameCodec.decode(bytes).exceptionOrNull()?.let { failure ->
                assertTrue(
                    "expected FrameDecodeException, got $failure",
                    failure is FrameDecodeException,
                )
            }
        }
    }

    // --- Helpers -----------------------------------------------------------

    private fun sampleFrame(
        version: Int = 1,
        type: FrameType = FrameType.MSG,
        flags: Int = 0,
        nodeId: Int = 0x1234,
        seq: Int = 0x5678,
        ttl: Int = 7,
        payload: ByteArray = "hello".toByteArray(),
    ) = Frame(version, type, flags, nodeId, seq, ttl, payload)

    private fun assertFrameEquals(expected: Frame, actual: Frame) {
        assertEquals(expected.version, actual.version)
        assertEquals(expected.type, actual.type)
        assertEquals(expected.flags, actual.flags)
        assertEquals(expected.nodeId, actual.nodeId)
        assertEquals(expected.seq, actual.seq)
        assertEquals(expected.ttl, actual.ttl)
        assertArrayEquals(expected.payload, actual.payload)
    }

    private fun assertFailure(expected: DecodeError, result: Result<Frame>) {
        assertTrue("expected failure but decoded ${result.getOrNull()}", result.isFailure)
        val reason = (result.exceptionOrNull() as? FrameDecodeException)?.reason
        assertEquals(expected, reason)
    }

    private fun writeCrc(bytes: ByteArray) {
        val crc = Crc16.compute(bytes, 0, bytes.size - FrameCodec.CRC_SIZE)
        bytes[bytes.size - 2] = (crc shr 8).toByte()
        bytes[bytes.size - 1] = crc.toByte()
    }
}
