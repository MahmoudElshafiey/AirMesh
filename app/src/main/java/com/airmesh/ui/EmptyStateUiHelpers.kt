package com.airmesh.ui

import com.airmesh.transport.AutoMeshState

/**
 * What the chats empty state should show (TASK U3 part 2). Only [SEARCHING] and
 * [CONNECTING] animate; every other kind renders a deliberately static illustration so
 * quiet states stay calm (and reduced-motion users never see movement they did not ask for).
 */
enum class EmptyStateKind {
    SEARCHING,
    CONNECTING,
    PERMISSION_MISSING,
    IDLE_BACKOFF,
    NO_AIRMESH_NEARBY,
    CONNECTED_NO_CONTACTS,
}

/** Loop periods of the radar animation in milliseconds. */
const val RADAR_SEARCHING_PERIOD_MS = 2400L
const val RADAR_CONNECTING_PERIOD_MS = 1200L

/** Rings emerge just outside the center node and expand to the full radius. */
private const val RING_MIN_RADIUS_FRACTION = 0.25f

/** Ring stroke fades from 35% of the primary color down to fully transparent. */
const val RADAR_RING_MAX_ALPHA = 0.35f

/** The three ring phases drawn when the system animation scale is off (static frame). */
val REDUCED_MOTION_RING_PHASES = floatArrayOf(0.15f, 0.5f, 0.85f)

/** One ring's drawn state: radius as a fraction of the full radius, and stroke alpha. */
data class RingState(val radiusFraction: Float, val alpha: Float)

/**
 * Pure ring math for one ring at its own [localPhase] in [0..1]: the radius grows
 * monotonically from 0.25 to 1.0 while the alpha falls linearly from 0.35 to exactly 0.
 */
fun ringAt(localPhase: Float): RingState {
    val p = localPhase.coerceIn(0f, 1f)
    return RingState(
        radiusFraction = RING_MIN_RADIUS_FRACTION + (1f - RING_MIN_RADIUS_FRACTION) * p,
        alpha = RADAR_RING_MAX_ALPHA * (1f - p),
    )
}

/**
 * [ring] staggers [count] rings by index/count of the period, so at any moment each ring is a
 * third of a cycle ahead of the previous one. Within one cycle the radius is monotonic; when a
 * ring wraps it is "reborn" small and faint at the center — that is the expanding pulse.
 */
fun ring(phase: Float, index: Int, count: Int): RingState =
    ringAt((phase + index / count.toFloat()) % 1f)

/** Pure phase of the loop for a frame timestamp: (t mod period) / period, always in [0..1). */
fun radarPhase(frameTimeMs: Long, periodMs: Long): Float {
    require(periodMs > 0L) { "periodMs must be positive" }
    return ((frameTimeMs % periodMs).toFloat() / periodMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * System animation scale → reduced motion. A null (unreadable) value must NEVER count as
 * reduced — the default is "animations on". Zero or negative means the user turned system
 * animations off in Settings.
 */
fun isReducedMotion(animatorDurationScale: Float?): Boolean =
    animatorDurationScale != null && animatorDurationScale <= 0f

/**
 * Pure mapping from the auto-mesh controller state to an empty-state kind (TASK U3 part 2).
 * [noAirMeshPeersHintActive] is true exactly when transport's noAirMeshPeersBannerText would
 * show, so the banner stays the single source of truth for that condition.
 */
fun emptyStateKind(
    autoMeshState: AutoMeshState,
    permissionsGranted: Boolean,
    noAirMeshPeersHintActive: Boolean = false,
): EmptyStateKind = when {
    !permissionsGranted -> EmptyStateKind.PERMISSION_MISSING
    // The hint only makes sense while searching; otherwise fall through to the state mapping.
    noAirMeshPeersHintActive && autoMeshState is AutoMeshState.Searching -> EmptyStateKind.NO_AIRMESH_NEARBY
    autoMeshState is AutoMeshState.Searching -> EmptyStateKind.SEARCHING
    autoMeshState is AutoMeshState.Creating ||
        autoMeshState is AutoMeshState.Joining ||
        autoMeshState is AutoMeshState.Yielding -> EmptyStateKind.CONNECTING
    // Idle (paused / waiting for permissions that are actually held) and Backoff both read as
    // "give it a second".
    autoMeshState is AutoMeshState.Idle || autoMeshState is AutoMeshState.Backoff -> EmptyStateKind.IDLE_BACKOFF
    // Owner/Client with an empty list: connected, but nobody has introduced themselves yet.
    else -> EmptyStateKind.CONNECTED_NO_CONTACTS
}

/** Title line for each kind; the UI replaces NO_AIRMESH_NEARBY's fallback with the banner text. */
fun emptyStateText(kind: EmptyStateKind, connectingTargetId: Int?): String = when (kind) {
    EmptyStateKind.SEARCHING -> "Looking for phones nearby…"
    // Joining/Yielding carry a target; Creating does not yet have one.
    EmptyStateKind.CONNECTING ->
        if (connectingTargetId != null) "Connecting to #$connectingTargetId…" else "Starting a group…"
    EmptyStateKind.PERMISSION_MISSING -> "Wi-Fi Direct permissions are missing"
    EmptyStateKind.IDLE_BACKOFF -> "Waiting a moment…"
    EmptyStateKind.NO_AIRMESH_NEARBY -> "No AirMesh phones found yet"
    EmptyStateKind.CONNECTED_NO_CONTACTS -> "Connected. Waiting for others to introduce themselves…"
}
