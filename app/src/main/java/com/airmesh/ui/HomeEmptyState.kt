package com.airmesh.ui

import com.airmesh.transport.AutoMeshState
import com.airmesh.transport.autoMeshBannerText

/**
 * What the chats home shows when there are no conversations yet (TASK U4 part 1).
 *
 * The old mapping sent "Searching + a classic Wi-Fi Direct device was seen" to a static
 * NO_AIRMESH_NEARBY state that repeated the banner's sentence in large text. Now, while
 * discovery is active the home ALWAYS shows the animated radar with one fixed headline;
 * non-AirMesh devices only change the small caption below it (they are ignored by design —
 * a TV or router is not a bug). After [HOME_MANUAL_BUTTON_AFTER_MS] of searching without an
 * AirMesh peer, a "Manual connect" button appears as the escape hatch.
 */
data class HomeEmptyState(
    val kind: EmptyStateKind,
    val headline: String,
    val caption: String?,
    val showManualButton: Boolean,
)

/** How long we search before offering the manual-connect escape hatch. */
const val HOME_MANUAL_BUTTON_AFTER_MS = 90_000L

/** Fixed headline while discovery is active — never repeats the banner text. */
const val HOME_SEARCHING_HEADLINE = "Looking for AirMesh phones nearby…"

/** Caption while classic (non-AirMesh) Wi-Fi Direct devices are visible but ignored. */
const val HOME_OTHER_DEVICES_CAPTION =
    "Other Wi-Fi Direct devices nearby are ignored. Make sure AirMesh is open on the other phone."

/** Caption after a long search with no AirMesh peer; also turns the manual button on. */
const val HOME_LONG_WAIT_CAPTION =
    "Still nobody in range. Move closer and make sure AirMesh is open on the other phone."

/**
 * Pure mapping from controller state to what the chats home empty state shows (TASK U4 part 1).
 *
 * [otherDevicesFound] counts classic Wi-Fi Direct devices (no AirMesh tag); it only affects the
 * caption while searching. [searchingForMs] is how long we have been in Searching, tracked by
 * [SearchTimer]. Non-searching states keep their existing kind and text via
 * [emptyStateKind]/[emptyStateText]; NO_AIRMESH_NEARBY becomes unreachable from here on purpose.
 * The CONNECTING headline's target id comes from the state itself (Joining/Yielding carry it).
 */
fun homeEmptyState(
    status: AutoMeshState,
    otherDevicesFound: Int,
    searchingForMs: Long,
    permissionOk: Boolean,
): HomeEmptyState {
    if (!permissionOk) {
        // Missing permissions outrank everything else — the button offers to grant them.
        return HomeEmptyState(
            kind = EmptyStateKind.PERMISSION_MISSING,
            headline = emptyStateText(EmptyStateKind.PERMISSION_MISSING, null),
            caption = null,
            showManualButton = false,
        )
    }
    return when (status) {
        is AutoMeshState.Searching -> {
            // Animated radar even when classic devices are found; they only change the caption.
            val longWait = searchingForMs >= HOME_MANUAL_BUTTON_AFTER_MS
            HomeEmptyState(
                kind = EmptyStateKind.SEARCHING,
                headline = HOME_SEARCHING_HEADLINE,
                caption = when {
                    longWait -> HOME_LONG_WAIT_CAPTION
                    otherDevicesFound > 0 -> HOME_OTHER_DEVICES_CAPTION
                    else -> null
                },
                showManualButton = longWait,
            )
        }
        // Joining/Yielding carry the target; Creating does not yet have one.
        is AutoMeshState.Creating -> HomeEmptyState(EmptyStateKind.CONNECTING, connectingHeadline(null), null, false)
        is AutoMeshState.Joining -> HomeEmptyState(EmptyStateKind.CONNECTING, connectingHeadline(status.targetNodeId), null, false)
        is AutoMeshState.Yielding -> HomeEmptyState(EmptyStateKind.CONNECTING, connectingHeadline(status.targetNodeId), null, false)
        is AutoMeshState.Idle,
        is AutoMeshState.Backoff,
        -> HomeEmptyState(
            kind = EmptyStateKind.IDLE_BACKOFF,
            headline = emptyStateText(EmptyStateKind.IDLE_BACKOFF, null),
            caption = null,
            showManualButton = false,
        )
        // Owner/Client with an empty list: connected, nobody has introduced themselves yet.
        else -> HomeEmptyState(
            kind = EmptyStateKind.CONNECTED_NO_CONTACTS,
            headline = emptyStateText(EmptyStateKind.CONNECTED_NO_CONTACTS, null),
            caption = null,
            showManualButton = false,
        )
    }
}

/**
 * The single banner the chats home may show (TASK U4 part 2): only actionable problems —
 * missing permissions, a merge in progress, or a retry after an error. Everything else
 * (searching, connecting, connected, idle) returns null: those states are already explained by
 * the empty state's headline, and repeating them in a banner was exactly the clutter this task
 * removes. The old "found N devices but none advertises AirMesh" hint is gone from home too —
 * its content now lives in the empty-state caption and the manual-connect button.
 *
 * [emptyStateVisible] mirrors ChatsHomeContent's condition (no conversation or people rows):
 * when the empty state is on screen it owns the permission message (headline + Grant button), so
 * a banner would repeat it; with conversations present, the banner is the only signal left.
 * The technical discovery line never appears here — it belongs to the Network tab's Debug card.
 */
fun homeBannerText(
    status: AutoMeshState,
    permissionOk: Boolean,
    neighborCount: Int,
    idleReason: String?,
    nowMs: Long,
    emptyStateVisible: Boolean,
): String? = when {
    !permissionOk && !emptyStateVisible ->
        // Same wording as the empty-state headline; the two are never on screen at once.
        emptyStateText(EmptyStateKind.PERMISSION_MISSING, null)
    // Merge in progress and retry-after-error: the only other actionable problems.
    status is AutoMeshState.Yielding || status is AutoMeshState.Backoff ->
        autoMeshBannerText(status, neighborCount, idleReason, nowMs)
    else -> null
}

/** CONNECTING headline for an optional join target id. */
private fun connectingHeadline(targetNodeId: Int?): String =
    emptyStateText(EmptyStateKind.CONNECTING, targetNodeId)

/**
 * Tracks how long the controller has been Searching (TASK U4 part 1). The clock is injected so
 * tests can fake time; production uses [System.currentTimeMillis]. Entering Searching starts the
 * count at zero; leaving it resets, and a later re-entry counts from that moment.
 */
class SearchTimer(private val clock: () -> Long = System::currentTimeMillis) {

    private var enteredAtMs: Long? = null

    /** Feed the current state; returns milliseconds spent in Searching (0 when not searching). */
    fun update(status: AutoMeshState): Long {
        if (status is AutoMeshState.Searching) {
            val start = enteredAtMs ?: clock().also { enteredAtMs = it }
            return clock() - start
        }
        enteredAtMs = null
        return 0L
    }
}
