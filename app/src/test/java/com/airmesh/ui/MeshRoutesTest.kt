package com.airmesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the navigation-graph route helpers (TASK U1 part 2). */
class MeshRoutesTest {

    @Test
    fun chatRouteEmbedsTheConversationId() {
        assertEquals("chat/0", chatRoute(0))
        assertEquals("chat/42", chatRoute(42))
    }

    @Test
    fun navTabMapsToItsTopLevelRoute() {
        assertEquals(ROUTE_CHATS, navTabToRoute(NavTab.CHAT))
        assertEquals(ROUTE_NETWORK, navTabToRoute(NavTab.NETWORK))
        assertEquals(ROUTE_LOG, navTabToRoute(NavTab.LOG))
    }

    @Test
    fun topLevelRoutesAreExactlyTheBottomBarOnes() {
        // No entry yet: the bar should show while the first destination settles.
        assertTrue(isTopLevelRoute(null))
        assertTrue(isTopLevelRoute(ROUTE_CHATS))
        assertTrue(isTopLevelRoute(ROUTE_NETWORK))
        assertTrue(isTopLevelRoute(ROUTE_LOG))
        assertFalse(isTopLevelRoute(ROUTE_PROFILE))
        assertFalse(isTopLevelRoute("chat/7"))
    }
}
