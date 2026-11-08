package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the DNS-SD service-type normalization and matching. */
class DnsSdServiceTypeTest {

    // --- normalizeServiceType ---------------------------------------------------

    @Test
    fun `bare type stays unchanged`() {
        assertEquals("_airmesh._tcp", normalizeServiceType("_airmesh._tcp"))
    }

    @Test
    fun `strips the local suffix and trailing dot`() {
        assertEquals("_airmesh._tcp", normalizeServiceType("_airmesh._tcp.local."))
    }

    @Test
    fun `strips a trailing dot without local`() {
        assertEquals("_airmesh._tcp", normalizeServiceType("_airmesh._tcp."))
    }

    @Test
    fun `lowercases the input`() {
        assertEquals("_airmesh._tcp", normalizeServiceType("_AirMesh._TCP"))
    }

    @Test
    fun `uppercase local domain normalizes`() {
        assertEquals("_airmesh._tcp", normalizeServiceType("_AIRMESH._TCP.LOCAL."))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("_airmesh._tcp", normalizeServiceType("  _airmesh._tcp.local.  "))
    }

    @Test
    fun `null becomes empty`() {
        assertEquals("", normalizeServiceType(null))
    }

    @Test
    fun `garbage stays garbage`() {
        assertEquals("not a service", normalizeServiceType("not a service"))
    }

    @Test
    fun `other service keeps its own name`() {
        assertEquals("_http._tcp", normalizeServiceType("_http._tcp.local."))
    }

    // --- isAirMeshService: type decides -----------------------------------------

    @Test
    fun `accepts the airmesh type in every shape`() {
        assertTrue(isAirMeshService("_airmesh._tcp", null))
        assertTrue(isAirMeshService("_airmesh._tcp.local.", null))
        assertTrue(isAirMeshService("_airmesh._tcp.", null))
        assertTrue(isAirMeshService("_AirMesh._TCP.local.", null))
        assertTrue(isAirMeshService("  _AIRMESH._TCP.LOCAL.  ", null))
    }

    @Test
    fun `rejects a different service`() {
        assertFalse(isAirMeshService("_http._tcp.local.", null))
        assertFalse(isAirMeshService("_http._tcp.local.", "printer"))
    }

    @Test
    fun `rejects null garbage and blank types`() {
        assertFalse(isAirMeshService(null, null))
        assertFalse(isAirMeshService("", null))
        assertFalse(isAirMeshService("garbage", null))
        assertFalse(isAirMeshService("airmesh-token", null)) // prefix belongs to the instance, not the type
    }

    // --- isAirMeshService: instance prefix fallback ------------------------------

    @Test
    fun `accepts the instance prefix only`() {
        assertTrue(isAirMeshService(null, "airmesh-1234"))
        assertTrue(isAirMeshService("_http._tcp.local.", "airmesh-1234"))
        assertTrue(isAirMeshService("", "Airmesh-ABCD"))
        assertTrue(isAirMeshService("garbage", "  airmesh-xyz  "))
    }

    @Test
    fun `prefix must be at the start and dash-terminated`() {
        assertFalse(isAirMeshService(null, "my-airmesh-1"))
        assertFalse(isAirMeshService(null, "airmesh1"))
        assertFalse(isAirMeshService(null, ""))
        assertFalse(isAirMeshService(null, "   "))
    }

    // --- isAirMeshService: combined ---------------------------------------------

    @Test
    fun `type only or instance only both pass`() {
        assertTrue(isAirMeshService("_airmesh._tcp", "whatever"))
        assertTrue(isAirMeshService("_http._tcp", "airmesh-42"))
    }

    @Test
    fun `neither type nor prefix fails`() {
        assertFalse(isAirMeshService("_http._tcp", "printer"))
        assertFalse(isAirMeshService(null, "0A1B2C3D"))
    }
}
