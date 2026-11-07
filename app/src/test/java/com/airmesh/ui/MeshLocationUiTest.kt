package com.airmesh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the pure location formatting helpers used by the chat tab. */
class MeshLocationUiTest {

    @Test
    fun coordinatesFormatWithFiveDecimals() {
        assertEquals("30.04442, 31.23576", formatCoordinates(30_044_420, 31_235_760))
        assertEquals("-33.86880, -151.20930", formatCoordinates(-33_868_800, -151_209_300))
    }

    @Test
    fun coordinatesKeepTheSignAndPadTrailingZeros() {
        assertEquals("0.00000, 0.00000", formatCoordinates(0, 0))
        // The codec extremes must render exactly, not as -90 or -180.
        assertEquals("-90.00000, -180.00000", formatCoordinates(-90_000_000, -180_000_000))
    }

    @Test
    fun distanceUsesWholeMetersBelowOneKilometer() {
        assertEquals("0 m", formatDistanceMeters(0.0))
        assertEquals("42 m", formatDistanceMeters(42.4))
        assertEquals("43 m", formatDistanceMeters(42.6))
    }

    @Test
    fun distanceUsesKilometersFromOneKilometerUp() {
        // 999.6 rounds to 1000, so it must read as kilometers — never "1000 m".
        assertEquals("1.0 km", formatDistanceMeters(999.6))
        assertEquals("1.5 km", formatDistanceMeters(1_500.0))
        assertEquals("343.6 km", formatDistanceMeters(343_580.0))
    }
}
