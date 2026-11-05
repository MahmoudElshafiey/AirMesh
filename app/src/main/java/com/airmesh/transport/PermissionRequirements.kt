package com.airmesh.transport

/**
 * Decides which runtime permissions AirMesh must hold on a device running a
 * given Android SDK level.
 *
 * Pure function of [required]'s argument (no Android imports) so the decision
 * is unit-testable on the JVM. The app always targets SDK 37, which is what
 * makes Local Network Protection apply to it:
 *
 * - Wi-Fi Direct discovery needs ACCESS_FINE_LOCATION on Android 10-12
 *   (SDK < 33); NEARBY_WIFI_DEVICES replaces it from SDK 33.
 * - From SDK 34 the ACCESS_LOCAL_NETWORK permission exists, and Local Network
 *   Protection drops LAN TCP packets (outbound connects AND inbound accepts)
 *   for apps targeting SDK 37 that do not hold it. Without it a client's TCP
 *   connect to the group owner times out instead of being refused.
 */
object PermissionRequirements {

    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /** SDK level where NEARBY_WIFI_DEVICES replaces location for Wi-Fi Direct. */
    private const val TIRAMISU = 33

    /** SDK level where ACCESS_LOCAL_NETWORK exists (Android 14). */
    private const val UPSIDE_DOWN_CAKE = 34

    /** The Wi-Fi Direct permission on a device running [sdkInt]. */
    fun wifiDirectPermission(sdkInt: Int): String =
        if (sdkInt < TIRAMISU) ACCESS_FINE_LOCATION else NEARBY_WIFI_DEVICES

    /** Every runtime permission AirMesh must hold on a device running [sdkInt]. */
    fun required(sdkInt: Int): List<String> = buildList {
        add(wifiDirectPermission(sdkInt))
        if (sdkInt >= UPSIDE_DOWN_CAKE) add(ACCESS_LOCAL_NETWORK)
    }
}
