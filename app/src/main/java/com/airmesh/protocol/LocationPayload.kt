package com.airmesh.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Reason why [LocationPayload.decode] rejected a payload. */
enum class LocationDecodeFailure {
    /** The payload is not exactly [LocationPayload.ENCODED_SIZE] bytes. */
    WRONG_LENGTH,

    /** Latitude outside -90..+90 degrees (in micro-degrees). */
    LATITUDE_OUT_OF_RANGE,

    /** Longitude outside -180..+180 degrees (in micro-degrees). */
    LONGITUDE_OUT_OF_RANGE,
}

/** Carries the [LocationDecodeFailure] of a rejected LOCATION payload. */
class LocationDecodeException(val failure: LocationDecodeFailure) :
    Exception("LOCATION payload invalid: $failure")

/**
 * Payload of a LOCATION frame: a position plus its horizontal accuracy.
 *
 * Coordinates are stored as micro-degrees (1e-6 degrees) in int32 fields so the
 * wire format is fixed-size, big-endian and free of floating point — at this
 * scale one unit is about 1 cm, far finer than any phone GPS can deliver.
 * Accuracy is a magnitude in meters, stored as an unsigned 16-bit value.
 */
data class LocationPayload(
    val latitudeE6: Int,
    val longitudeE6: Int,
    val accuracyMeters: Int,
) {

    /**
     * Encodes to exactly [ENCODED_SIZE] big-endian bytes:
     * int32 latitude, int32 longitude, uint16 accuracy.
     *
     * Accuracy is clamped into the unsigned-16 range because it is a
     * magnitude; out-of-range coordinates are written as-is (the receiver
     * validates them in [decode]).
     */
    fun encode(): ByteArray {
        val accuracy = accuracyMeters.coerceIn(0, UShort.MAX_VALUE.toInt())
        val buffer = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(latitudeE6)
        buffer.putInt(longitudeE6)
        buffer.putShort(accuracy.toShort())
        return buffer.array()
    }

    companion object {
        /** Exact size of [encode]'s output in bytes. */
        const val ENCODED_SIZE = 10

        private const val MAX_LATITUDE_E6 = 90_000_000
        private const val MAX_LONGITUDE_E6 = 180_000_000

        /**
         * Decodes a LOCATION payload. Checks run in a fixed order — length,
         * then latitude range, then longitude range — so a bad payload always
         * reports the most fundamental problem first. Never throws: failures
         * come back as [Result.failure] with an explicit reason, because a
         * corrupted frame must be logged and dropped, not crash the node.
         */
        fun decode(bytes: ByteArray): Result<LocationPayload> {
            if (bytes.size != ENCODED_SIZE) {
                return failure(LocationDecodeFailure.WRONG_LENGTH)
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val latitudeE6 = buffer.getInt(0)
            val longitudeE6 = buffer.getInt(4)
            // The accuracy field is unsigned on the wire; mask to 0..65535.
            val accuracyMeters = buffer.getShort(8).toInt() and 0xFFFF

            if (latitudeE6 !in -MAX_LATITUDE_E6..MAX_LATITUDE_E6) {
                return failure(LocationDecodeFailure.LATITUDE_OUT_OF_RANGE)
            }
            if (longitudeE6 !in -MAX_LONGITUDE_E6..MAX_LONGITUDE_E6) {
                return failure(LocationDecodeFailure.LONGITUDE_OUT_OF_RANGE)
            }

            return Result.success(LocationPayload(latitudeE6, longitudeE6, accuracyMeters))
        }

        private fun failure(reason: LocationDecodeFailure): Result<LocationPayload> =
            Result.failure(LocationDecodeException(reason))
    }
}

/** Micro-degrees per degree; the scale factor of the wire format. */
const val MICRO_DEGREES_PER_DEGREE = 1_000_000.0

/** Converts decimal degrees to micro-degrees, rounding to the nearest integer. */
fun degreesToE6(degrees: Double): Int =
    Math.round(degrees * MICRO_DEGREES_PER_DEGREE).toInt()

/** Latitude in decimal degrees (e.g. 31.768372). */
val LocationPayload.latitudeDegrees: Double
    get() = latitudeE6 / MICRO_DEGREES_PER_DEGREE

/** Longitude in decimal degrees (e.g. -79.940372). */
val LocationPayload.longitudeDegrees: Double
    get() = longitudeE6 / MICRO_DEGREES_PER_DEGREE

/** Mean Earth radius in meters (IUGG value); plenty for a relay app. */
private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * Great-circle distance between two positions, in meters, on a sphere of
 * [EARTH_RADIUS_METERS]. Pure math — no Android imports. The haversine form is
 * used because it stays numerically stable for both tiny and near-antipodal
 * distances, unlike the naive law-of-cosines version.
 */
fun haversineMeters(a: LocationPayload, b: LocationPayload): Double {
    val lat1 = Math.toRadians(a.latitudeDegrees)
    val lat2 = Math.toRadians(b.latitudeDegrees)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitudeDegrees - a.longitudeDegrees)

    val sinHalfDLat = sin(dLat / 2)
    val sinHalfDLon = sin(dLon / 2)
    // Floating point can push h just outside [0, 1] at the poles; clamp so
    // asin never sees NaN.
    val h = (sinHalfDLat * sinHalfDLat + cos(lat1) * cos(lat2) * sinHalfDLon * sinHalfDLon)
        .coerceIn(0.0, 1.0)
    return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(h))
}
