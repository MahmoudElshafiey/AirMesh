package com.airmesh.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the DIRECT / DIRECT_ACK payloads (TASK D1 part 1). */
class DirectPayloadTest {

    // --- DirectPayload ------------------------------------------------------

    @Test
    fun roundTripPlainAscii() {
        val payload = DirectPayload(destNodeId = 42, text = "meet at the ridge")
        val decoded = DirectPayload.decode(payload.encode()).getOrThrow()

        assertEquals(42, decoded.destNodeId)
        assertEquals("meet at the ridge", decoded.text)
    }

    @Test
    fun roundTripEmojiAndAccents() {
        val payload = DirectPayload(destNodeId = 65535, text = "héllo 🌍🚁 — ok?")
        val decoded = DirectPayload.decode(payload.encode()).getOrThrow()

        assertEquals(65535, decoded.destNodeId)
        assertEquals("héllo 🌍🚁 — ok?", decoded.text)
    }

    @Test
    fun wireLayoutIsVersionDestBigEndianThenUtf8Text() {
        val encoded = DirectPayload(destNodeId = 0x1234, text = "hi").encode()

        assertArrayEquals(
            byteArrayOf(1, 0x12.toByte(), 0x34, 'h'.code.toByte(), 'i'.code.toByte()),
            encoded,
        )
    }

    @Test
    fun boundaryLengthsRoundTrip() {
        // One UTF-8 byte of text.
        val one = DirectPayload.decode(DirectPayload(1, "a").encode()).getOrThrow()
        assertEquals("a", one.text)

        // Exactly MAX_TEXT_BYTES: 250 emoji × 4 bytes each = 1000.
        val maxText = "🌍".repeat(250)
        assertEquals(DirectPayload.MAX_TEXT_BYTES, maxText.toByteArray(Charsets.UTF_8).size)
        val decoded = DirectPayload.decode(DirectPayload(65535, maxText).encode()).getOrThrow()
        assertEquals(maxText, decoded.text)
    }

    @Test
    fun textOverTheLimitIsRejectedOnBothSides() {
        // 1001 UTF-8 bytes: encode is a caller bug...
        assertThrows(IllegalArgumentException::class.java) {
            DirectPayload(5, "a".repeat(DirectPayload.MAX_TEXT_BYTES + 1)).encode()
        }
        // ...and the same size on the wire is rejected at decode time.
        val tooLarge = byteArrayOf(1, 0x00, 0x05) +
            ByteArray(DirectPayload.MAX_TEXT_BYTES + 1) { 'a'.code.toByte() }
        assertDirectFailure(DirectDecodeFailure.TEXT_TOO_LARGE, DirectPayload.decode(tooLarge))
    }

    @Test
    fun destinationBoundariesAndRejections() {
        // Lowest and highest valid ids round-trip.
        assertEquals(1, DirectPayload.decode(DirectPayload(1, "x").encode()).getOrThrow().destNodeId)
        assertEquals(65535, DirectPayload.decode(DirectPayload(65535, "x").encode()).getOrThrow().destNodeId)

        // 0 and out-of-range ids are caller bugs at encode time...
        assertThrows(IllegalArgumentException::class.java) { DirectPayload(0, "x").encode() }
        assertThrows(IllegalArgumentException::class.java) { DirectPayload(65536, "x").encode() }
        // ...and a hostile 0 on the wire is rejected at decode time.
        val zeroDest = byteArrayOf(1, 0x00, 0x00, 'x'.code.toByte())
        assertDirectFailure(DirectDecodeFailure.DEST_ZERO, DirectPayload.decode(zeroDest))
    }

    @Test
    fun truncatedAndEmptyTextRejected() {
        // Too short to hold version + destination.
        assertDirectFailure(DirectDecodeFailure.TOO_SHORT, DirectPayload.decode(ByteArray(0)))
        assertDirectFailure(DirectDecodeFailure.TOO_SHORT, DirectPayload.decode(byteArrayOf(1, 0x00)))

        // Header only: no text bytes at all.
        assertDirectFailure(DirectDecodeFailure.EMPTY_TEXT, DirectPayload.decode(byteArrayOf(1, 0x00, 0x05)))
    }

    @Test
    fun unsupportedVersionRejected() {
        val version2 = byteArrayOf(2, 0x00, 0x05, 'x'.code.toByte())
        assertDirectFailure(DirectDecodeFailure.UNSUPPORTED_VERSION, DirectPayload.decode(version2))
    }

    @Test
    fun invalidUtf8Rejected() {
        // 0xFF is never valid UTF-8.
        val bad = byteArrayOf(1, 0x00, 0x05, 0xFF.toByte())
        assertDirectFailure(DirectDecodeFailure.INVALID_UTF8, DirectPayload.decode(bad))

        // A truncated multi-byte sequence (first byte of an emoji only).
        val cutEmoji = byteArrayOf(1, 0x00, 0x05, 0xF0.toByte())
        assertDirectFailure(DirectDecodeFailure.INVALID_UTF8, DirectPayload.decode(cutEmoji))
    }

    @Test
    fun maxTextFitsInsideTheFramePayloadLimit() {
        // version + uint16 dest + MAX_TEXT_BYTES must stay under FrameCodec.MAX_PAYLOAD.
        assertTrue(3 + DirectPayload.MAX_TEXT_BYTES <= FrameCodec.MAX_PAYLOAD)
    }

    // --- DirectAckPayload ---------------------------------------------------

    @Test
    fun ackRoundTripAndWireLayout() {
        val payload = DirectAckPayload(ackedNodeId = 0x1234, ackedSeq = 0xABCD)

        assertArrayEquals(
            byteArrayOf(1, 0x12.toByte(), 0x34.toByte(), 0xAB.toByte(), 0xCD.toByte()),
            payload.encode(),
        )

        val decoded = DirectAckPayload.decode(payload.encode()).getOrThrow()
        assertEquals(0x1234, decoded.ackedNodeId)
        assertEquals(0xABCD, decoded.ackedSeq)
    }

    @Test
    fun ackBoundaryValuesRoundTrip() {
        val maxed = DirectAckPayload.decode(DirectAckPayload(65535, 65535).encode()).getOrThrow()
        assertEquals(65535, maxed.ackedNodeId)
        assertEquals(65535, maxed.ackedSeq)

        // Seq 0 is a valid wrapped value and must round-trip too.
        val zeroed = DirectAckPayload.decode(DirectAckPayload(1, 0).encode()).getOrThrow()
        assertEquals(0, zeroed.ackedSeq)
    }

    @Test
    fun ackWrongLengthAndVersionRejected() {
        assertAckFailure(DirectAckDecodeFailure.WRONG_LENGTH, DirectAckPayload.decode(ByteArray(4)))
        assertAckFailure(DirectAckDecodeFailure.WRONG_LENGTH, DirectAckPayload.decode(ByteArray(6)))

        val version2 = byteArrayOf(2, 0x00, 0x01, 0x00, 0x01)
        assertAckFailure(DirectAckDecodeFailure.UNSUPPORTED_VERSION, DirectAckPayload.decode(version2))
    }

    @Test
    fun ackEncodeRejectsOutOfRangeFields() {
        assertThrows(IllegalArgumentException::class.java) { DirectAckPayload(65536, 1).encode() }
        assertThrows(IllegalArgumentException::class.java) { DirectAckPayload(-1, 1).encode() }
        assertThrows(IllegalArgumentException::class.java) { DirectAckPayload(1, -1).encode() }
    }

    // --- Helpers ------------------------------------------------------------

    private fun assertDirectFailure(expected: DirectDecodeFailure, result: Result<DirectPayload>) {
        assertTrue("expected failure but decoded ${result.getOrNull()}", result.isFailure)
        assertEquals(expected, (result.exceptionOrNull() as? DirectDecodeException)?.failure)
    }

    private fun assertAckFailure(expected: DirectAckDecodeFailure, result: Result<DirectAckPayload>) {
        assertTrue("expected failure but decoded ${result.getOrNull()}", result.isFailure)
        assertEquals(expected, (result.exceptionOrNull() as? DirectAckDecodeException)?.failure)
    }
}
