package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the DNS-SD ad token codec, discovery refresh and peer staleness timing. */
class AirMeshAdCodecTest {

    // --- encode ------------------------------------------------------------------

    @Test
    fun `encode produces the expected token`() {
        assertEquals("airmesh-n42-v2-t00001234-o0", AirMeshAdCodec.encode(42, 0x1234, false))
        assertEquals("airmesh-n42-v2-t00001234-o1", AirMeshAdCodec.encode(42, 0x1234, true))
    }

    @Test
    fun `encode pads the token to eight hex digits`() {
        assertEquals("airmesh-n7-v2-t00000001-o0", AirMeshAdCodec.encode(7, 1, false))
        assertEquals("airmesh-n65535-v2-tffffffff-o1", AirMeshAdCodec.encode(65535, -1, true))
    }

    @Test
    fun `encode represents negative tokens as unsigned hex`() {
        // The token is a signed Int but travels as an unsigned 8-hex value.
        assertEquals("airmesh-n7-v2-tffffffff-o0", AirMeshAdCodec.encode(7, -1, false))
        assertEquals("airmesh-n7-v2-tfffffffe-o0", AirMeshAdCodec.encode(7, -2, false))
    }

    // --- decode ------------------------------------------------------------------

    @Test
    fun `decode round-trips`() {
        for (token in listOf(0, 1, 0x1234, Int.MAX_VALUE, -1, -2)) {
            for (owner in listOf(false, true)) {
                val ad = AirMeshAdCodec.decode(AirMeshAdCodec.encode(65535, token, owner))!!
                assertEquals("token $token", 65535, ad.nodeId)
                assertEquals(token, ad.tieBreakToken)
                assertEquals(owner, ad.isGroupOwner)
                assertEquals(AirMeshAdCodec.PROTOCOL_VERSION, ad.protocolVersion)
            }
        }
    }

    @Test
    fun `decode rejects null and empty input`() {
        assertNull(AirMeshAdCodec.decode(null))
        assertNull(AirMeshAdCodec.decode(""))
    }

    @Test
    fun `decode rejects wrong part counts`() {
        assertNull(AirMeshAdCodec.decode("airmesh-n7-v2-t00000001")) // missing owner flag
        assertNull(AirMeshAdCodec.decode("airmesh-n7-v2-t00000001-o0-x")) // extra part
    }

    @Test
    fun `decode rejects a wrong prefix`() {
        assertNull(AirMeshAdCodec.decode("other-n7-v2-t00000001-o0"))
    }

    @Test
    fun `decode rejects old v1 MAC tokens`() {
        // The v1 layout carried the MAC as part two; it must never decode as v2.
        assertNull(AirMeshAdCodec.decode("airmesh-aabbccddeeff-n42-v1-o0"))
        // Even an all-digit MAC fails: the 12-digit "node id" is out of range.
        assertNull(AirMeshAdCodec.decode("airmesh-001122334455-n42-v1-o0"))
    }

    @Test
    fun `decode rejects malformed token hex`() {
        assertNull(AirMeshAdCodec.decode("airmesh-n7-v2-t0000123-o0")) // 7 chars
        assertNull(AirMeshAdCodec.decode("airmesh-n7-v2-t000012345-o0")) // 9 chars
        assertNull(AirMeshAdCodec.decode("airmesh-n7-v2-tABCDEF01-o0")) // uppercase
        assertNull(AirMeshAdCodec.decode("airmesh-n7-v2-t000012g4-o0")) // 'g' is not hex
    }

    @Test
    fun `decode rejects out-of-range node ids`() {
        assertNull(AirMeshAdCodec.decode("airmesh-n0-v2-t00000001-o0")) // below 1..65535
        assertNull(AirMeshAdCodec.decode("airmesh-n65536-v2-t00000001-o0")) // above 1..65535
    }

    @Test
    fun `decode rejects unknown versions and bad owner flags`() {
        assertNull(AirMeshAdCodec.decode("airmesh-n7-v1-t00000001-o0"))
        assertNull(AirMeshAdCodec.decode("airmesh-n7-v3-t00000001-o0"))
        assertNull(AirMeshAdCodec.decode("airmesh-n7-v2-t00000001-ox"))
        assertNull(AirMeshAdCodec.decode("airmesh-n7-v2-t00000001-o11"))
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
