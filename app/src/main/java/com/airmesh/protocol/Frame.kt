package com.airmesh.protocol

/**
 * Frame types carried in the low 4 bits of the first header byte.
 * The numeric values are part of the wire format and must not change.
 */
enum class FrameType(val wireValue: Int) {
    MSG(0),
    LOCATION(1),
    ACK(2),
    HELLO(3);

    companion object {
        /** Returns the type for a 4-bit wire value, or null if it is unknown. */
        fun fromWire(value: Int): FrameType? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * One AirMesh protocol frame.
 *
 * Note: [payload] is a ByteArray, so the generated data-class equals/hashCode
 * compare it by reference. Use contentEquals when comparing payloads.
 */
data class Frame(
    val version: Int,
    val type: FrameType,
    val flags: Int,
    val nodeId: Int,
    val seq: Int,
    val ttl: Int,
    val payload: ByteArray,
)
