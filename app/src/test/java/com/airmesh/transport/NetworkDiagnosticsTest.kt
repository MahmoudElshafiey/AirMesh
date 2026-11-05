package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the interface flagging logic used in on-device diagnostics. */
class NetworkDiagnosticsTest {

    @Test
    fun p2pInterfaceNamesAreFlagged() {
        assertTrue(NetworkDiagnostics.isP2pInterface("p2p0"))
        assertTrue(NetworkDiagnostics.isP2pInterface("P2P1"))
        assertFalse(NetworkDiagnostics.isP2pInterface("wlan0"))
        assertFalse(NetworkDiagnostics.isP2pInterface("lo"))
    }

    @Test
    fun groupSubnetAddressesAreDetected() {
        assertTrue(NetworkDiagnostics.holdsGroupAddress(listOf("192.168.49.1")))
        assertTrue(
            NetworkDiagnostics.holdsGroupAddress(listOf("10.0.2.2", "192.168.49.7")),
        )
        assertFalse(NetworkDiagnostics.holdsGroupAddress(listOf("192.168.50.1")))
        assertFalse(NetworkDiagnostics.holdsGroupAddress(emptyList()))
    }

    @Test
    fun describeFlagsP2pAndGroupSubnetInterfaces() {
        val lines = NetworkDiagnostics.describe(
            listOf(
                InterfaceInfo(name = "wlan0", up = true, ipv4Addresses = listOf("192.168.49.1")),
                InterfaceInfo(name = "p2p0", up = false, ipv4Addresses = emptyList()),
                InterfaceInfo(name = "lo", up = true, ipv4Addresses = listOf("127.0.0.1")),
            ),
        )

        assertEquals(3, lines.size)
        // On Android the P2P link usually rides wlan0: flag it by its address.
        assertEquals("wlan0 up=true ipv4=[192.168.49.1] [192.168.49.x]", lines[0])
        assertEquals("p2p0 up=false ipv4=[] [P2P]", lines[1])
        // A plain loopback line carries no flags at all.
        assertEquals("lo up=true ipv4=[127.0.0.1]", lines[2])
    }

    @Test
    fun enumerateReportsAtLeastOneInterfaceWithValidIpv4Format() {
        val interfaces = NetworkDiagnostics.enumerate()
        assertTrue("expected at least one network interface", interfaces.isNotEmpty())

        val ipv4Pattern = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        for (info in interfaces) {
            info.ipv4Addresses.forEach { address ->
                assertTrue(
                    "address $address of ${info.name} is not dotted-quad IPv4",
                    ipv4Pattern.matches(address),
                )
            }
        }
    }
}
