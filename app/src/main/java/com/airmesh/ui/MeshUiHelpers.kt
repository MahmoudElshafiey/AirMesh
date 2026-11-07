package com.airmesh.ui

import com.airmesh.protocol.FrameCodec
import com.airmesh.routing.Router
import com.airmesh.transport.P2pRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure presentation helpers for the mesh UI: time formatting, relay-count text,
 * log-line classification and status-chip derivation.
 *
 * No Android imports, so every function is unit-testable on the JVM with a
 * fixed zone; the Compose layer only renders what these functions return.
 */

// --- Time formatting --------------------------------------------------------

private val CHAT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
private val LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** "HH:mm:ss" for chat bubbles; [zone] is injectable so tests are deterministic. */
fun formatChatTime(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(timeMs).atZone(zone).format(CHAT_TIME_FORMATTER)

/** "HH:mm:ss.SSS" for the event log; milliseconds keep on-device diagnostics precise. */
fun formatLogTime(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(timeMs).atZone(zone).format(LOG_TIME_FORMATTER)

// --- Relay count ------------------------------------------------------------

/**
 * Caption for a received message: how many hops it took to arrive.
 * relays = initialTtl - ttl, clamped at zero so a ttl above the initial value
 * (should not happen) still reads as "direct" instead of negative relays.
 */
fun relayCountText(ttl: Int, initialTtl: Int = Router.DEFAULT_TTL): String {
    val relays = (initialTtl - ttl).coerceAtLeast(0)
    return if (relays == 0) "direct" else "$relays ${if (relays == 1) "relay" else "relays"}"
}

// --- Payload limit ------------------------------------------------------------

/** How close (in chars) to the frame payload cap before the counter appears. */
private const val PAYLOAD_LIMIT_WARNING_MARGIN = 128

/** True when a draft of [textLength] chars is within the warning margin of the cap. */
fun isNearPayloadLimit(textLength: Int, maxPayload: Int = FrameCodec.MAX_PAYLOAD): Boolean =
    textLength > maxPayload - PAYLOAD_LIMIT_WARNING_MARGIN

/** Counter line shown under the compose field while a draft nears the frame payload limit. */
fun payloadLimitText(textLength: Int, maxPayload: Int = FrameCodec.MAX_PAYLOAD): String =
    "$textLength / $maxPayload — near the frame payload limit"

// --- Role / Wi-Fi Direct labels ----------------------------------------------

/** Human-readable role for the status card and chip. */
fun roleLabel(role: P2pRole): String = when (role) {
    P2pRole.NONE -> "No group"
    P2pRole.GROUP_OWNER -> "Group owner"
    P2pRole.CLIENT -> "Client"
}

/** Wi-Fi Direct radio state; null means the first report has not arrived yet. */
fun wifiDirectStateLabel(p2pEnabled: Boolean?): String = when (p2pEnabled) {
    true -> "enabled"
    false -> "disabled"
    null -> "unknown"
}

// --- Log classification -------------------------------------------------------

/** Filter categories for the log tab; one line may belong to several of them. */
enum class LogCategory { MESSAGES, NETWORK, TCP, ERRORS }

/**
 * Categories a log line belongs to, by keyword:
 * - MESSAGES: TX / RX / originated / delivered / replay lines (chat traffic)
 * - NETWORK:  the "P2P:" prefix used by every Wi-Fi Direct connector line
 * - TCP:      connect/listen attempts and outcomes
 * - ERRORS:   anything containing failed / error / Exception / dropped
 */
fun classifyLogLine(line: String): Set<LogCategory> {
    val lower = line.lowercase()
    return buildSet {
        if (lower.contains("tx ") || lower.contains("rx ") ||
            lower.contains("originated") || lower.contains("delivered") ||
            lower.contains("replay")
        ) {
            add(LogCategory.MESSAGES)
        }
        if (lower.contains("p2p")) add(LogCategory.NETWORK)
        if (lower.contains("tcp")) add(LogCategory.TCP)
        if (lower.contains("failed") || lower.contains("error") ||
            lower.contains("exception") || lower.contains("dropped")
        ) {
            add(LogCategory.ERRORS)
        }
    }
}

/** True when [line] is visible under the selected filter; null shows everything. */
fun matchesFilter(line: String, category: LogCategory?): Boolean =
    category == null || category in classifyLogLine(line)

// --- Status chip --------------------------------------------------------------

/** Dot state for the top-bar status chip; the text carries the meaning too. */
enum class MeshStatus { NO_GROUP, GROUP_NO_NEIGHBORS, CONNECTED }

fun meshStatus(role: P2pRole, neighborCount: Int): MeshStatus = when {
    role == P2pRole.NONE -> MeshStatus.NO_GROUP
    neighborCount <= 0 -> MeshStatus.GROUP_NO_NEIGHBORS
    else -> MeshStatus.CONNECTED
}

/** Chip text such as "Owner · 2 neighbors"; words, not just color, convey state. */
fun statusChipText(role: P2pRole, neighborCount: Int): String = when (role) {
    P2pRole.NONE -> "No group"
    P2pRole.GROUP_OWNER -> chipWithNeighbors("Owner", neighborCount)
    P2pRole.CLIENT -> chipWithNeighbors("Client", neighborCount)
}

private fun chipWithNeighbors(roleLabel: String, neighborCount: Int): String =
    if (neighborCount <= 0) {
        "$roleLabel · no neighbors"
    } else {
        val plural = if (neighborCount == 1) "neighbor" else "neighbors"
        "$roleLabel · $neighborCount $plural"
    }

// --- Theme mode ---------------------------------------------------------------

/** In-app theme setting; SYSTEM follows the device's dark-mode toggle. */
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Pure decision used by the activity and the previews alike. */
fun resolveDarkTheme(mode: ThemeMode, systemIsDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemIsDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Restores a stored value; missing or unknown values fall back to SYSTEM. */
fun parseThemeMode(storedValue: String?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == storedValue } ?: ThemeMode.SYSTEM

// --- Location sharing ---------------------------------------------------------

/** Progress of a "share my location" request, shown above the compose bar. */
enum class LocationShareStatus { IDLE, WAITING_FOR_FIX, FAILED }

/**
 * Coordinates with 5 decimals (~1 m resolution). Micro-degrees carry a sixth
 * digit that is below GPS accuracy, so it is not displayed. Locale.US keeps
 * the decimal point stable — copied coordinates must parse on any device.
 */
fun formatCoordinates(latitudeE6: Int, longitudeE6: Int): String =
    "%.5f, %.5f".format(Locale.US, latitudeE6 / 1e6, longitudeE6 / 1e6)

/** Human distance: whole meters below a kilometer (rounded), kilometers above. */
fun formatDistanceMeters(meters: Double): String {
    val rounded = Math.round(meters)
    // Round first so 999.6 m reads "1.0 km", never the awkward "1000 m".
    return if (rounded < 1_000) "$rounded m" else "%.1f km".format(Locale.US, meters / 1000.0)
}
