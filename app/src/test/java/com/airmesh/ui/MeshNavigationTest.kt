package com.airmesh.ui

import com.airmesh.service.IncomingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for the pure notification-tap navigation parsing (no Android imports). */
class MeshNavigationTest {

    // --- parseMessageKey -------------------------------------------------------

    @Test
    fun `parses a valid text key`() {
        val key = parseMessageKey("TEXT:7:1700000000000")!!
        assertEquals(IncomingKind.TEXT, key.kind)
        assertEquals(7, key.senderId)
        assertEquals(1_700_000_000_000L, key.timestampMs)
    }

    @Test
    fun `parses a valid location key`() {
        val key = parseMessageKey("LOCATION:3:42")!!
        assertEquals(IncomingKind.LOCATION, key.kind)
        assertEquals(3, key.senderId)
        assertEquals(42L, key.timestampMs)
    }

    @Test
    fun `null and empty keys parse to null`() {
        assertNull(parseMessageKey(null))
        assertNull(parseMessageKey(""))
        assertNull(parseMessageKey("   "))
    }

    @Test
    fun `wrong part count parses to null`() {
        assertNull(parseMessageKey("TEXT:7"))
        assertNull(parseMessageKey("TEXT:7:1:9"))
        assertNull(parseMessageKey(":7:1"))
    }

    @Test
    fun `unknown or wrong-case kind parses to null`() {
        assertNull(parseMessageKey("VOICE:7:1"))
        // The service writes the enum names exactly; anything else is garbage.
        assertNull(parseMessageKey("text:7:1"))
        assertNull(parseMessageKey("Text:7:1"))
    }

    @Test
    fun `non-numeric or negative fields parse to null`() {
        assertNull(parseMessageKey("TEXT:x:1"))
        assertNull(parseMessageKey("TEXT:7:y"))
        assertNull(parseMessageKey("TEXT:-3:1"))
        assertNull(parseMessageKey("TEXT:7:-1"))
        // Underscores are Kotlin source-literal syntax, not valid in a parsed string.
        assertNull(parseMessageKey("TEXT:7:1_700"))
    }

    // --- parseNavigationTarget ---------------------------------------------------

    @Test
    fun `empty extras fall back to chat with no key`() {
        assertEquals(NavigationTarget(), parseNavigationTarget(emptyMap()))
    }

    @Test
    fun `chat tab with a valid message key`() {
        val target = parseNavigationTarget(
            mapOf(EXTRA_TAB to TAB_CHAT, EXTRA_MESSAGE_KEY to "TEXT:7:1"),
        )
        assertEquals(NavigationTarget(NavTab.CHAT, MessageKey(IncomingKind.TEXT, 7, 1L)), target)
    }

    @Test
    fun `network and log tabs parse`() {
        assertEquals(NavTab.NETWORK, parseNavigationTarget(mapOf(EXTRA_TAB to TAB_NETWORK)).tab)
        assertEquals(NavTab.LOG, parseNavigationTarget(mapOf(EXTRA_TAB to TAB_LOG)).tab)
    }

    @Test
    fun `unknown tab value falls back to chat`() {
        assertEquals(NavTab.CHAT, parseNavigationTarget(mapOf(EXTRA_TAB to "bogus")).tab)
        assertEquals(NavTab.CHAT, parseNavigationTarget(mapOf(EXTRA_TAB to "Chat")).tab)
    }

    @Test
    fun `wrong-typed tab value falls back to chat`() {
        assertEquals(NavTab.CHAT, parseNavigationTarget(mapOf(EXTRA_TAB to 5)).tab)
        assertEquals(NavTab.CHAT, parseNavigationTarget(mapOf(EXTRA_TAB to true)).tab)
    }

    @Test
    fun `garbage message key falls back to no key`() {
        val target = parseNavigationTarget(
            mapOf(EXTRA_TAB to TAB_CHAT, EXTRA_MESSAGE_KEY to "nonsense"),
        )
        assertEquals(NavigationTarget(NavTab.CHAT, null), target)

        // A non-string value in the slot is garbage too.
        val wrongType = parseNavigationTarget(mapOf(EXTRA_MESSAGE_KEY to 123))
        assertNull(wrongType.messageKey)
    }

    @Test
    fun `a key on a non-chat tab is still parsed but harmless`() {
        // The UI only consumes the key on the chat tab; parsing stays total.
        val target = parseNavigationTarget(
            mapOf(EXTRA_TAB to TAB_NETWORK, EXTRA_MESSAGE_KEY to "LOCATION:2:9"),
        )
        assertEquals(NavTab.NETWORK, target.tab)
        assertEquals(MessageKey(IncomingKind.LOCATION, 2, 9L), target.messageKey)
    }
}
