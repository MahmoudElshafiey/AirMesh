package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for the SDK-level runtime permission decision. */
class PermissionRequirementsTest {

    @Test
    fun sdk29NeedsFineLocationOnly() {
        // Android 10: Wi-Fi Direct discovery is gated on fine location;
        // ACCESS_LOCAL_NETWORK does not exist yet.
        assertEquals(
            listOf(PermissionRequirements.ACCESS_FINE_LOCATION),
            PermissionRequirements.required(29),
        )
    }

    @Test
    fun sdk32StillNeedsFineLocationOnly() {
        // Android 12: last release where location gates Wi-Fi Direct.
        assertEquals(
            listOf(PermissionRequirements.ACCESS_FINE_LOCATION),
            PermissionRequirements.required(32),
        )
    }

    @Test
    fun sdk33ReplacesLocationWithNearbyWifiDevices() {
        // Android 13: NEARBY_WIFI_DEVICES takes over; ACCESS_LOCAL_NETWORK
        // still does not exist.
        assertEquals(
            listOf(PermissionRequirements.NEARBY_WIFI_DEVICES),
            PermissionRequirements.required(33),
        )
    }

    @Test
    fun sdk36DoesNotRequireLocalNetworkPermission() {
        // Android 16: local network protection is opt-in and covered by
        // NEARBY_WIFI_DEVICES, so ACCESS_LOCAL_NETWORK must not be requested.
        assertEquals(
            listOf(PermissionRequirements.NEARBY_WIFI_DEVICES),
            PermissionRequirements.required(36),
        )
    }

    @Test
    fun sdk37AddsLocalNetworkPermission() {
        // Android 17: ACCESS_LOCAL_NETWORK becomes required for our app.
        assertEquals(
            listOf(
                PermissionRequirements.NEARBY_WIFI_DEVICES,
                PermissionRequirements.ACCESS_LOCAL_NETWORK,
            ),
            PermissionRequirements.required(37),
        )
    }

    @Test
    fun localNetworkPermissionAppearsExactlyAtSdk37() {
        // Boundary: Android 14-16 do not require ACCESS_LOCAL_NETWORK; it is
        // required from Android 17 (SDK 37) onward.
        assertEquals(
            listOf(PermissionRequirements.NEARBY_WIFI_DEVICES),
            PermissionRequirements.required(34),
        )
        assertEquals(
            listOf(
                PermissionRequirements.NEARBY_WIFI_DEVICES,
                PermissionRequirements.ACCESS_LOCAL_NETWORK,
            ),
            PermissionRequirements.required(37),
        )
    }

    @Test
    fun wifiDirectPermissionFollowsTheSdk33Boundary() {
        assertEquals(
            PermissionRequirements.ACCESS_FINE_LOCATION,
            PermissionRequirements.wifiDirectPermission(29),
        )
        assertEquals(
            PermissionRequirements.ACCESS_FINE_LOCATION,
            PermissionRequirements.wifiDirectPermission(32),
        )
        assertEquals(
            PermissionRequirements.NEARBY_WIFI_DEVICES,
            PermissionRequirements.wifiDirectPermission(33),
        )
        assertEquals(
            PermissionRequirements.NEARBY_WIFI_DEVICES,
            PermissionRequirements.wifiDirectPermission(37),
        )
    }
}
