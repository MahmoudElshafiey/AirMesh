package com.airmesh.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [NetworkDiagnostics.hasGroupAddress], the connect-loop gate. */
class NetworkDiagnosticsHasGroupAddressTest {

    @Test
    fun upInterfaceWithGroupAddressIsDetected() {
        assertTrue(
            NetworkDiagnostics.hasGroupAddress(
                listOf(
                    InterfaceInfo(name = "wlan0", up = true, ipv4Addresses = listOf("192.168.49.1")),
                ),
            ),
        )
    }

    @Test
    fun downInterfaceWithGroupAddressDoesNotCount() {
        assertFalse(
            NetworkDiagnostics.hasGroupAddress(
                listOf(
                    InterfaceInfo(name = "wlan0", up = false, ipv4Addresses = listOf("192.168.49.1")),
                ),
            ),
        )
    }

    @Test
    fun normalWifiOnlyMeansNoP2pLink() {
        assertFalse(
            NetworkDiagnostics.hasGroupAddress(
                listOf(
                    InterfaceInfo(name = "wlan0", up = true, ipv4Addresses = listOf("192.168.1.44")),
                ),
            ),
        )
    }

    @Test
    fun emptyInterfaceListMeansNoP2pLink() {
        assertFalse(NetworkDiagnostics.hasGroupAddress(emptyList()))
    }

    @Test
    fun groupAddressOnAnyUpInterfaceCounts() {
        assertTrue(
            NetworkDiagnostics.hasGroupAddress(
                listOf(
                    InterfaceInfo(name = "lo", up = true, ipv4Addresses = listOf("127.0.0.1")),
                    InterfaceInfo(name = "wlan0", up = true, ipv4Addresses = listOf("192.168.1.44")),
                    InterfaceInfo(name = "p2p0", up = true, ipv4Addresses = listOf("192.168.49.7")),
                ),
            ),
        )
    }
}
