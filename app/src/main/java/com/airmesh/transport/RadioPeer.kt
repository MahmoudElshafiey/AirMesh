package com.airmesh.transport

/**
 * TASK T1 part C2: one raw Wi-Fi Direct peer as seen by requestPeers, reduced to what the
 * remembered-owner policy needs. Pure Kotlin mirror of WifiP2pDevice's status constants so the
 * policy stays unit-testable on the JVM; the service maps WifiP2pDevice -> RadioPeer.
 */
data class RadioPeer(
    /** The P2P device address (MAC) — what manager.connect() takes. */
    val deviceAddress: String,
    /** Connection status; one of [AVAILABLE] / [CONNECTED]. */
    val status: Int,
) {
    companion object {
        // Mirror WifiP2pDevice.AVAILABLE / CONNECTED (the framework's own values).
        const val AVAILABLE = 1
        const val CONNECTED = 3
    }
}
