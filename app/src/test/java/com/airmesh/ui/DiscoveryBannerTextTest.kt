package com.airmesh.ui

import com.airmesh.transport.AutoMeshState
import com.airmesh.transport.NO_AIRMESH_PEERS_AFTER_MS
import com.airmesh.transport.discoveryStatusLine
import com.airmesh.transport.noAirMeshPeersBannerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the banner's discovery line and the log filter classification. */
class DiscoveryBannerTextTest {

    private fun searching(sinceMs: Long = 0L) = AutoMeshState.Searching(sinceMs)

    private val bannerStartMs = NO_AIRMESH_PEERS_AFTER_MS

    // --- Banner second line -------------------------------------------------------

    @Test
    fun `discovery status line text is exact`() {
        assertEquals("Discovery strategy 1, raw callbacks 0", discoveryStatusLine(1, 0))
        assertEquals("Discovery strategy 4, raw callbacks 137", discoveryStatusLine(4, 137))
    }

    @Test
    fun `banner without strategy values keeps the old single line`() {
        val text = noAirMeshPeersBannerText(searching(), bannerStartMs, 0, 3)

        assertEquals(
            "Found 3 Wi-Fi Direct devices but none advertises AirMesh. Open Advanced to connect manually.",
            text,
        )
        assertFalse(text!!.contains('\n'))
    }

    @Test
    fun `banner appends the strategy line after a newline`() {
        val text = noAirMeshPeersBannerText(
            searching(), bannerStartMs, 0, 3, strategy = 2, rawCallbacks = 5,
        )!!

        val lines = text.split('\n')
        assertEquals(2, lines.size)
        assertEquals(
            "Found 3 Wi-Fi Direct devices but none advertises AirMesh. Open Advanced to connect manually.",
            lines[0],
        )
        assertEquals("Discovery strategy 2, raw callbacks 5", lines[1])
    }

    @Test
    fun `single device wording is kept with the strategy line`() {
        val text = noAirMeshPeersBannerText(
            searching(), bannerStartMs, 0, 1, strategy = 3, rawCallbacks = 0,
        )!!

        assertTrue(text.startsWith("Found 1 Wi-Fi Direct device but none advertises AirMesh."))
        assertTrue(text.endsWith("\nDiscovery strategy 3, raw callbacks 0"))
    }

    @Test
    fun `strategy values do not resurrect a hidden banner`() {
        assertNull(noAirMeshPeersBannerText(searching(), bannerStartMs - 1, 0, 3, 2, 5))
        assertNull(noAirMeshPeersBannerText(searching(), bannerStartMs, 1, 3, 2, 5))
        assertNull(noAirMeshPeersBannerText(searching(), bannerStartMs, 0, 0, 2, 5))
        assertNull(noAirMeshPeersBannerText(AutoMeshState.Idle, bannerStartMs, 0, 3, 2, 5))
    }

    // --- Log filter classification ------------------------------------------------

    @Test
    fun `dnssd lines classify as network`() {
        assertTrue(LogCategory.NETWORK in classifyLogLine("dnssd: raw service instance='abc' type='_airmesh._tcp'"))
        assertTrue(LogCategory.NETWORK in classifyLogLine("dnssd: service cycle complete"))
        assertTrue(matchesFilter("dnssd: stats scans=1 rawService=0", LogCategory.NETWORK))
    }

    @Test
    fun `auto lines classify as network`() {
        assertTrue(LogCategory.NETWORK in classifyLogLine("auto: tick"))
        assertTrue(LogCategory.NETWORK in classifyLogLine("auto: controller ready (node #7)"))
        assertTrue(matchesFilter("auto: searching for peers", LogCategory.NETWORK))
    }

    @Test
    fun `a dnssd failure is network and errors`() {
        assertEquals(
            setOf(LogCategory.NETWORK, LogCategory.ERRORS),
            classifyLogLine("dnssd: addServiceRequest failed reason=3"),
        )
    }

    @Test
    fun `p2p lines stay network and dnssd is not a message`() {
        assertTrue(LogCategory.NETWORK in classifyLogLine("P2P: group formed (role=owner)"))
        assertFalse(LogCategory.MESSAGES in classifyLogLine("dnssd: raw txt fullDomain='_airmesh._tcp'"))
        assertFalse(matchesFilter("dnssd: raw txt", LogCategory.MESSAGES))
    }

    @Test
    fun `unrelated lines still classify as nothing`() {
        assertTrue(classifyLogLine("node 7 started").isEmpty())
        assertFalse(matchesFilter("node 7 started", LogCategory.NETWORK))
    }
}
