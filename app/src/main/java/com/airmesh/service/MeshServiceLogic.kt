package com.airmesh.service

/**
 * Pure decision logic for [MeshService], kept free of Android imports so it is
 * unit-testable on the JVM. The service adapts platform types (Context, Intent,
 * NotificationManager) to these inputs; every "what should we do" rule lives here
 * and is covered by tests in `MeshServiceLogicTest`.
 */

// --- Running notification text ---------------------------------------------

/**
 * Text for the ongoing foreground notification: how many neighbors are live.
 * Words, not just a number, so 0 / 1 / many each read correctly on screen.
 */
fun runningNotificationText(neighborCount: Int): String = when (neighborCount) {
    0 -> "AirMesh is running · no neighbors"
    1 -> "AirMesh is running · 1 neighbor"
    else -> "AirMesh is running · $neighborCount neighbors"
}

// --- Update throttling ------------------------------------------------------

/**
 * Leading-edge throttle for the ongoing notification: at most one update per
 * [minIntervalMs]. The first call always passes; later calls pass only once the
 * interval has elapsed since the last post. A fake clock makes this testable on
 * the JVM (the service injects `System::currentTimeMillis`).
 */
class NotificationThrottle(private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS) {

    init { require(minIntervalMs >= 0) { "minIntervalMs must be >= 0: $minIntervalMs" } }

    private var lastPostedAtMs: Long? = null

    /** True when an update may be posted at [nowMs]; records the post. */
    fun shouldPost(nowMs: Long): Boolean {
        val last = lastPostedAtMs
        if (last == null || nowMs - last >= minIntervalMs) {
            lastPostedAtMs = nowMs
            return true
        }
        return false
    }

    /** Clears the schedule so the next update posts immediately. */
    fun reset() { lastPostedAtMs = null }

    companion object {
        /** At most one ongoing-notification refresh every 2 s. */
        const val DEFAULT_MIN_INTERVAL_MS = 2_000L
    }
}

// --- Per-message notification decision -------------------------------------

/** Kind of incoming content a per-message notification can describe. */
enum class IncomingKind { TEXT, LOCATION }

/** Title + body for one received-content notification (sender id + short text). */
data class MessageNotificationContent(val title: String, val body: String)

/** Max characters of message text shown in a notification; longer is elided. */
const val NOTIFICATION_TEXT_MAX_CHARS = 100

/**
 * Decides whether to post a per-message notification for newly received content:
 * only when no Activity is visible (the user already sees it on screen otherwise)
 * and the user has not turned message notifications off in the Network tab.
 */
fun shouldPostMessageNotification(activityVisible: Boolean, notificationsEnabled: Boolean): Boolean =
    !activityVisible && notificationsEnabled

/**
 * Builds the title/body for one received-content notification. Text messages show
 * a short excerpt; locations are described without coordinates (brevity + privacy).
 */
fun messageNotificationContent(kind: IncomingKind, senderId: Int, text: String): MessageNotificationContent {
    val title = "AirMesh · Node #$senderId"
    return when (kind) {
        IncomingKind.TEXT -> MessageNotificationContent(title, shortenForNotification(text))
        IncomingKind.LOCATION -> MessageNotificationContent(title, "shared their location")
    }
}

/** Trims [text] to at most [maxChars] with an ellipsis so a notification stays short. */
fun shortenForNotification(text: String, maxChars: Int = NOTIFICATION_TEXT_MAX_CHARS): String {
    val trimmed = text.trim()
    if (maxChars <= 0) return ""
    return if (trimmed.length <= maxChars) trimmed else trimmed.take(maxChars - 1) + "…"
}

// --- Service phase reducer --------------------------------------------------

/** Coarse lifecycle of the mesh service. */
enum class MeshServicePhase { NOT_STARTED, RUNNING }

/** Events that move the service between phases. */
sealed interface ServiceEvent {
    /** Permissions granted / (re)start requested from a foreground Activity. */
    data object Start : ServiceEvent
    /** User disconnect, notification Stop action, or service destruction. */
    data object Stop : ServiceEvent
}

/**
 * Reduces the service phase on an event. [ServiceEvent.Start] is idempotent:
 * starting an already-running service stays RUNNING (handles being started twice),
 * so a double start never creates a second node. [ServiceEvent.Stop] always returns
 * to NOT_STARTED, regardless of whether a connect was in flight — the connector's
 * own cancellation handles tearing down a mid-flight connection.
 */
fun reduceMeshServicePhase(current: MeshServicePhase, event: ServiceEvent): MeshServicePhase = when (event) {
    is ServiceEvent.Start -> if (current == MeshServicePhase.NOT_STARTED) MeshServicePhase.RUNNING else current
    is ServiceEvent.Stop -> MeshServicePhase.NOT_STARTED
}

// --- Group removal policy ---------------------------------------------------

/** Why the mesh is being stopped; drives whether the P2P group is removed. */
enum class StopReason { USER_DISCONNECT, NOTIFICATION_STOP, SERVICE_DESTROYED, ACTIVITY_STOPPED, SCREEN_OFF }

/**
 * Whether stopping for [reason] removes the Wi-Fi Direct group. Only an explicit
 * user disconnect, the notification Stop action, and service destruction remove it;
 * activity stop and screen-off never do — in this design they don't stop the mesh at
 * all (the foreground service keeps running), so those reasons exist to make that
 * rule explicit and unit-testable.
 */
fun shouldRemoveGroupOnStop(reason: StopReason): Boolean = when (reason) {
    StopReason.USER_DISCONNECT, StopReason.NOTIFICATION_STOP, StopReason.SERVICE_DESTROYED -> true
    StopReason.ACTIVITY_STOPPED, StopReason.SCREEN_OFF -> false
}
