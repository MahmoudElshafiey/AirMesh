package com.airmesh.node

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Selection policy on a fake clock: [now] is a fixed epoch-ms value and every
 * candidate's age is derived from it, so no test depends on wall time.
 */
class LocationSelectionTest {

    private val now = 1_000_000L

    private fun gpsFix(ageMs: Long = 2_000L) =
        LocationCandidate(LOCATION_GPS_PROVIDER, 30_000_000, 31_000_000, 8, now - ageMs)

    private fun networkFix(ageMs: Long = 5_000L) =
        LocationCandidate(LOCATION_NETWORK_PROVIDER, 30_000_100, 31_000_100, 35, now - ageMs)

    @Test
    fun `fresh GPS fix wins over a fresh network fix`() {
        val decision = selectLocationFix(
            LocationAttemptSnapshot(nowMs = now, liveGps = gpsFix(), liveNetwork = networkFix()),
        )
        assertEquals(LocationDecision.Use(gpsFix(), cached = false), decision)
    }

    @Test
    fun `network fix is used when GPS produced none`() {
        val decision = selectLocationFix(
            LocationAttemptSnapshot(nowMs = now, waitedMs = 45_000L, liveNetwork = networkFix()),
        )
        assertEquals(LocationDecision.Use(networkFix(), cached = false), decision)
    }

    @Test
    fun `last known fix younger than ten minutes is shared as cached`() {
        val known = networkFix(ageMs = 3 * 60_000L)
        val decision = selectLocationFix(
            LocationAttemptSnapshot(nowMs = now, waitedMs = 45_000L, lastKnownNetwork = known),
        )
        assertEquals(LocationDecision.Use(known, cached = true), decision)
    }

    @Test
    fun `newest last known fix wins`() {
        val oldGps = gpsFix(ageMs = 9 * 60_000L)
        val freshNetwork = networkFix(ageMs = 2 * 60_000L)
        val decision = selectLocationFix(
            LocationAttemptSnapshot(
                nowMs = now,
                waitedMs = 45_000L,
                lastKnownGps = oldGps,
                lastKnownNetwork = freshNetwork,
            ),
        )
        assertEquals(LocationDecision.Use(freshNetwork, cached = true), decision)
    }

    @Test
    fun `last known fix older than ten minutes is rejected with a specific reason`() {
        val stale = networkFix(ageMs = 11 * 60_000L)
        val decision = selectLocationFix(
            LocationAttemptSnapshot(nowMs = now, waitedMs = 45_000L, lastKnownNetwork = stale),
        )
        assertEquals(
            LocationDecision.Fail(
                listOf("no GPS signal in 45s — try near a window or outside", "last known network fix too old (11 min)"),
            ),
            decision,
        )
    }

    @Test
    fun `boundary - exactly ten minutes old is still shared`() {
        val boundary = gpsFix(ageMs = LOCATION_LAST_KNOWN_MAX_AGE_MS)
        val decision = selectLocationFix(
            LocationAttemptSnapshot(nowMs = now, waitedMs = 45_000L, lastKnownGps = boundary),
        )
        assertEquals(LocationDecision.Use(boundary, cached = true), decision)
    }

    @Test
    fun `all providers disabled fails with both reasons`() {
        val decision = selectLocationFix(
            LocationAttemptSnapshot(nowMs = now, waitedMs = 45_000L, gpsProviderEnabled = false, networkProviderEnabled = false),
        )
        assertEquals(
            LocationDecision.Fail(listOf("GPS provider is turned off", "network location provider is turned off")),
            decision,
        )
    }

    @Test
    fun `approximate-only permission fails even with a fresh fix`() {
        val decision = selectLocationFix(
            LocationAttemptSnapshot(nowMs = now, permission = LocationPermissionState.APPROXIMATE_ONLY, liveGps = gpsFix()),
        )
        assertEquals(
            LocationDecision.Fail(listOf("only approximate location allowed — allow precise location to share")),
            decision,
        )
    }

    @Test
    fun `no permission fails`() {
        val decision = selectLocationFix(
            LocationAttemptSnapshot(nowMs = now, permission = LocationPermissionState.NONE),
        )
        assertEquals(LocationDecision.Fail(listOf("location permission not granted")), decision)
    }

    @Test
    fun `location services off fails fast`() {
        val decision = selectLocationFix(
            LocationAttemptSnapshot(nowMs = now, locationServicesEnabled = false),
        )
        assertEquals(LocationDecision.Fail(listOf("location services are turned off")), decision)
    }

    @Test
    fun `formatAgeMs switches from seconds to minutes`() {
        assertEquals("42 s", formatAgeMs(42_000L))
        assertEquals("3 min", formatAgeMs(180_000L))
    }
}
