package com.airmesh.presence

/**
 * Composer banner for a direct chat, by truthful presence state (TASK U5 part 2). Online
 * states show no banner; everything else explains what happens to messages sent right now.
 */
fun liveChatBannerText(state: PresenceState?): String? = when (state) {
    PresenceState.OUT_OF_RANGE, is PresenceState.LastSeen ->
        "Not in range. Messages will be delivered when you are back in range."

    PresenceState.CHECKING -> "Checking connection..."

    PresenceState.NOT_CONNECTED -> "You are not connected to the network right now."

    // ONLINE_DIRECT / ONLINE_RELAYED / UNKNOWN: nothing to warn about.
    else -> null
}
