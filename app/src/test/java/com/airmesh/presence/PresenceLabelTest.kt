package com.airmesh.presence

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the presence text keys and their rendered strings (TASK U5 part 1). */
class PresenceLabelTest {

    // A fixed "now" in UTC keeps every expectation timezone-independent.
    private val now = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
    private val zone = ZoneOffset.UTC

    @Test
    fun theLabelKeysMatchTheStates() {
        assertEquals(PresenceLabelText.KEY_ONLINE, presenceLabel(PresenceState.ONLINE_DIRECT, now).key)
        assertEquals(PresenceLabelText.KEY_ONLINE, presenceLabel(PresenceState.ONLINE_RELAYED, now).key)
        assertEquals(PresenceLabelText.KEY_CHECKING, presenceLabel(PresenceState.CHECKING, now).key)
        assertEquals(PresenceLabelText.KEY_OUT_OF_RANGE, presenceLabel(PresenceState.OUT_OF_RANGE, now).key)
        assertEquals(PresenceLabelText.KEY_NOT_CONNECTED, presenceLabel(PresenceState.NOT_CONNECTED, now).key)
        assertEquals(PresenceLabelText.KEY_NONE, presenceLabel(PresenceState.UNKNOWN, now).key)

        val lastSeen = presenceLabel(PresenceState.LastSeen(now - 123L), now)
        assertEquals(PresenceLabelText.KEY_LAST_SEEN, lastSeen.key)
        assertEquals(now - 123L, lastSeen.argMs)
    }

    @Test
    fun theFixedStatesRenderTheirExactStrings() {
        assertEquals("Online", formatPresenceLabel(presenceLabel(PresenceState.ONLINE_DIRECT, now), now, zone))
        assertEquals("Online", formatPresenceLabel(presenceLabel(PresenceState.ONLINE_RELAYED, now), now, zone))
        assertEquals("Checking...", formatPresenceLabel(presenceLabel(PresenceState.CHECKING, now), now, zone))
        assertEquals("Out of range", formatPresenceLabel(presenceLabel(PresenceState.OUT_OF_RANGE, now), now, zone))
        assertEquals("Not connected", formatPresenceLabel(presenceLabel(PresenceState.NOT_CONNECTED, now), now, zone))
        // UNKNOWN renders empty so the UI can keep its own fallback ("Not nearby").
        assertEquals("", formatPresenceLabel(presenceLabel(PresenceState.UNKNOWN, now), now, zone))
    }

    @Test
    fun lastSeenRendersRelativeSpans() {
        val at = Instant.parse("2026-11-15T11:57:00Z").toEpochMilli() // 3 min ago
        assertEquals("Last seen 3 min ago", formatPresenceLabel(presenceLabel(PresenceState.LastSeen(at), now), now, zone))

        val justNow = Instant.parse("2026-11-15T11:59:30Z").toEpochMilli() // 30 s ago
        assertEquals("Last seen just now", formatPresenceLabel(presenceLabel(PresenceState.LastSeen(justNow), now), now, zone))

        // Exactly one minute is no longer "just now".
        val oneMinute = Instant.parse("2026-11-15T11:59:00Z").toEpochMilli()
        assertEquals("Last seen 1 min ago", formatPresenceLabel(presenceLabel(PresenceState.LastSeen(oneMinute), now), now, zone))
    }

    @Test
    fun lastSeenRendersTodayAsABareTime() {
        val at = Instant.parse("2026-11-15T09:15:00Z").toEpochMilli() // same day, > 1 h ago
        assertEquals("Last seen 09:15", formatPresenceLabel(presenceLabel(PresenceState.LastSeen(at), now), now, zone))
    }

    @Test
    fun lastSeenRendersYesterdayWithTime() {
        val at = Instant.parse("2026-11-14T14:30:00Z").toEpochMilli()
        assertEquals("Last seen yesterday 14:30", formatPresenceLabel(presenceLabel(PresenceState.LastSeen(at), now), now, zone))
    }

    @Test
    fun lastSeenRendersOlderDatesWithoutTheYearWhenItMatches() {
        val at = Instant.parse("2026-11-03T10:00:00Z").toEpochMilli()
        assertEquals("Last seen Nov 3", formatPresenceLabel(presenceLabel(PresenceState.LastSeen(at), now), now, zone))

        val otherYear = Instant.parse("2025-12-31T10:00:00Z").toEpochMilli()
        assertEquals("Last seen Dec 31, 2025", formatPresenceLabel(presenceLabel(PresenceState.LastSeen(otherYear), now), now, zone))
    }

    @Test
    fun aMissingTimestampRendersEmptyInsteadOfCrashing() {
        assertEquals("", formatPresenceLabel(PresenceLabelText(PresenceLabelText.KEY_LAST_SEEN, null), now, zone))
    }
}
