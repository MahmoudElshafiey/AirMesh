package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for locationPermissionsForSharing. Kept in its own file so the
 * existing PermissionRequirementsTest (which covers required/wifiDirect)
 * stays untouched.
 */
class LocationPermissionTest {

    @Test
    fun sharingRequiresFineAndCoarseLocation() {
        assertEquals(
            listOf(
                PermissionRequirements.ACCESS_FINE_LOCATION,
                PermissionRequirements.ACCESS_COARSE_LOCATION,
            ),
            PermissionRequirements.locationPermissionsForSharing(),
        )
    }

    @Test
    fun fineIsRequestedFirstSoTheDialogOffersPreciseLocationFirst() {
        // RequestMultiplePermissions shows the dialog in list order; precise
        // location must come before approximate.
        val permissions = PermissionRequirements.locationPermissionsForSharing()
        assertEquals(2, permissions.size)
        assertEquals("android.permission.ACCESS_FINE_LOCATION", permissions.first())
        assertEquals("android.permission.ACCESS_COARSE_LOCATION", permissions.last())
    }

    @Test
    fun sharingSetIsIndependentOfTheWifiDirectSet() {
        // required(sdk) changes with the SDK level; the location-sharing set
        // must not — it is a constant decision.
        val sharing = PermissionRequirements.locationPermissionsForSharing()
        for (sdkInt in listOf(29, 30, 32, 33, 34, 35, 37)) {
            assertEquals(sharing, PermissionRequirements.locationPermissionsForSharing())
            // On Android 10-12 both sets overlap on FINE; from SDK 33 the Wi-Fi
            // Direct set no longer mentions location at all.
            if (sdkInt < 33) {
                assertTrue(
                    "SDK $sdkInt: fine location is in both sets",
                    PermissionRequirements.required(sdkInt).contains(PermissionRequirements.ACCESS_FINE_LOCATION),
                )
            } else {
                assertFalse(
                    "SDK $sdkInt: Wi-Fi Direct set must not request location",
                    PermissionRequirements.required(sdkInt).any { it.contains("LOCATION") },
                )
            }
        }
    }
}
