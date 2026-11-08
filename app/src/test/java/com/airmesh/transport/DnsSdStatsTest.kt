package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for the DNS-SD counter/report-window helper. */
class DnsSdStatsTest {

    @Test
    fun `fresh stats line is all zeroes`() {
        assertEquals(
            "dnssd: stats scans=0 rawService=0 rawTxt=0 accepted=0 ignored=0",
            DnsSdStats().statsLine(),
        )
    }

    @Test
    fun `each counter increments independently`() {
        val stats = DnsSdStats()

        stats.onScan()
        stats.onScan()
        stats.onRawService()
        stats.onRawTxt()
        stats.onRawTxt()
        stats.onRawTxt()
        stats.onAccepted()
        stats.onIgnored()
        stats.onIgnored()

        assertEquals(
            "dnssd: stats scans=2 rawService=1 rawTxt=3 accepted=1 ignored=2",
            stats.statsLine(),
        )
    }

    @Test
    fun `stats line text is exact`() {
        val stats = DnsSdStats()
        stats.onScan()
        stats.onRawService()
        stats.onRawTxt()
        stats.onAccepted()
        stats.onIgnored()

        // Guard the literal spacing and key order the log parser relies on.
        assertEquals(
            "dnssd: stats scans=1 rawService=1 rawTxt=1 accepted=1 ignored=1",
            stats.statsLine(),
        )
    }

    @Test
    fun `reset zeroes every counter`() {
        val stats = DnsSdStats()
        stats.onScan()
        stats.onRawService()
        stats.onRawTxt()
        stats.onAccepted()
        stats.onIgnored()

        stats.reset()

        assertEquals(
            "dnssd: stats scans=0 rawService=0 rawTxt=0 accepted=0 ignored=0",
            stats.statsLine(),
        )
    }

    @Test
    fun `counting continues from zero after reset`() {
        val stats = DnsSdStats()
        stats.onScan()
        stats.onRawService()
        stats.reset()

        stats.onScan()
        stats.onIgnored()

        assertEquals(
            "dnssd: stats scans=1 rawService=0 rawTxt=0 accepted=0 ignored=1",
            stats.statsLine(),
        )
    }
}
