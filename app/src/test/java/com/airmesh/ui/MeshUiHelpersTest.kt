package com.airmesh.ui

import com.airmesh.transport.P2pRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** JVM tests for the pure UI helpers: time, relay text, log filters, status chip. */
class MeshUiHelpersTest {

    // 1_700_000_000 s after the epoch is 2023-11-14T22:13:20Z — a fixed instant
    // that makes the formatted output deterministic in any test timezone.
    private val baseMs = 1_700_000_000_000L

    // --- Time formatting -----------------------------------------------------

    @Test
    fun chatTimeIsHoursMinutesSeconds() {
        assertEquals("22:13:20", formatChatTime(baseMs, ZoneOffset.UTC))
    }

    @Test
    fun logTimeKeepsMilliseconds() {
        assertEquals("22:13:20.000", formatLogTime(baseMs, ZoneOffset.UTC))
        assertEquals("22:13:20.456", formatLogTime(baseMs + 456L, ZoneOffset.UTC))
    }

    // --- Relay count ----------------------------------------------------------

    @Test
    fun relayCountTextCoversDirectAndRelayed() {
        assertEquals("direct", relayCountText(8)) // ttl == DEFAULT_TTL: no hops used
        assertEquals("1 relay", relayCountText(7))
        assertEquals("2 relays", relayCountText(6))
        assertEquals("8 relays", relayCountText(0))
    }

    @Test
    fun relayCountClampsWhenTtlExceedsTheInitialValue() {
        // A ttl above DEFAULT_TTL must not produce negative relays.
        assertEquals("direct", relayCountText(9))
        assertEquals("direct", relayCountText(100))
    }

    @Test
    fun relayCountHonorsACustomInitialTtl() {
        assertEquals("2 relays", relayCountText(ttl = 2, initialTtl = 4))
        assertEquals("direct", relayCountText(ttl = 5, initialTtl = 4)) // clamped again
    }

    // --- Role / Wi-Fi Direct labels -------------------------------------------

    @Test
    fun roleAndWifiDirectLabels() {
        assertEquals("No group", roleLabel(P2pRole.NONE))
        assertEquals("Group owner", roleLabel(P2pRole.GROUP_OWNER))
        assertEquals("Client", roleLabel(P2pRole.CLIENT))

        assertEquals("enabled", wifiDirectStateLabel(true))
        assertEquals("disabled", wifiDirectStateLabel(false))
        assertEquals("unknown", wifiDirectStateLabel(null))
    }

    // --- Log classification -----------------------------------------------------

    @Test
    fun messageLinesAreClassifiedAsMessages() {
        val lines = listOf(
            "TX MSG node=7 seq=1 ttl=8 -> 42718",
            "RX MSG node=42718 seq=9 ttl=6 from=30512",
            "originated MSG seq=1 ttl=8 (5 bytes)",
            "delivered MSG node=42718 seq=9 ttl=6",
            "replay: neighbor 42718: 2 stored, 1 pending for this neighbor",
        )
        lines.forEach { line ->
            assertTrue("expected MESSAGES in $line", LogCategory.MESSAGES in classifyLogLine(line))
        }
    }

    @Test
    fun p2pPrefixIsClassifiedAsNetwork() {
        assertEquals(setOf(LogCategory.NETWORK), classifyLogLine("P2P: group formed (role=owner)"))
    }

    @Test
    fun tcpLinesAreClassifiedAsTcp() {
        assertEquals(setOf(LogCategory.TCP), classifyLogLine("TCP connected to 192.168.49.1:8988 neighbor=7"))
    }

    @Test
    fun errorKeywordsAreClassifiedAsErrors() {
        val lines = listOf(
            "connect failed: ConnectionRefusedException",
            "interface dump failed: SecurityException: null",
            "corrupt frames: 1 dropped (total 1)",
        )
        lines.forEach { line ->
            assertTrue("expected ERRORS in $line", LogCategory.ERRORS in classifyLogLine(line))
        }
    }

    @Test
    fun aLineCanMatchSeveralFilters() {
        // Connector failure: P2P prefix + TCP + "failed"/"Exception".
        val connectorFailure = classifyLogLine(
            "P2P: TCP connect attempt 3 to 192.168.49.1:8988 failed in CONNECT phase " +
                "after 5003ms: SocketTimeoutException: null; retrying in 2000ms",
        )
        assertEquals(
            setOf(LogCategory.NETWORK, LogCategory.TCP, LogCategory.ERRORS),
            connectorFailure,
        )

        // Replay failure: message traffic + "failed".
        assertEquals(
            setOf(LogCategory.MESSAGES, LogCategory.ERRORS),
            classifyLogLine("replay TX seq=5 -> 7 failed"),
        )
    }

    @Test
    fun unrelatedAndEmptyLinesMatchNoFilter() {
        assertTrue(classifyLogLine("").isEmpty())
        assertTrue(classifyLogLine("node 7 started").isEmpty())
    }

    @Test
    fun matchesFilterPassesEverythingForNullAndSelectsOtherwise() {
        val line = "replay TX seq=5 -> 7 failed"
        assertTrue(matchesFilter(line, null))
        assertTrue(matchesFilter("", null)) // even an empty line passes "All"
        assertTrue(matchesFilter(line, LogCategory.MESSAGES))
        assertTrue(matchesFilter(line, LogCategory.ERRORS))
        assertFalse(matchesFilter(line, LogCategory.NETWORK))
        assertFalse(matchesFilter("node 7 started", LogCategory.MESSAGES))
    }

    // --- Status chip -------------------------------------------------------------

    @Test
    fun meshStatusDerivesFromRoleAndNeighborCount() {
        assertEquals(MeshStatus.NO_GROUP, meshStatus(P2pRole.NONE, 3)) // role wins
        assertEquals(MeshStatus.GROUP_NO_NEIGHBORS, meshStatus(P2pRole.GROUP_OWNER, 0))
        assertEquals(MeshStatus.GROUP_NO_NEIGHBORS, meshStatus(P2pRole.CLIENT, -1)) // clamped
        assertEquals(MeshStatus.CONNECTED, meshStatus(P2pRole.CLIENT, 1))
        assertEquals(MeshStatus.CONNECTED, meshStatus(P2pRole.GROUP_OWNER, 4))
    }

    @Test
    fun statusChipTextNamesTheRoleAndNeighborCount() {
        assertEquals("No group", statusChipText(P2pRole.NONE, 0))
        assertEquals("Owner · no neighbors", statusChipText(P2pRole.GROUP_OWNER, 0))
        assertEquals("Client · 1 neighbor", statusChipText(P2pRole.CLIENT, 1))
        assertEquals("Owner · 2 neighbors", statusChipText(P2pRole.GROUP_OWNER, 2))
    }
}
