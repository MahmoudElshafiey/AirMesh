package com.airmesh.ui

import com.airmesh.transport.AutoMeshState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the chats-home status chip wording (TASK U1 part 3). */
class ChatsHomeUiHelpersTest {

    @Test
    fun connectedShowsTheNeighborCount() {
        val owner = ChatsStatus.from(AutoMeshState.Owner, 3)
        assertEquals("Connected - 3 phones", owner.text)
        assertTrue(owner.connected)
        assertEquals("Connected - 1 phone", ChatsStatus.from(AutoMeshState.Client, 1).text)
        assertEquals("Connected - no phones", ChatsStatus.from(AutoMeshState.Owner, 0).text)
    }

    @Test
    fun preConnectionStatesSaySearching() {
        val states = listOf<AutoMeshState>(
            AutoMeshState.Searching(0L),
            AutoMeshState.Creating,
            AutoMeshState.Joining("AA:BB:CC:DD:EE:FF", 7, 0L),
            AutoMeshState.Backoff(1_000L, "busy"),
        )
        states.forEach { state ->
            val status = ChatsStatus.from(state, 0)
            assertEquals("Searching…", status.text)
            assertFalse(status.connected)
        }
    }

    @Test
    fun yieldingSaysMerging() {
        val status = ChatsStatus.from(AutoMeshState.Yielding("AA:BB:CC:DD:EE:FF", 7, 2, 0L), 1)
        assertEquals("Merging…", status.text)
        assertFalse(status.connected)
    }

    @Test
    fun idleIsNoGroup() {
        val status = ChatsStatus.from(AutoMeshState.Idle, 0)
        assertEquals("No group", status.text)
        assertFalse(status.connected)
    }
}
