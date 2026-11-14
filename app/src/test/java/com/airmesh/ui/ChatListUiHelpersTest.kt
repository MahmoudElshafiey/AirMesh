package com.airmesh.ui

import com.airmesh.chat.NodePresence
import com.airmesh.chat.PresenceState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** JVM tests for the pure conversation-list text helpers (TASK U1 part 1). */
class ChatListUiHelpersTest {

    private val zone = ZoneOffset.UTC

    // "now" is a fixed instant: 2023-11-14T22:13:20Z, so every expectation below is
    // deterministic regardless of the machine's timezone.
    private val nowMs = ZonedDateTime.of(2023, 11, 14, 22, 13, 20, 0, zone).toInstant().toEpochMilli()

    private fun ts(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    // --- List time formatting --------------------------------------------------

    @Test
    fun listTimeIsClockForToday() {
        assertEquals("22:13", formatListTime(nowMs, nowMs, zone))
        assertEquals("21:13", formatListTime(ts(2023, 11, 14, 21, 13), nowMs, zone))
    }

    @Test
    fun listTimeIsYesterdayForThePreviousDay() {
        assertEquals("Yesterday", formatListTime(ts(2023, 11, 13, 9, 5), nowMs, zone))
    }

    @Test
    fun listTimeIsShortDateWithinThisYear() {
        assertEquals("Sep 5", formatListTime(ts(2023, 9, 5, 10, 0), nowMs, zone))
    }

    @Test
    fun listTimeIsFullDateForEarlierYears() {
        assertEquals("14 Nov 2022", formatListTime(ts(2022, 11, 14, 22, 13), nowMs, zone))
    }

    @Test
    fun listTimeIsEmptyWhenThereIsNoTimestamp() {
        assertEquals("", formatListTime(0L, nowMs, zone))
        assertEquals("", formatListTime(-5L, nowMs, zone))
    }

    // --- Presence text ---------------------------------------------------------

    private val online = NodePresence(PresenceState.ONLINE, true)
    private val recent = NodePresence(PresenceState.RECENT, false)
    private val unknown = NodePresence(PresenceState.UNKNOWN, false)

    @Test
    fun presenceTextIsOnlineForLiveNodes() {
        assertEquals("Online", presenceText(online, nowMs))
        // A stale last-seen time never overrides "Online".
        assertEquals("Online", presenceText(online, nowMs, lastSeenMs = nowMs - 3_600_000L))
    }

    @Test
    fun presenceTextUsesTheLastSeenSpanWhenKnown() {
        assertEquals("Last seen just now", presenceText(recent, nowMs, lastSeenMs = nowMs - 15_000L))
        assertEquals("Last seen 3 min ago", presenceText(recent, nowMs, lastSeenMs = nowMs - 180_000L))
        assertEquals("Last seen 2 h ago", presenceText(recent, nowMs, lastSeenMs = nowMs - 7_200_000L))
        assertEquals("Last seen 5 d ago", presenceText(recent, nowMs, lastSeenMs = nowMs - 432_000_000L))
    }

    @Test
    fun presenceTextFallsBackToRecentlyForRecentWithoutATimestamp() {
        assertEquals("Last seen recently", presenceText(recent, nowMs))
    }

    @Test
    fun presenceTextIsEmptyForUnknownWithoutATimestamp() {
        assertEquals("", presenceText(unknown, nowMs))
    }

    @Test
    fun presenceTextShowsASpanForAnyStateWithALastSeenTime() {
        // Even an UNKNOWN node with a recorded last-seen time gets a span.
        assertEquals("Last seen 1 min ago", presenceText(unknown, nowMs, lastSeenMs = nowMs - 60_000L))
    }

    // --- Preview text ----------------------------------------------------------

    @Test
    fun previewLeavesShortTextUntouched() {
        assertEquals("Copy that.", conversationPreviewText("Copy that."))
    }

    @Test
    fun previewCollapsesWhitespaceAndTrims() {
        assertEquals("a b c", conversationPreviewText("  a\n\n   b\tc  "))
    }

    @Test
    fun previewIsEmptyForBlankInput() {
        assertEquals("", conversationPreviewText("   \n  "))
    }

    @Test
    fun previewTruncatesWithAnEllipsisAtTheCap() {
        val long = "x".repeat(200)
        val out = conversationPreviewText(long, maxChars = 40)
        assertEquals(40, out.length)
        assertEquals("…", out.substring(out.length - 1))

        // A space at the cut point is trimmed so the ellipsis sits on a character.
        val spaced = "a ".repeat(200)
        val outSpaced = conversationPreviewText(spaced, maxChars = 40)
        assertEquals(40, outSpaced.length)
        assertEquals("…", outSpaced.substring(outSpaced.length - 1))
    }

    // --- Unread badge ----------------------------------------------------------

    @Test
    fun unreadBadgeIsHiddenAtZeroAndNegative() {
        assertEquals("", unreadBadgeText(0))
        assertEquals("", unreadBadgeText(-3))
    }

    @Test
    fun unreadBadgeShowsTheCountUpToNinetyNine() {
        assertEquals("1", unreadBadgeText(1))
        assertEquals("99", unreadBadgeText(99))
    }

    @Test
    fun unreadBadgeCapsAboveNinetyNine() {
        assertEquals("99+", unreadBadgeText(100))
        assertEquals("99+", unreadBadgeText(1234))
    }
}
