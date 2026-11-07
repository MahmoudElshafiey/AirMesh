package com.airmesh.node

import com.airmesh.protocol.LocationPayload

/**
 * One position fix, in the same units as [LocationPayload] (micro-degrees),
 * plus when it was taken so the UI can tell a fresh fix from a stale one.
 */
data class LocationFix(
    val latitudeE6: Int,
    val longitudeE6: Int,
    val accuracyMeters: Int,
    val timeMs: Long,
) {
    /** The payload this fix would be sent as in a LOCATION frame. */
    fun toPayload(): LocationPayload = LocationPayload(latitudeE6, longitudeE6, accuracyMeters)
}

/** How long [LocationProvider.requestFix] waits for a live fix (GPS or network) at most. */
const val LOCATION_MAX_WAIT_MS = 45_000L

/** Fixes older than this are not used for the "X m from you" distance display in chat. */
const val LOCATION_STALE_AFTER_MS = 5 * 60 * 1000L

/**
 * Supplies the device's current position for location sharing.
 *
 * The production implementation ([AndroidLocationProvider]) uses the platform
 * `android.location.LocationManager` — no Google Play Services, per the project
 * constraints. Tests use a fake so no GPS hardware is needed.
 */
interface LocationProvider {

    /**
     * Waits up to [LOCATION_MAX_WAIT_MS] for a usable position and returns it,
     * or null when nothing fresh enough is available in time — indoors GPS may
     * simply never fix, so callers must handle the failure case.
     */
    suspend fun requestFix(): LocationFix?
}
