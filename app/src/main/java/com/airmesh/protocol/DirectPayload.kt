package com.airmesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Reason why [DirectPayload.decode] rejected a payload. */
enum class DirectDecodeFailure {
    /** Fewer than 3 bytes: cannot hold the version and destination. */
    TOO_SHORT,

    /** Byte 0 is not the supported payload version (1). */
    UNSUPPORTED_VERSION,

    /** The destination node id is 0 (node ids are 1..65535). */
    DEST_ZERO,

    /** No text bytes after the header. */
    EMPTY_TEXT,

    /** The UTF-8 text exceeds [DirectPayload.MAX_TEXT_BYTES] bytes. */
    TEXT_TOO_LARGE,

    /** The text bytes are not valid UTF-8. */
    INVALID_UTF8,
}

/** Carries the [DirectDecodeFailure] of a rejected DIRECT payload. */
class DirectDecodeException(val failure: DirectDecodeFailure) :
    Exception("DIRECT payload invalid: $failure")

/**
 * Payload of a DIRECT frame: one-to-one message addressed to [destNodeId].
 *
 * Wire layout (variable length):
 * ```
 * byte 0   payload version (= 1)
 * bytes 1-2 destNodeId (uint16 big-endian, 1..65535)
 * bytes 3+ UTF-8 text (1..[MAX_TEXT_BYTES] bytes)
 * ```
 *
 * The destination is carried in the payload — not just the frame header — so a
 * relay can tell whether it is addressed to itself without any node-specific
 * state, and the original sender stays visible even after hops.
 */
data class DirectPayload(
    val destNodeId: Int,
    val text: String,
) {

    /**
     * Encodes to version byte + uint16 destination + UTF-8 text. Invalid field
     * values are caller bugs (the node validates before encoding), so they
     * throw [IllegalArgumentException] instead of returning a Result.
     */
    fun encode(): ByteArray {
        require(destNodeId in 1..0xFFFF) { "destNodeId must be 1..65535: $destNodeId" }
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(textBytes.size in 1..MAX_TEXT_BYTES) {
            "text is ${textBytes.size} UTF-8 bytes, must be 1..$MAX_TEXT_BYTES"
        }

        return byteArrayOf(
            PAYLOAD_VERSION.toByte(),
            (destNodeId ushr 8).toByte(),
            destNodeId.toByte(),
        ) + textBytes
    }

    companion object {
        const val PAYLOAD_VERSION = 1

        /** Max UTF-8 size of the message body; keeps the frame under [FrameCodec.MAX_PAYLOAD]. */
        const val MAX_TEXT_BYTES = 1000

        /** Minimum payload size: version + uint16 destination, before any text. */
        private const val MIN_SIZE = 3

        /**
         * Decodes a DIRECT payload. Checks run in a fixed order — length, then
         * version, then destination, then text size, then UTF-8 validity — so a
         * bad payload always reports the most fundamental problem first. Never
         * throws: failures come back as [Result.failure] with an explicit
         * reason, because one malformed frame must be logged and dropped, not
         * crash the node.
         */
        fun decode(bytes: ByteArray): Result<DirectPayload> {
            if (bytes.size < MIN_SIZE) return failure(DirectDecodeFailure.TOO_SHORT)

            val version = bytes[0].toInt() and 0xFF
            if (version != PAYLOAD_VERSION) return failure(DirectDecodeFailure.UNSUPPORTED_VERSION)

            val destNodeId = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
            if (destNodeId == 0) return failure(DirectDecodeFailure.DEST_ZERO)

            val textBytes = bytes.copyOfRange(MIN_SIZE, bytes.size)
            if (textBytes.isEmpty()) return failure(DirectDecodeFailure.EMPTY_TEXT)
            if (textBytes.size > MAX_TEXT_BYTES) return failure(DirectDecodeFailure.TEXT_TOO_LARGE)

            // Strict UTF-8: String(bytes, UTF_8) would silently replace bad
            // sequences with U+FFFD, so decode in REPORT mode to detect them.
            val text = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(textBytes))
                    .toString()
            } catch (error: CharacterCodingException) {
                return failure(DirectDecodeFailure.INVALID_UTF8)
            }

            return Result.success(DirectPayload(destNodeId, text))
        }

        private fun failure(reason: DirectDecodeFailure): Result<DirectPayload> =
            Result.failure(DirectDecodeException(reason))
    }
}

/** Reason why [DirectAckPayload.decode] rejected a payload. */
enum class DirectAckDecodeFailure {
    /** The payload is not exactly [DirectAckPayload.ENCODED_SIZE] bytes. */
    WRONG_LENGTH,

    /** Byte 0 is not the supported payload version (1). */
    UNSUPPORTED_VERSION,
}

/** Carries the [DirectAckDecodeFailure] of a rejected DIRECT_ACK payload. */
class DirectAckDecodeException(val failure: DirectAckDecodeFailure) :
    Exception("DIRECT_ACK payload invalid: $failure")

/**
 * Payload of a DIRECT_ACK frame: delivery receipt for one direct message.
 *
 * Wire layout (fixed [ENCODED_SIZE] bytes):
 * ```
 * byte 0   payload version (= 1)
 * bytes 1-2 ackedNodeId (uint16 big-endian, the ORIGINAL sender of the DM —
 *          which is also the node that receives this ack)
 * bytes 3-4 ackedSeq (uint16, the frame seq of the acknowledged DIRECT)
 * ```
 *
 * The receipt names the original sender and its frame seq rather than the
 * destination: the sender matches receipts against what it sent, so the reply
 * must carry the sender's identity back to it.
 */
data class DirectAckPayload(
    val ackedNodeId: Int,
    val ackedSeq: Int,
) {

    /** Encodes to exactly [ENCODED_SIZE] big-endian bytes. Out-of-range fields are caller bugs. */
    fun encode(): ByteArray {
        require(ackedNodeId in 0..0xFFFF) { "ackedNodeId must fit in 16 bits: $ackedNodeId" }
        require(ackedSeq in 0..0xFFFF) { "ackedSeq must fit in 16 bits: $ackedSeq" }

        val buffer = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(PAYLOAD_VERSION.toByte())
        buffer.putShort(ackedNodeId.toShort())
        buffer.putShort(ackedSeq.toShort())
        return buffer.array()
    }

    companion object {
        const val PAYLOAD_VERSION = 1

        /** Exact size of [encode]'s output in bytes. */
        const val ENCODED_SIZE = 5

        /**
         * Decodes a DIRECT_ACK payload. Never throws: failures come back as
         * [Result.failure] with an explicit reason, because one malformed frame
         * must be logged and dropped, not crash the node.
         */
        fun decode(bytes: ByteArray): Result<DirectAckPayload> {
            if (bytes.size != ENCODED_SIZE) return failure(DirectAckDecodeFailure.WRONG_LENGTH)

            val version = bytes[0].toInt() and 0xFF
            if (version != PAYLOAD_VERSION) return failure(DirectAckDecodeFailure.UNSUPPORTED_VERSION)

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            // Both fields are unsigned on the wire; mask to 0..65535.
            val ackedNodeId = buffer.getShort(1).toInt() and 0xFFFF
            val ackedSeq = buffer.getShort(3).toInt() and 0xFFFF

            return Result.success(DirectAckPayload(ackedNodeId, ackedSeq))
        }

        private fun failure(reason: DirectAckDecodeFailure): Result<DirectAckPayload> =
            Result.failure(DirectAckDecodeException(reason))
    }
}
