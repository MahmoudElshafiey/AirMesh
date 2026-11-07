package com.airmesh.ui

import com.airmesh.service.IncomingKind

/**
 * Pure navigation logic for notification taps: parses the extras a notification's
 * content intent carries into a [NavigationTarget] the UI can act on. No Android
 * imports, so every rule is unit-testable on the JVM — the activity feeds it a
 * plain map instead of a Bundle.
 */

/** The tab a notification tap should land on; mapped explicitly to MeshTab in the UI. */
enum class NavTab { CHAT, NETWORK, LOG }

/** A chat item to scroll to after opening from a notification. */
data class MessageKey(val kind: IncomingKind, val senderId: Int, val timestampMs: Long)

/** Where a notification tap should take the UI; defaults land on the Chat tab top. */
data class NavigationTarget(
    val tab: NavTab = NavTab.CHAT,
    val messageKey: MessageKey? = null,
)

/**
 * A navigation request with a sequence number so repeated taps of the same
 * notification always re-trigger the UI effect (two targets are value-equal).
 */
data class NavRequest(val seq: Int, val target: NavigationTarget)

// --- Intent extra keys and values --------------------------------------------

const val EXTRA_TAB = "airmesh_nav_tab"
const val EXTRA_MESSAGE_KEY = "airmesh_nav_message_key"

/** Values for [EXTRA_TAB]. */
const val TAB_CHAT = "chat"
const val TAB_NETWORK = "network"
const val TAB_LOG = "log"

/**
 * Parses notification extras into a [NavigationTarget]. Unknown, missing or
 * garbage values fall back to the Chat tab with no scroll target:
 * - [EXTRA_TAB] must be one of the known strings; anything else (wrong type,
 *   unknown value, absent) means CHAT.
 * - [EXTRA_MESSAGE_KEY] must be a well-formed "KIND:senderId:timestampMs" string
 *   (the same format the service uses for notification dedup); anything else
 *   means no scroll target.
 */
fun parseNavigationTarget(extras: Map<String, Any?>): NavigationTarget {
    val tab = when (extras[EXTRA_TAB]) {
        TAB_CHAT -> NavTab.CHAT
        TAB_NETWORK -> NavTab.NETWORK
        TAB_LOG -> NavTab.LOG
        else -> NavTab.CHAT
    }
    return NavigationTarget(tab = tab, messageKey = parseMessageKey(extras[EXTRA_MESSAGE_KEY] as? String))
}

/** Parses a "KIND:senderId:timestampMs" key; null when missing or malformed. */
fun parseMessageKey(raw: String?): MessageKey? {
    if (raw == null) return null
    val parts = raw.split(":")
    if (parts.size != 3) return null
    val kind = when (parts[0]) {
        IncomingKind.TEXT.name -> IncomingKind.TEXT
        IncomingKind.LOCATION.name -> IncomingKind.LOCATION
        else -> return null
    }
    val senderId = parts[1].toIntOrNull() ?: return null
    val timestampMs = parts[2].toLongOrNull() ?: return null
    if (senderId < 0 || timestampMs < 0) return null
    return MessageKey(kind, senderId, timestampMs)
}
