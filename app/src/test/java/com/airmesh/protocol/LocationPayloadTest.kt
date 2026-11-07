package com.airmesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the LOCATION payload codec: round trips (including negative
 * coordinates and the +-90 / +-180 extremes), degree conversion rounding,
 * explicit decode failures, accuracy clamping, and haversine against two
 * hand-checked point pairs.
 */
class LocationPayloadTest {

    // --- round trip -----------------------------------------------------------

    @Test
    fun encodeDecodeRoundTrip() {
        val payload = LocationPayload(30_044_420, 31_235_760, 12)
        assertEquals(payload, LocationPayload.decode(payload.encode()).getOrThrow())
    }

    @Test
    fun negativeCoordinatesRoundTrip() {
        // South/west hemisphere: both coordinates negative.
        val payload = LocationPayload(-33_868_800, -151_209_300, 25)
        assertEquals(payload, LocationPayload.decode(payload.encode()).getOrThrow())
    }

    @Test
    fun extremesRoundTrip() {
        val northEast = LocationPayload(90_000_000, 180_000_000, 0)
        val southWest = LocationPayload(-90_000_000, -180_000_000, 65_535)
        assertEquals(northEast, LocationPayload.decode(northEast.encode()).getOrThrow())
        assertEquals(southWest, LocationPayload.decode(southWest.encode()).getOrThrow())
    }

    @Test
    fun encodedSizeIsAlwaysTenBytes() {
        for (payload in listOf(
            LocationPayload(0, 0, 0),
            LocationPayload(-90_000_000, -180_000_000, -5),
            LocationPayload(90_000_000, 180_000_000, 70_000),
        )) {
            assertEquals(LocationPayload.ENCODED_SIZE, payload.encode().size)
        }
    }

    // --- degree conversion and rounding ---------------------------------------

    @Test
    fun degreesToE6RoundsToTheNearestMicroDegree() {
        // Fractional micro-degree below .5 rounds down, above .5 rounds up.
        assertEquals(31_768_372, degreesToE6(31.76837249))
        assertEquals(31_768_373, degreesToE6(31.76837251))
        // Same behavior for negative coordinates.
        assertEquals(-122_419_512, degreesToE6(-122.4195124))
        assertEquals(-122_419_513, degreesToE6(-122.4195126))
    }

    @Test
    fun e6ToDegreesIsTheInverseConversion() {
        val payload = LocationPayload(30_044_420, 31_235_760, 1)
        assertEquals(30.04442, payload.latitudeDegrees, 1e-9)
        assertEquals(31.23576, payload.longitudeDegrees, 1e-9)
    }

    // --- explicit decode failures ----------------------------------------------

    @Test
    fun wrongLengthFailsWithWrongLength() {
        val good = LocationPayload(1, 2, 3).encode()
        for (size in listOf(0, 5, 9, 11, 64)) {
            assertDecodeFailure(good.copyOf(size), LocationDecodeFailure.WRONG_LENGTH)
        }
    }

    @Test
    fun latitudeOutOfRangeFails() {
        assertDecodedCoordinatesFail(90_000_001, 0, LocationDecodeFailure.LATITUDE_OUT_OF_RANGE)
        assertDecodedCoordinatesFail(-90_000_001, 0, LocationDecodeFailure.LATITUDE_OUT_OF_RANGE)
    }

    @Test
    fun longitudeOutOfRangeFails() {
        assertDecodedCoordinatesFail(0, 180_000_001, LocationDecodeFailure.LONGITUDE_OUT_OF_RANGE)
        assertDecodedCoordinatesFail(0, -180_000_001, LocationDecodeFailure.LONGITUDE_OUT_OF_RANGE)
    }

    @Test
    fun latitudeErrorIsReportedBeforeLongitude() {
        // Both coordinates invalid: the documented check order reports the
        // latitude problem first.
        assertDecodedCoordinatesFail(
            95_000_000,
            200_000_000,
            LocationDecodeFailure.LATITUDE_OUT_OF_RANGE,
        )
    }

    @Test
    fun boundaryValuesAreAccepted() {
        // Exactly +-90 and +-180 are valid; only strictly outside fails.
        assertTrue(LocationPayload.decode(LocationPayload(90_000_000, 180_000_000, 1).encode()).isSuccess)
        assertTrue(LocationPayload.decode(LocationPayload(-90_000_000, -180_000_000, 1).encode()).isSuccess)
    }

    // --- accuracy clamping ------------------------------------------------------

    @Test
    fun accuracyIsClampedToUnsigned16OnEncode() {
        val decoded = LocationPayload.decode(LocationPayload(0, 0, -5).encode()).getOrThrow()
        assertEquals(0, decoded.accuracyMeters)

        val large = LocationPayload.decode(LocationPayload(0, 0, 70_000).encode()).getOrThrow()
        assertEquals(65_535, large.accuracyMeters)
    }

    @Test
    fun inRangeAccuracyPassesThroughUntouched() {
        for (value in listOf(0, 1, 25, 65_534, 65_535)) {
            val decoded = LocationPayload.decode(LocationPayload(0, 0, value).encode()).getOrThrow()
            assertEquals(value, decoded.accuracyMeters)
        }
    }

    // --- haversine ---------------------------------------------------------------

    @Test
    fun haversineOneDegreeOfLongitudeAtTheEquator() {
        // Hand check: one degree of arc on a sphere of radius R is exactly
        // R * pi / 180 = 6371 km * pi / 180 ~= 111,194.9 m.
        val expected = 6_371_000.0 * Math.PI / 180.0
        val distance = haversineMeters(
            LocationPayload(0, 0, 0),
            LocationPayload(0, 1_000_000, 0),
        )
        assertEquals(expected, distance, 1.0) // within one meter: floating point only
    }

    @Test
    fun haversineLondonToParis() {
        // Hand check: dLat ~= 2.65 deg, dLon ~= 2.48 deg give a central angle of
        // ~0.0539 rad, so R * c ~= 6371 km * 0.0539 ~= 343.5 km — the published
        // great-circle distance between the two city centers is ~343-344 km.
        val london = LocationPayload(51_507_400, -127_800, 0)
        val paris = LocationPayload(48_856_600, 2_352_200, 0)
        assertEquals(343_500.0, haversineMeters(london, paris), 1_500.0)
    }

    @Test
    fun haversineIsZeroForIdenticalPointsAndSymmetric() {
        val cairo = LocationPayload(30_044_420, 31_235_760, 10)
        val sydney = LocationPayload(-33_868_800, -151_209_300, 10)
        assertEquals(0.0, haversineMeters(cairo, cairo), 0.0)
        assertEquals(haversineMeters(cairo, sydney), haversineMeters(sydney, cairo), 0.0)
    }

    // --- helpers ------------------------------------------------------------------

    private fun assertDecodeFailure(bytes: ByteArray, expected: LocationDecodeFailure) {
        val result = LocationPayload.decode(bytes)
        assertTrue("decode must fail for ${bytes.size} bytes", result.isFailure)
        assertEquals(expected, (result.exceptionOrNull() as LocationDecodeException).failure)
    }

    /** Encodes out-of-range coordinates directly (encode does not validate them) and checks the decode failure. */
    private fun assertDecodedCoordinatesFail(
        latitudeE6: Int,
        longitudeE6: Int,
        expected: LocationDecodeFailure,
    ) {
        val bytes = LocationPayload(latitudeE6, longitudeE6, 5).encode()
        assertDecodeFailure(bytes, expected)
    }
}
