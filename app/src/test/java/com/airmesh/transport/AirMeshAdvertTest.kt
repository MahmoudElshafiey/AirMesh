package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for parseAirMeshInstance / buildAirMeshInstance. */
class AirMeshAdvertTest {

    // ---- real phone logs (exact strings) ------------------------------------

    @Test
    fun `parse real log - local dot suffix`() {
        val ad = parseAirMeshInstance("airmesh-n52647-v2-t7e1b99fe-o0.local.")!!
        assertEquals(52647, ad.nodeId)
        assertEquals(2, ad.version)
        assertEquals(0x7e1b99feL, ad.tieBreak)
        assertFalse(ad.owner)
    }

    @Test
    fun `parse real log - second device`() {
        val ad = parseAirMeshInstance("airmesh-n62280-v2-t74f089b0-o0.local.")!!
        assertEquals(62280, ad.nodeId)
        assertEquals(2, ad.version)
        assertEquals(0x74f089b0L, ad.tieBreak)
        assertFalse(ad.owner)
    }

    @Test
    fun `parse real log - bare instance`() {
        val ad = parseAirMeshInstance("airmesh-n59871-v2-t08800dcf-o0")!!
        assertEquals(59871, ad.nodeId)
        assertEquals(2, ad.version)
        assertEquals(0x08800dcfL, ad.tieBreak)
        assertFalse(ad.owner)
    }

    @Test
    fun `parse real log - TXT-style full domain`() {
        val ad = parseAirMeshInstance(
            "_airmesh._tcp.airmesh-n52647-v2-t7e1b99fe-o0.local."
        )!!
        assertEquals(52647, ad.nodeId)
        assertEquals(2, ad.version)
        assertEquals(0x7e1b99feL, ad.tieBreak)
        assertFalse(ad.owner)
    }

    // ---- owner=1 variants ---------------------------------------------------

    @Test
    fun `parse owner true variant with local dot`() {
        val ad = parseAirMeshInstance("airmesh-n52647-v2-t7e1b99fe-o1.local.")!!
        assertTrue(ad.owner)
        assertEquals(52647, ad.nodeId)
    }

    @Test
    fun `parse owner true bare`() {
        val ad = parseAirMeshInstance("airmesh-n59871-v2-t08800dcf-o1")!!
        assertTrue(ad.owner)
        assertEquals(59871, ad.nodeId)
    }

    // ---- suffix variations --------------------------------------------------

    @Test
    fun `parse local without trailing dot`() {
        val ad = parseAirMeshInstance("airmesh-n52647-v2-t7e1b99fe-o0.local")!!
        assertEquals(52647, ad.nodeId)
    }

    @Test
    fun `parse _airmesh _tcp local without trailing dot`() {
        val ad = parseAirMeshInstance(
            "airmesh-n52647-v2-t7e1b99fe-o0._airmesh._tcp.local"
        )!!
        assertEquals(52647, ad.nodeId)
    }

    @Test
    fun `parse _airmesh _tcp local with trailing dot`() {
        val ad = parseAirMeshInstance(
            "airmesh-n52647-v2-t7e1b99fe-o0._airmesh._tcp.local."
        )!!
        assertEquals(52647, ad.nodeId)
    }

    // ---- case insensitivity -------------------------------------------------

    @Test
    fun `parse uppercase instance`() {
        val ad = parseAirMeshInstance("AIRMESH-N52647-V2-T7E1B99FE-O0.LOCAL.")!!
        assertEquals(52647, ad.nodeId)
        assertEquals(0x7e1b99feL, ad.tieBreak)
    }

    @Test
    fun `parse mixed case`() {
        val ad = parseAirMeshInstance("AirMesh-N100-v2-tABcd0012-O1")!!
        assertEquals(100, ad.nodeId)
        assertEquals(0xabcd0012L, ad.tieBreak)
        assertTrue(ad.owner)
    }

    // ---- buildAirMeshInstance -----------------------------------------------

    @Test
    fun `build produces canonical padded token`() {
        val ad = AirMeshAdvert(42, 2, 0x1234L, false)
        assertEquals("airmesh-n42-v2-t00001234-o0", buildAirMeshInstance(ad))
    }

    @Test
    fun `build owner true max values`() {
        val ad = AirMeshAdvert(65535, 2, 0xffffffffL, true)
        assertEquals("airmesh-n65535-v2-tffffffff-o1", buildAirMeshInstance(ad))
    }

    @Test
    fun `build tieBreak zero`() {
        val ad = AirMeshAdvert(1, 2, 0L, false)
        assertEquals("airmesh-n1-v2-t00000000-o0", buildAirMeshInstance(ad))
    }

    // ---- round-trip parse(build(x)) == x ------------------------------------

    @Test
    fun `round-trip identity`() {
        val cases = listOf(
            AirMeshAdvert(1, 2, 0L, false),
            AirMeshAdvert(65535, 2, 0xffffffffL, true),
            AirMeshAdvert(52647, 2, 0x7e1b99feL, false),
            AirMeshAdvert(100, 3, 0xaL, true),
            AirMeshAdvert(59871, 2, 0x08800dcfL, false),
        )
        for (ad in cases) {
            assertEquals(ad, parseAirMeshInstance(buildAirMeshInstance(ad)))
        }
    }

    // ---- null / empty / garbage rejection -----------------------------------

    @Test
    fun `parse null returns null`() {
        assertNull(parseAirMeshInstance(null))
    }

    @Test
    fun `parse empty returns null`() {
        assertNull(parseAirMeshInstance(""))
        assertNull(parseAirMeshInstance("   "))
    }

    @Test
    fun `parse missing parts`() {
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t01"))          // no owner
        assertNull(parseAirMeshInstance("airmesh-n7-v2"))              // no token or owner
        assertNull(parseAirMeshInstance("airmesh"))                    // just prefix
    }

    @Test
    fun `parse nodeId zero rejected`() {
        assertNull(parseAirMeshInstance("airmesh-n0-v2-t00000001-o0"))
    }

    @Test
    fun `parse nodeId above 65535 rejected`() {
        assertNull(parseAirMeshInstance("airmesh-n65536-v2-t00000001-o0"))
        assertNull(parseAirMeshInstance("airmesh-n100000-v2-t00000001-o0"))
    }

    @Test
    fun `parse owner other than 0 or 1 rejected`() {
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t00000001-o2"))
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t00000001-ox"))
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t00000001-o11"))
    }

    @Test
    fun `parse extra garbage in the middle rejected`() {
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t00000001-o0-extra"))
        assertNull(parseAirMeshInstance("airmesh-n7-extra-v2-t00000001-o0"))
        assertNull(parseAirMeshInstance("garbage-airmesh-n7-v2-t00000001-o0"))
    }

    @Test
    fun `parse wrong prefix rejected`() {
        assertNull(parseAirMeshInstance("other-n7-v2-t00000001-o0"))
        assertNull(parseAirMeshInstance("xairmesh-n7-v2-t00000001-o0"))
    }

    @Test
    fun `parse hex with 9 digits rejected`() {
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t123456789-o0"))
    }

    @Test
    fun `parse hex with 0 digits rejected`() {
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t-o0"))
    }

    @Test
    fun `parse hex with non-hex char rejected`() {
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t0000g001-o0"))
    }

    @Test
    fun `parse unknown suffix not stripped gives null`() {
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t00000001-o0.something"))
    }

    // ---- boundary: short hex accepted (1-7 digits) --------------------------

    @Test
    fun `parse accepts 1-digit hex`() {
        val ad = parseAirMeshInstance("airmesh-n1-v2-ta-o0")!!
        assertEquals(0xaL, ad.tieBreak)
    }

    @Test
    fun `parse accepts 7-digit hex`() {
        val ad = parseAirMeshInstance("airmesh-n1-v2-t1234567-o0")!!
        assertEquals(0x1234567L, ad.tieBreak)
    }
}
