package com.airmesh.ui

import com.airmesh.transport.AutoMeshState

/**
 * Top-bar status chip content for the chats home (TASK U1 part 3): a short text
 * plus whether we are in a group. Pure so every wording rule is JVM-testable;
 * [neighborCount] comes from the live neighbor set, not from the state machine.
 */
data class ChatsStatus(val text: String, val connected: Boolean) {

    companion object {
        fun from(state: AutoMeshState, neighborCount: Int): ChatsStatus = when (state) {
            is AutoMeshState.Owner, is AutoMeshState.Client ->
                ChatsStatus("Connected - ${phoneWord(neighborCount)}", true)
            // Yielding means we hand our group to a stronger owner and join it.
            is AutoMeshState.Yielding -> ChatsStatus("Merging…", false)
            // Everything between "no group" and "in a group": discovery, create,
            // join, and the quiet retry period all read as searching.
            is AutoMeshState.Searching,
            is AutoMeshState.Creating,
            is AutoMeshState.Joining,
            is AutoMeshState.Backoff -> ChatsStatus("Searching…", false)
            AutoMeshState.Idle -> ChatsStatus("No group", false)
        }

        private fun phoneWord(count: Int): String = when (count) {
            0 -> "no phones"
            1 -> "1 phone"
            else -> "$count phones"
        }
    }
}
