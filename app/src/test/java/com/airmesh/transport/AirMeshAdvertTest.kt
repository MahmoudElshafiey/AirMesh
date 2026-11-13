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

    // ---- client count suffix (-c<count>) ------------------------------------

    @Test
    fun `parse owner with c3 and local dot`() {
        val ad = parseAirMeshInstance("airmesh-n13230-v2-t3a559a93-o1-c3.local.")!!
        assertTrue(ad.owner)
        assertEquals(13230, ad.nodeId)
        assertEquals(3, ad.clients)
    }

    @Test
    fun `parse old format without suffix has zero clients`() {
        val ad = parseAirMeshInstance("airmesh-n52647-v2-t7e1b99fe-o0.local.")!!
        assertEquals(0, ad.clients)
        val owner = parseAirMeshInstance("airmesh-n52647-v2-t7e1b99fe-o1")!!
        assertEquals(0, owner.clients)
    }

    @Test
    fun `parse c0 is zero clients`() {
        val ad = parseAirMeshInstance("airmesh-n7-v2-t00000001-o1-c0.local.")!!
        assertTrue(ad.owner)
        assertEquals(0, ad.clients)
    }

    @Test
    fun `parse c99 is ninety-nine clients`() {
        val ad = parseAirMeshInstance("airmesh-n7-v2-t00000001-o1-c99")!!
        assertEquals(99, ad.clients)
    }

    @Test
    fun `parse c100 clamps to 99`() {
        val ad = parseAirMeshInstance("airmesh-n7-v2-t00000001-o1-c100.local.")!!
        assertEquals(99, ad.clients)
    }

    @Test
    fun `parse huge count clamps to 99`() {
        val ad = parseAirMeshInstance("airmesh-n7-v2-t00000001-o1-c99999999999999999999")!!
        assertEquals(99, ad.clients)
    }

    @Test
    fun `parse client count garbage rejected`() {
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t00000001-o1-c"))          // no digits
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t00000001-o1-cx"))         // non-digit
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t00000001-o1-c3-extra"))   // trailing garbage
        assertNull(parseAirMeshInstance("airmesh-n7-v2-t00000001-c3-o1"))         // count before owner flag
    }

    @Test
    fun `build emits no suffix for zero clients`() {
        val ad = AirMeshAdvert(42, 2, 0x1234L, true, 0)
        assertEquals("airmesh-n42-v2-t00001234-o1", buildAirMeshInstance(ad))
    }

    @Test
    fun `build emits c suffix for positive clients`() {
        val ad = AirMeshAdvert(13230, 2, 0x3a559a93L, true, 3)
        assertEquals("airmesh-n13230-v2-t3a559a93-o1-c3", buildAirMeshInstance(ad))
    }

    @Test
    fun `build clamps count above 99 to two digits`() {
        val ad = AirMeshAdvert(42, 2, 0x1234L, true, 150)
        assertEquals("airmesh-n42-v2-t00001234-o1-c99", buildAirMeshInstance(ad))
    }

    @Test
    fun `round-trip with client counts`() {
        val cases = listOf(
            AirMeshAdvert(1, 2, 0L, true, 0),
            AirMeshAdvert(65535, 2, 0xffffffffL, true, 99),
            AirMeshAdvert(52647, 2, 0x7e1b99feL, false, 0),
            AirMeshAdvert(100, 3, 0xaL, true, 1),
        )
        for (ad in cases) {
            assertEquals(ad, parseAirMeshInstance(buildAirMeshInstance(ad)))
        }
    }

    @Test
    fun `round-trip of count above 99 clamps to 99`() {
        val ad = AirMeshAdvert(42, 2, 0x1234L, true, 150)
        assertEquals(AirMeshAdvert(42, 2, 0x1234L, true, 99), parseAirMeshInstance(buildAirMeshInstance(ad)))
    }

    // ---- re-registration gate -------------------------------------------------

    @Test
    fun `re-register passes on first real change`() {
        assertTrue(shouldReRegisterAdvert(lastCount = 0, lastAtMs = 0L, newCount = 3, nowMs = 1_000L))
    }

    @Test
    fun `re-register blocked when count unchanged`() {
        assertFalse(shouldReRegisterAdvert(lastCount = 3, lastAtMs = 1_000L, newCount = 3, nowMs = 60_000L))
    }

    @Test
    fun `re-register blocked inside the ten second interval`() {
        assertFalse(shouldReRegisterAdvert(lastCount = 3, lastAtMs = 1_000L, newCount = 4, nowMs = 10_999L))
    }

    @Test
    fun `re-register passes once the ten second interval elapsed`() {
        assertTrue(shouldReRegisterAdvert(lastCount = 3, lastAtMs = 1_000L, newCount = 4, nowMs = 11_000L))
    }

    @Test
    fun `re-register honors a custom interval`() {
        assertFalse(shouldReRegisterAdvert(lastCount = 3, lastAtMs = 1_000L, newCount = 4, nowMs = 5_999L, minIntervalMs = 6_000L))
        assertTrue(shouldReRegisterAdvert(lastCount = 3, lastAtMs = 1_000L, newCount = 4, nowMs = 7_000L, minIntervalMs = 6_000L))
    }
}
