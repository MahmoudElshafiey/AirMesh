package com.airmesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reason why [FrameCodec.decode] rejected a frame. */
enum class DecodeError {
    TOO_SHORT,
    PAYLOAD_TOO_LARGE,
    LENGTH_MISMATCH,
    BAD_CRC,
    UNKNOWN_TYPE,
}

/** Carries the [DecodeError] of a rejected frame. */
class FrameDecodeException(val reason: DecodeError) : Exception("Frame decode failed: $reason")

/**
 * Encodes and decodes AirMesh frames.
 *
 * Wire layout (big-endian):
 * ```
 * byte 0      version (high 4 bits) | type (low 4 bits)
 * byte 1      flags
 * bytes 2-3   nodeId (uint16)
 * bytes 4-5   seq (uint16)
 * byte 6      ttl (uint8)
 * bytes 7-8   payload length (uint16, max 1024)
 * bytes 9..   payload
 * last 2      CRC-16/CCITT-FALSE over every preceding byte
 * ```
 */
object FrameCodec {

    const val HEADER_SIZE = 9
    const val CRC_SIZE = 2
    const val MIN_FRAME_SIZE = HEADER_SIZE + CRC_SIZE
    const val MAX_PAYLOAD = 1024

    /**
     * Encodes [frame]. Invalid field values are caller bugs, so they throw
     * [IllegalArgumentException] instead of returning a Result.
     */
    fun encode(frame: Frame): ByteArray {
        require(frame.version in 0..0x0F) { "version must fit in 4 bits: ${frame.version}" }
        require(frame.flags in 0..0xFF) { "flags must fit in 8 bits: ${frame.flags}" }
        require(frame.nodeId in 0..0xFFFF) { "nodeId must fit in 16 bits: ${frame.nodeId}" }
        require(frame.seq in 0..0xFFFF) { "seq must fit in 16 bits: ${frame.seq}" }
        require(frame.ttl in 0..0xFF) { "ttl must fit in 8 bits: ${frame.ttl}" }
        require(frame.payload.size <= MAX_PAYLOAD) {
            "payload is ${frame.payload.size} bytes, max is $MAX_PAYLOAD"
        }

        val buffer = ByteBuffer.allocate(MIN_FRAME_SIZE + frame.payload.size)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.put(((frame.version shl 4) or frame.type.wireValue).toByte())
        buffer.put(frame.flags.toByte())
        buffer.putShort(frame.nodeId.toShort())
        buffer.putShort(frame.seq.toShort())
        buffer.put(frame.ttl.toByte())
        buffer.putShort(frame.payload.size.toShort())
        buffer.put(frame.payload)

        // CRC covers everything written so far: header plus payload.
        val crc = Crc16.compute(buffer.array(), 0, buffer.position())
        buffer.putShort(crc.toShort())

        return buffer.array()
    }

    /**
     * Decodes [bytes]. Checks run in a fixed order so a corrupted frame always
     * reports the most fundamental problem: size, then protocol limit, then
     * exact length, then CRC (before trusting any header field), then type.
     */
    fun decode(bytes: ByteArray): Result<Frame> {
        if (bytes.size < MIN_FRAME_SIZE) {
            return failure(DecodeError.TOO_SHORT)
        }

        val payloadLength = ((bytes[7].toInt() and 0xFF) shl 8) or (bytes[8].toInt() and 0xFF)
        if (payloadLength > MAX_PAYLOAD) {
            return failure(DecodeError.PAYLOAD_TOO_LARGE)
        }
        if (bytes.size != MIN_FRAME_SIZE + payloadLength) {
            return failure(DecodeError.LENGTH_MISMATCH)
        }

        val expectedCrc = ((bytes[bytes.size - 2].toInt() and 0xFF) shl 8) or
            (bytes[bytes.size - 1].toInt() and 0xFF)
        val actualCrc = Crc16.compute(bytes, 0, bytes.size - CRC_SIZE)
        if (actualCrc != expectedCrc) {
            return failure(DecodeError.BAD_CRC)
        }

        val firstByte = bytes[0].toInt() and 0xFF
        val type = FrameType.fromWire(firstByte and 0x0F)
            ?: return failure(DecodeError.UNKNOWN_TYPE)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val frame = Frame(
            version = firstByte ushr 4,
            type = type,
            flags = bytes[1].toInt() and 0xFF,
            nodeId = buffer.getShort(2).toInt() and 0xFFFF,
            seq = buffer.getShort(4).toInt() and 0xFFFF,
            ttl = bytes[6].toInt() and 0xFF,
            payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLength),
        )
        return Result.success(frame)
    }

    private fun failure(reason: DecodeError): Result<Frame> =
        Result.failure(FrameDecodeException(reason))
}
