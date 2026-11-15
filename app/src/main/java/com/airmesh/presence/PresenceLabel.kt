package com.airmesh.presence

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Text key + optional timestamp argument for a presence state (TASK U5 part 1). The UI maps
 * the key to its string; keeping the key separate from the wording makes both sides testable.
 */
data class PresenceLabelText(val key: String, val argMs: Long? = null) {
    companion object {
        const val KEY_ONLINE = "online"
        const val KEY_CHECKING = "checking"
        const val KEY_OUT_OF_RANGE = "out_of_range"
        const val KEY_NOT_CONNECTED = "not_connected"
        const val KEY_LAST_SEEN = "last_seen"

        /** UNKNOWN maps to an empty key so callers keep their own fallback ("Not nearby"). */
        const val KEY_NONE = ""
    }
}

/** Maps a state to its text key; [LastSeen] carries the evidence timestamp as the argument. */
fun presenceLabel(state: PresenceState, nowMs: Long): PresenceLabelText = when (state) {
    PresenceState.ONLINE_DIRECT,
    PresenceState.ONLINE_RELAYED -> PresenceLabelText(PresenceLabelText.KEY_ONLINE)

    PresenceState.CHECKING -> PresenceLabelText(PresenceLabelText.KEY_CHECKING)
    PresenceState.OUT_OF_RANGE -> PresenceLabelText(PresenceLabelText.KEY_OUT_OF_RANGE)
    PresenceState.NOT_CONNECTED -> PresenceLabelText(PresenceLabelText.KEY_NOT_CONNECTED)
    is PresenceState.LastSeen -> PresenceLabelText(PresenceLabelText.KEY_LAST_SEEN, state.atMs)

    PresenceState.UNKNOWN -> PresenceLabelText(PresenceLabelText.KEY_NONE)
}

/**
 * Renders a [PresenceLabelText] into the user-facing string (TASK U5 part 1):
 * "Online", "Checking...", "Out of range", "Not connected", "Last seen 3 min ago",
 * "Last seen yesterday 14:30". The zone is injectable so tests are timezone-independent.
 */
fun formatPresenceLabel(
    label: PresenceLabelText,
    nowMs: Long,
    zone: ZoneId = ZoneOffset.UTC,
): String = when (label.key) {
    PresenceLabelText.KEY_ONLINE -> "Online"
    PresenceLabelText.KEY_CHECKING -> "Checking..."
    PresenceLabelText.KEY_OUT_OF_RANGE -> "Out of range"
    PresenceLabelText.KEY_NOT_CONNECTED -> "Not connected"
    PresenceLabelText.KEY_LAST_SEEN -> label.argMs?.let { "Last seen ${formatLastSeenSpan(it, nowMs, zone)}" }.orEmpty()
    else -> ""
}

/**
 * The span after "Last seen": "just now", "N min ago", a bare time when it happened today,
 * "yesterday HH:mm", or the short date ("Nov 3", with the year only if it differs).
 */
fun formatLastSeenSpan(atMs: Long, nowMs: Long, zone: ZoneId = ZoneOffset.UTC): String {
    val ageMs = (nowMs - atMs).coerceAtLeast(0L)
    if (ageMs < 60_000L) return "just now"
    if (ageMs < 3_600_000L) return "${ageMs / 60_000L} min ago"

    val at = Instant.ofEpochMilli(atMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    // padStart instead of String.format: no locale-dependent digits.
    val time = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
    return when {
        at.toLocalDate() == now.toLocalDate() -> time
        at.toLocalDate() == now.toLocalDate().minusDays(1) -> "yesterday $time"
        else -> MONTH_NAMES[at.monthValue - 1] + " ${at.dayOfMonth}" +
            if (at.year != now.year) ", ${at.year}" else ""
    }
}

private val MONTH_NAMES = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
