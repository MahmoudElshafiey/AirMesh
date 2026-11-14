package com.airmesh.ui

import com.airmesh.chat.ChatDirection
import com.airmesh.chat.ChatKind
import com.airmesh.chat.ChatMessage
import com.airmesh.chat.ChatStatus
import com.airmesh.chat.Contact
import com.airmesh.chat.MESH_CONVERSATION_ID
import com.airmesh.chat.NodePresence
import com.airmesh.chat.PresenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** JVM tests for the pure chat-screen presentation logic (TASK U2 part 1). */
class ChatScreenUiHelpersTest {

    private val zone = ZoneId.of("UTC")
    /** A fixed "now": 2026-11-14T12:00 UTC. */
    private val nowMs = LocalDate.of(2026, 11, 14).atTime(12, 0)
        .toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun msgAt(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDate.of(2026, 11, day).atTime(hour, minute)
            .toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun message(
        createdAt: Long,
        direction: ChatDirection = ChatDirection.IN,
        originNode: Int = 7,
        status: ChatStatus = ChatStatus.SENT,
    ) = ChatMessage(
        conversationId = 0,
        direction = direction,
        kind = ChatKind.TEXT,
        text = "hi",
        originNode = originNode,
        originSeq = 1,
        createdAt = createdAt,
        status = status,
        isRead = true,
    )

    // --- deliveryGlyph -------------------------------------------------------

    @Test
    fun queuedShowsClock() {
        assertEquals(DeliveryGlyph.CLOCK, deliveryGlyph(ChatStatus.QUEUED, ageMs = 0L))
    }

    @Test
    fun sentWithinTenMinutesShowsOneTick() {
        assertEquals(
            DeliveryGlyph.ONE_TICK,
            deliveryGlyph(ChatStatus.SENT, ageMs = NOT_DELIVERED_AFTER_MS - 1),
        )
    }

    @Test
    fun deliveredAlwaysShowsTwoTicksEvenWhenOld() {
        assertEquals(
            DeliveryGlyph.TWO_TICKS,
            deliveryGlyph(ChatStatus.DELIVERED, ageMs = NOT_DELIVERED_AFTER_MS + 10_000),
        )
    }

    @Test
    fun sentAfterTenMinutesShowsExclamation() {
        assertEquals(
            DeliveryGlyph.EXCLAMATION,
            deliveryGlyph(ChatStatus.SENT, ageMs = NOT_DELIVERED_AFTER_MS + 1),
        )
    }

    @Test
    fun queuedAfterTenMinutesShowsExclamation() {
        assertEquals(
            DeliveryGlyph.EXCLAMATION,
            deliveryGlyph(ChatStatus.QUEUED, ageMs = NOT_DELIVERED_AFTER_MS + 1),
        )
    }

    @Test
    fun exactlyAtTheLimitStillShowsTicks() {
        assertEquals(DeliveryGlyph.ONE_TICK, deliveryGlyph(ChatStatus.SENT, ageMs = NOT_DELIVERED_AFTER_MS))
    }

    // --- startsNewGroup ------------------------------------------------------

    @Test
    fun firstMessageStartsAGroup() {
        assertTrue(startsNewGroup(null, message(msgAt(14, 9))))
    }

    @Test
    fun sameSenderWithinTwoMinutesStaysInOneGroup() {
        val previous = message(msgAt(14, 9, 0))
        val current = message(msgAt(14, 9, 1)) // 60 s later
        assertFalse(startsNewGroup(previous, current))
    }

    @Test
    fun differentDirectionStartsAGroup() {
        val previous = message(msgAt(14, 9, 0), direction = ChatDirection.IN)
        val current = message(msgAt(14, 9, 1), direction = ChatDirection.OUT)
        assertTrue(startsNewGroup(previous, current))
    }

    @Test
    fun differentOriginNodeStartsAGroupInTheMeshRoom() {
        val previous = message(msgAt(14, 9, 0), originNode = 7)
        val current = message(msgAt(14, 9, 1), originNode = 8)
        assertTrue(startsNewGroup(previous, current))
    }

    @Test
    fun gapOverTwoMinutesStartsAGroup() {
        val previous = message(msgAt(14, 9, 0))
        val current = message(msgAt(14, 9, 2) + 1L) // 120_001 ms later
        assertTrue(startsNewGroup(previous, current))
    }

    @Test
    fun exactlyTwoMinutesStillGroups() {
        val previous = message(msgAt(14, 9, 0))
        val current = message(msgAt(14, 9, 2)) // exactly 120_000 ms later
        assertFalse(startsNewGroup(previous, current))
    }

    // --- day separators ------------------------------------------------------

    @Test
    fun todayIsLabelledToday() {
        assertEquals("Today", daySeparatorLabel(msgAt(14, 8), nowMs, zone))
    }

    @Test
    fun yesterdayIsLabelledYesterday() {
        assertEquals("Yesterday", daySeparatorLabel(msgAt(13, 23), nowMs, zone))
    }

    @Test
    fun olderDaysGetTheLocalizedDate() {
        val label = daySeparatorLabel(msgAt(1, 9), nowMs, zone)
        assertTrue(label != "Today" && label != "Yesterday")
        assertTrue("expected a date containing the year, got '$label'", label.contains("2026"))
    }

    @Test
    fun dayBoundaryFollowsTheInjectedZone() {
        // 2026-11-14T01:00Z is already Nov 14 in UTC+3; a message at Nov 13T23:00Z is
        // also Nov 14 there, so it must read "Today" even though the days differ in UTC.
        val plusThree = ZoneId.of("Etc/GMT-3")
        val localNow = LocalDate.of(2026, 11, 14).atTime(1, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        val lateEveningBefore = LocalDate.of(2026, 11, 13).atTime(23, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("Today", daySeparatorLabel(lateEveningBefore, localNow, plusThree))
    }

    @Test
    fun needsDaySeparatorOnlyWhenTheCalendarDayChanges() {
        val previous = message(msgAt(14, 9))
        assertTrue(needsDaySeparator(null, previous, zone))
        assertFalse(needsDaySeparator(previous, message(msgAt(14, 23)), zone))
        assertTrue(needsDaySeparator(message(msgAt(13, 23)), message(msgAt(14, 0)), zone))
    }

    // --- buildChatUiRows -----------------------------------------------------

    @Test
    fun emptyListGivesNoRows() {
        assertTrue(buildChatUiRows(emptyList(), nowMs, zone).isEmpty())
    }

    @Test
    fun singleMessageGetsADayRowAndStartsAGroup() {
        val rows = buildChatUiRows(listOf(message(msgAt(14, 9))), nowMs, zone)
        assertEquals(2, rows.size)
        assertTrue(rows[0] is ChatUiRow.Day && (rows[0] as ChatUiRow.Day).label == "Today")
        val msgRow = rows[1] as ChatUiRow.Message
        assertTrue(msgRow.newGroup)
    }

    @Test
    fun consecutiveSameSenderMessagesShareOneDayRow() {
        val rows = buildChatUiRows(
            listOf(message(msgAt(14, 9, 0)), message(msgAt(14, 9, 1))),
            nowMs,
            zone,
        )
        assertEquals(3, rows.size) // one Day + two Messages
        val second = rows[2] as ChatUiRow.Message
        assertFalse(second.newGroup)
    }

    @Test
    fun dayChangeInsertsASeparatorAndBreaksTheGroup() {
        val rows = buildChatUiRows(
            listOf(message(msgAt(13, 23)), message(msgAt(14, 0))),
            nowMs,
            zone,
        )
        assertEquals(4, rows.size) // Day(Yesterday), Message, Day(Today), Message
        val labels = rows.filterIsInstance<ChatUiRow.Day>().map { it.label }
        assertEquals(listOf("Yesterday", "Today"), labels)
        val lastMessage = rows[3] as ChatUiRow.Message
        assertTrue(lastMessage.newGroup)
    }

    // --- chatBannerText ------------------------------------------------------

    @Test
    fun notConnectedShowsTheMeshBannerEvenInTheRoom() {
        assertEquals(
            NOT_CONNECTED_BANNER,
            chatBannerText(MESH_CONVERSATION_ID, null, connected = false),
        )
    }

    @Test
    fun offlineContactInADirectChatShowsTheOfflineBanner() {
        val recent = NodePresence(PresenceState.RECENT, isDirectNeighbor = false)
        assertEquals(
            OFFLINE_CONTACT_BANNER,
            chatBannerText(conversationId = 7, peerPresence = recent, connected = true),
        )
    }

    @Test
    fun unknownPresenceCountsAsNotNearby() {
        val unknown = NodePresence(PresenceState.UNKNOWN, isDirectNeighbor = false)
        assertEquals(
            OFFLINE_CONTACT_BANNER,
            chatBannerText(conversationId = 7, peerPresence = unknown, connected = true),
        )
    }

    @Test
    fun onlineContactShowsNoBanner() {
        val online = NodePresence(PresenceState.ONLINE, isDirectNeighbor = true)
        assertEquals(null, chatBannerText(conversationId = 7, peerPresence = online, connected = true))
    }

    @Test
    fun connectedRoomShowsNoBanner() {
        assertEquals(null, chatBannerText(MESH_CONVERSATION_ID, null, connected = true))
    }

    // --- sender name and color ----------------------------------------------

    private val profiles = emptyMap<Int, com.airmesh.profile.ProfileInfo>()

    @Test
    fun contactNameWinsOverTheFallback() {
        val contact = Contact(nodeId = 7, displayName = "Amina", colorIndex = 3)
        assertEquals("Amina", senderNameFor(7, contact, profiles))
    }

    @Test
    fun blankContactNameFallsBackToUserId() {
        val contact = Contact(nodeId = 7, displayName = "   ", colorIndex = 0)
        assertEquals("User #7", senderNameFor(7, contact, profiles))
    }

    @Test
    fun missingContactFallsBackToUserId() {
        assertEquals("User #9", senderNameFor(9, null, profiles))
    }

    @Test
    fun knownContactColorIsUsed() {
        val contact = Contact(nodeId = 7, displayName = "Amina", colorIndex = 3)
        assertEquals(3, senderColorIndexFor(7, contact, profiles))
    }

    @Test
    fun unknownContactColorFallsBackToTheDeterministicOne() {
        // No contact row and no profile: the color is derived from the node id.
        val expected = avatarColorIndex(9, emptyMap())
        assertEquals(expected, senderColorIndexFor(9, null, profiles))
    }
}
