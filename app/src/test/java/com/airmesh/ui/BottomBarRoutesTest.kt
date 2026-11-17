package com.airmesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for the bottom-bar route rule (TASK R1 part 3): only Chats and Network keep the bar. */
class BottomBarRoutesTest {

    @Test
    fun chatsAndNetworkKeepTheBar() {
        assertEquals(NavTab.CHAT, bottomBarRoutes(ROUTE_CHATS))
        assertEquals(NavTab.NETWORK, bottomBarRoutes(ROUTE_NETWORK))
    }

    @Test
    fun theEventLogIsFullScreenSoItHasNoBar() {
        assertNull(bottomBarRoutes(ROUTE_LOG))
    }

    @Test
    fun detailRoutesNeverHadTheBar() {
        assertNull(bottomBarRoutes(ROUTE_PROFILE))
        assertNull(bottomBarRoutes("chat/7"))
    }

    @Test
    fun theNetworkQueryPatternStillSelectsNetwork() {
        // The registered pattern reports a query; baseRoute strips it before the comparison.
        assertEquals(NavTab.NETWORK, bottomBarRoutes(ROUTE_NETWORK_PATTERN))
        assertEquals(NavTab.NETWORK, bottomBarRoutes(networkRouteExpandAdvanced()))
    }

    @Test
    fun noEntryYetCountsAsTheStartDestination() {
        // While the first destination settles, the bar shows with Chats selected.
        assertEquals(NavTab.CHAT, bottomBarRoutes(null))
    }

    @Test
    fun unknownRoutesHaveNoBar() {
        assertNull(bottomBarRoutes("bogus"))
    }
}
