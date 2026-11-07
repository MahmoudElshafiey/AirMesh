package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the DNS-SD ad token codec, discovery refresh and peer staleness timing. */
class AirMeshAdCodecTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    // --- encode ------------------------------------------------------------------

    @Test
    fun `encode produces the expected token`() {
        assertEquals("airmesh-aabbccddeeff-n42-v1-o0", AirMeshAdCodec.encode(mac, 42, false))
        assertEquals("airmesh-aabbccddeeff-n42-v1-o1", AirMeshAdCodec.encode(mac, 42, true))
    }

    @Test
    fun `encode lowercases and strips colons`() {
        assertEquals("airmesh-0a1b2c3d4e5f-n7-v1-o0", AirMeshAdCodec.encode("0A:1B:2C:3D:4E:5F", 7, false))
    }

    // --- decode ------------------------------------------------------------------

    @Test
    fun `decode round-trips`() {
        val ad = AirMeshAdCodec.decode(AirMeshAdCodec.encode(mac, 65535, true))!!
        assertEquals("AA:BB:CC:DD:EE:FF", ad.deviceAddress)
        assertEquals(65535, ad.nodeId)
        assertEquals(AirMeshAdCodec.PROTOCOL_VERSION, ad.protocolVersion)
        assertTrue(ad.isGroupOwner)
    }

    @Test
    fun `decode accepts an uppercase mac and normalizes it`() {
        val ad = AirMeshAdCodec.decode("airmesh-AABBCCDDEEFF-n7-v1-o0")!!
        assertEquals("AA:BB:CC:DD:EE:FF", ad.deviceAddress)
        assertFalse(ad.isGroupOwner)
    }

    @Test
    fun `decode rejects null and empty input`() {
        assertNull(AirMeshAdCodec.decode(null))
        assertNull(AirMeshAdCodec.decode(""))
    }

    @Test
    fun `decode rejects wrong part counts`() {
        assertNull(AirMeshAdCodec.decode("airmesh-aabbccddeeff-n7-v1")) // missing owner flag
        assertNull(AirMeshAdCodec.decode("airmesh-aabbccddeeff-n7-v1-o0-x")) // extra part
    }

    @Test
    fun `decode rejects a wrong prefix`() {
        assertNull(AirMeshAdCodec.decode("other-aabbccddeeff-n7-v1-o0"))
    }

    @Test
    fun `decode rejects malformed macs`() {
        assertNull(AirMeshAdCodec.decode("airmesh-aabbccddeef-n7-v1-o0")) // 11 hex chars
        assertNull(AirMeshAdCodec.decode("airmesh-aabbccddeefg-n7-v1-o0")) // 12 chars, 'g' is not hex
    }

    @Test
    fun `decode rejects out-of-range node ids`() {
        assertNull(AirMeshAdCodec.decode("airmesh-aabbccddeeff-n0-v1-o0")) // below 1..65535
        assertNull(AirMeshAdCodec.decode("airmesh-aabbccddeeff-n65536-v1-o0")) // above 1..65535
    }

    @Test
    fun `decode rejects unknown versions and bad owner flags`() {
        assertNull(AirMeshAdCodec.decode("airmesh-aabbccddeeff-n7-v2-o0"))
        assertNull(AirMeshAdCodec.decode("airmesh-aabbccddeeff-n7-v1-ox"))
        assertNull(AirMeshAdCodec.decode("airmesh-aabbccddeeff-n7-v1-o11"))
    }

    // --- discovery refresh timing ---------------------------------------------------

    @Test
    fun `refresh is due when no request was ever made`() {
        assertTrue(shouldRefreshDiscovery(null, 0))
    }

    @Test
    fun `refresh is not due inside the window and is due at or after it`() {
        assertFalse(shouldRefreshDiscovery(1_000L, 60_999L)) // 59.999 s elapsed
        assertTrue(shouldRefreshDiscovery(1_000L, 61_000L)) // exactly 60 s elapsed
    }

    @Test
    fun `refresh window is 60 seconds`() {
        assertFalse(shouldRefreshDiscovery(50_000L, 90_000L)) // 40 s elapsed
        assertTrue(shouldRefreshDiscovery(10_000L, 70_000L)) // 60 s elapsed
    }

    // --- peer staleness ---------------------------------------------------------------

    @Test
    fun `peer is not stale inside the window and is stale at or after it`() {
        assertFalse(shouldExpirePeer(1_000L, 120_999L)) // 119.999 s elapsed
        assertTrue(shouldExpirePeer(1_000L, 121_000L)) // exactly 120 s elapsed
    }

    @Test
    fun `peer staleness window is 120 seconds`() {
        assertFalse(shouldExpirePeer(50_000L, 90_000L)) // 40 s elapsed
        assertTrue(shouldExpirePeer(10_000L, 130_000L)) // 120 s elapsed
    }
}
