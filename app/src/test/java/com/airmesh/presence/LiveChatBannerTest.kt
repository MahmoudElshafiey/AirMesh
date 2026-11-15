package com.airmesh.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the composer banner text by truthful presence state (TASK U5 part 2). */
class LiveChatBannerTest {

    @Test
    fun outOfRangeAndLastSeenShareTheStoreAndForwardNote() {
        val expected = "Not in range. Messages will be delivered when you are back in range."
        assertEquals(expected, liveChatBannerText(PresenceState.OUT_OF_RANGE))
        assertEquals(expected, liveChatBannerText(PresenceState.LastSeen(123L)))
    }

    @Test
    fun checkingSaysChecking() {
        assertEquals("Checking connection...", liveChatBannerText(PresenceState.CHECKING))
    }

    @Test
    fun notConnectedSaysSo() {
        assertEquals("You are not connected to the network right now.", liveChatBannerText(PresenceState.NOT_CONNECTED))
    }

    @Test
    fun onlineStatesAndUnknownShowNoBanner() {
        assertNull(liveChatBannerText(PresenceState.ONLINE_DIRECT))
        assertNull(liveChatBannerText(PresenceState.ONLINE_RELAYED))
        assertNull(liveChatBannerText(PresenceState.UNKNOWN))
        assertNull(liveChatBannerText(null))
    }
}
