package com.airmesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [ProfilePayload]: round trips, boundaries and every decode error. */
class ProfilePayloadTest {

    // U+1F680 (rocket): one code point but two UTF-16 units and 4 UTF-8 bytes.
    private val rocket = "🚀"
    // U+1F389 (party popper): another emoji, to prove mixed names survive intact.
    private val party = "🎉"

    @Test
    fun roundTripAsciiName() {
        val payload = ProfilePayload("Alice", 3)
        val decoded = ProfilePayload.decode(payload.encode()).getOrThrow()
        assertEquals("Alice", decoded.name)
        assertEquals(3, decoded.avatarColorIndex)
    }

    @Test
    fun roundTripEmojiNameCountsCodePointsNotUnits() {
        val name = "$rocket Rocket $party"
        val payload = ProfilePayload(name, 7)
        val decoded = ProfilePayload.decode(payload.encode()).getOrThrow()
        assertEquals(name, decoded.name)
        assertEquals(7, decoded.avatarColorIndex)
    }

    @Test
    fun boundaryTwentyFourCodePointsAccepted() {
        val name = "a".repeat(24) // 24 code points, 24 bytes
        val payload = ProfilePayload(name, 0)
        assertEquals(name, ProfilePayload.decode(payload.encode()).getOrThrow().name)
    }

    @Test
    fun boundarySixtyFourBytesAccepted() {
        // 16 emoji: only 16 code points but exactly 64 UTF-8 bytes (4 each).
        val name = rocket.repeat(16)
        assertEquals(64, name.toByteArray(Charsets.UTF_8).size)
        val payload = ProfilePayload(name, 5)
        assertEquals(name, ProfilePayload.decode(payload.encode()).getOrThrow().name)
    }

    @Test
    fun tooManyCodePointsRejected() {
        // 25 code points: valid UTF-8 but over the 24-code-point cap.
        val bytes = byteArrayOf(1, 0) + "a".repeat(25).toByteArray(Charsets.UTF_8)
        assertFailure(ProfileDecodeFailure.NAME_TOO_LARGE, ProfilePayload.decode(bytes))
    }

    @Test
    fun tooManyBytesRejected() {
        // 17 emoji = 68 UTF-8 bytes (> 64), but only 17 code points.
        val name = rocket.repeat(17)
        val bytes = byteArrayOf(1, 0) + name.toByteArray(Charsets.UTF_8)
        assertFailure(ProfileDecodeFailure.NAME_TOO_LARGE, ProfilePayload.decode(bytes))
    }

    @Test
    fun invalidUtf8Rejected() {
        // 0xFF is not a valid UTF-8 start or continuation byte.
        val bytes = byteArrayOf(1, 2, 0x41, 0xFF.toByte())
        assertFailure(ProfileDecodeFailure.INVALID_UTF8, ProfilePayload.decode(bytes))
    }

    @Test
    fun emptyNameRejected() {
        // Version + color but no name bytes.
        assertFailure(ProfileDecodeFailure.EMPTY_NAME, ProfilePayload.decode(byteArrayOf(1, 3)))
    }

    @Test
    fun tooShortRejected() {
        assertFailure(ProfileDecodeFailure.TOO_SHORT, ProfilePayload.decode(byteArrayOf(1)))
        assertFailure(ProfileDecodeFailure.TOO_SHORT, ProfilePayload.decode(ByteArray(0)))
    }

    @Test
    fun unsupportedVersionRejected() {
        val bytes = byteArrayOf(2, 3) + "hi".toByteArray(Charsets.UTF_8)
        assertFailure(ProfileDecodeFailure.UNSUPPORTED_VERSION, ProfilePayload.decode(bytes))
    }

    @Test
    fun badColorIndexRejected() {
        val bytes = byteArrayOf(1, 8) + "hi".toByteArray(Charsets.UTF_8)
        assertFailure(ProfileDecodeFailure.BAD_COLOR_INDEX, ProfilePayload.decode(bytes))
    }

    private fun assertFailure(expected: ProfileDecodeFailure, result: Result<ProfilePayload>) {
        assertTrue("expected failure but decoded ${result.getOrNull()}", result.isFailure)
        val reason = (result.exceptionOrNull() as? ProfileDecodeException)?.failure
        assertEquals(expected, reason)
    }
}
