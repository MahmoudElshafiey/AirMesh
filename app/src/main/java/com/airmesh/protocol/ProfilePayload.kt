package com.airmesh.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Reason why [ProfilePayload.decode] rejected a payload. */
enum class ProfileDecodeFailure {
    /** Fewer than 2 bytes: cannot hold the version and color index. */
    TOO_SHORT,

    /** Byte 0 is not the supported payload version (1). */
    UNSUPPORTED_VERSION,

    /** Byte 1 (avatar color) is outside 0..7. */
    BAD_COLOR_INDEX,

    /** The name bytes are not valid UTF-8. */
    INVALID_UTF8,

    /** The name has zero code points. */
    EMPTY_NAME,

    /** The name exceeds 24 code points or 64 UTF-8 bytes. */
    NAME_TOO_LARGE,
}

/** Carries the [ProfileDecodeFailure] of a rejected PROFILE payload. */
class ProfileDecodeException(val failure: ProfileDecodeFailure) :
    Exception("PROFILE payload invalid: $failure")

/**
 * Payload of a PROFILE frame: a display name plus an avatar color index.
 *
 * Wire layout (variable length):
 * ```
 * byte 0   payload version (= 1)
 * byte 1   avatarColorIndex (0..7)
 * bytes 2+ UTF-8 encoded display name (1..24 code points, at most 64 bytes)
 * ```
 */
data class ProfilePayload(
    val name: String,
    val avatarColorIndex: Int,
) {

    /**
     * Encodes to version byte + color byte + UTF-8 name. Invalid field values
     * are caller bugs (the node sanitizes before encoding), so they throw
     * [IllegalArgumentException] instead of returning a Result.
     */
    fun encode(): ByteArray {
        require(avatarColorIndex in 0..MAX_COLOR_INDEX) {
            "avatarColorIndex must be 0..$MAX_COLOR_INDEX: $avatarColorIndex"
        }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(name.codePointCount(0, name.length) in 1..MAX_NAME_CODE_POINTS) {
            "name must be 1..$MAX_NAME_CODE_POINTS code points: ${name.codePointCount(0, name.length)}"
        }
        require(nameBytes.size <= MAX_NAME_BYTES) {
            "name is ${nameBytes.size} UTF-8 bytes, max is $MAX_NAME_BYTES"
        }

        return byteArrayOf(PAYLOAD_VERSION.toByte(), avatarColorIndex.toByte()) + nameBytes
    }

    companion object {
        const val PAYLOAD_VERSION = 1
        const val MAX_COLOR_INDEX = 7
        const val MAX_NAME_CODE_POINTS = 24
        const val MAX_NAME_BYTES = 64

        /** Minimum payload size: version + color, before any name byte. */
        private const val MIN_SIZE = 2

        /**
         * Decodes a PROFILE payload. Checks run in a fixed order — length, then
         * version, then color, then UTF-8 validity, then name size — so a bad
         * payload always reports the most fundamental problem first. Never
         * throws: failures come back as [Result.failure] with an explicit
         * reason, because one malformed frame must be logged and dropped, not
         * crash the node.
         */
        fun decode(bytes: ByteArray): Result<ProfilePayload> {
            if (bytes.size < MIN_SIZE) return failure(ProfileDecodeFailure.TOO_SHORT)

            val version = bytes[0].toInt() and 0xFF
            if (version != PAYLOAD_VERSION) return failure(ProfileDecodeFailure.UNSUPPORTED_VERSION)

            val colorIndex = bytes[1].toInt() and 0xFF
            if (colorIndex !in 0..MAX_COLOR_INDEX) return failure(ProfileDecodeFailure.BAD_COLOR_INDEX)

            val nameBytes = bytes.copyOfRange(MIN_SIZE, bytes.size)

            // Strict UTF-8: String(bytes, UTF_8) would silently replace bad
            // sequences with U+FFFD, so decode in REPORT mode to detect them.
            val name = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(nameBytes))
                    .toString()
            } catch (error: CharacterCodingException) {
                return failure(ProfileDecodeFailure.INVALID_UTF8)
            }

            if (name.isEmpty()) return failure(ProfileDecodeFailure.EMPTY_NAME)
            val codePoints = name.codePointCount(0, name.length)
            if (codePoints > MAX_NAME_CODE_POINTS || nameBytes.size > MAX_NAME_BYTES) {
                return failure(ProfileDecodeFailure.NAME_TOO_LARGE)
            }

            return Result.success(ProfilePayload(name, colorIndex))
        }

        private fun failure(reason: ProfileDecodeFailure): Result<ProfilePayload> =
            Result.failure(ProfileDecodeException(reason))
    }
}
