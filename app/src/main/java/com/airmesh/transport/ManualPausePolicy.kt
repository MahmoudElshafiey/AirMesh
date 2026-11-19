package com.airmesh.transport

/** A manual Wi-Fi Direct action that temporarily pauses auto-connect (TASK S1 part 2). */
enum class ManualPauseAction(val pauseDurationMs: Long) {
    DISCOVER(MANUAL_PAUSE_MS),
    CREATE_GROUP(MANUAL_PAUSE_MS),
    CONNECT(MANUAL_PAUSE_MS),
    RETRY_TCP(MANUAL_PAUSE_MS),
    DISCONNECT(MANUAL_DISCONNECT_PAUSE_MS),
}

/** Discover / Create group / Connect / Retry TCP pause auto-connect for this long. */
const val MANUAL_PAUSE_MS = 45_000L

/** The Advanced card's Disconnect pauses longer: the radio was deliberately taken down. */
const val MANUAL_DISCONNECT_PAUSE_MS = 120_000L

/** After an in-flight manual connect settles (group formed or failed), auto resumes this long later. */
const val MANUAL_CONNECT_SETTLE_DELAY_MS = 10_000L

/** One pause episode never exceeds this total, even with repeated actions or a stuck connect. */
const val MANUAL_PAUSE_CAP_MS = 180_000L

/**
 * Temporary auto-connect pauses caused by manual actions (TASK S1 part 2). Pure Kotlin: every
 * method takes an explicit timestamp so tests can fake the clock; no Android imports.
 *
 * Rules:
 * - A manual action ([ManualPauseAction]) pauses for its duration (45 s; Disconnect is 120 s).
 *   Repeating actions extends the deadline, but never past [MANUAL_PAUSE_CAP_MS] from the
 *   episode start — a fresh episode restarts the cap window.
 * - While a manual connect is in flight (requested until its group forms or fails) the pause is
 *   extended to "now", still capped at 3 minutes total.
 * - When that connect settles, auto resumes [MANUAL_CONNECT_SETTLE_DELAY_MS] later; settling
 *   overrides any earlier deadline of the same episode.
 * - The Auto switch stays authoritative: [resumeByUser] clears everything immediately.
 */
class ManualPausePolicy {

    /** Deadline of the current pause; 0 = none (an in-flight connect may still hold one). */
    var pauseUntilMs: Long = 0L
        private set

    /** When the current episode started; anchors the [MANUAL_PAUSE_CAP_MS] cap. Null = no episode. */
    var originMs: Long? = null
        private set

    /** A manual connect was requested and has not formed a group or failed yet. */
    var connectInFlight: Boolean = false
        private set

    /** Why the current pause will end; consumed by [takeResumeReason] when it does. */
    private var resumeReason = "timeout"

    fun isPaused(nowMs: Long): Boolean {
        if (connectInFlight) {
            val origin = originMs ?: return nowMs < pauseUntilMs
            // An in-flight manual connect holds the pause until it settles or the cap.
            return nowMs < origin + MANUAL_PAUSE_CAP_MS || nowMs < pauseUntilMs
        }
        return nowMs < pauseUntilMs
    }

    /** Milliseconds left in the current pause (0 when not paused); feeds the banner countdown. */
    fun pauseRemainingMs(nowMs: Long): Long {
        if (!isPaused(nowMs)) return 0L
        val until = effectivePauseUntilMs(nowMs)
        // While a connect is in flight the deadline tracks "now"; keep at least 1 s so the
        // banner stays visible instead of flickering.
        return (until - nowMs).coerceAtLeast(1L)
    }

    private fun effectivePauseUntilMs(nowMs: Long): Long {
        if (!connectInFlight) return pauseUntilMs
        val origin = originMs ?: return pauseUntilMs
        // In flight: hold at least until "now" (up to the cap), so [isPaused] stays true.
        return minOf(origin + MANUAL_PAUSE_CAP_MS, maxOf(pauseUntilMs, nowMs))
    }

    /**
     * Registers a manual action; returns true when this call started or extended the stored
     * deadline (the caller logs one line per true). A fresh episode — and with it the cap window
     * — starts whenever no pause is active at [nowMs].
     */
    fun noteManualAction(action: ManualPauseAction, nowMs: Long): Boolean {
        if (!isPaused(nowMs)) originMs = nowMs
        val capEnd = (originMs ?: nowMs) + MANUAL_PAUSE_CAP_MS
        val newUntil = minOf(capEnd, nowMs + action.pauseDurationMs)
        return if (newUntil > pauseUntilMs) {
            pauseUntilMs = newUntil
            resumeReason = "timeout" // the clock decides again from here on
            true
        } else false
    }

    /** A manual connect was issued; while it is in flight the pause extends to "now". */
    fun noteConnectRequested(nowMs: Long) {
        if (originMs == null || !isPaused(nowMs)) originMs = nowMs // defensive: CONNECT follows an action
        connectInFlight = true
    }

    /**
     * The in-flight manual connect settled: [connected] is true when its group formed. Auto then
     * resumes [MANUAL_CONNECT_SETTLE_DELAY_MS] later (capped). No-op — returns false — when
     * nothing was in flight, so auto-formed groups never touch the pause.
     */
    fun noteConnectSettled(nowMs: Long, connected: Boolean): Boolean {
        if (!connectInFlight) return false
        connectInFlight = false
        val capEnd = (originMs ?: nowMs) + MANUAL_PAUSE_CAP_MS
        pauseUntilMs = minOf(capEnd, nowMs + MANUAL_CONNECT_SETTLE_DELAY_MS)
        resumeReason = if (connected) "connected" else "failed"
        return true
    }

    /** The user resumed manually (Resume button or the Auto switch back on); clears everything. */
    fun resumeByUser(nowMs: Long): Boolean {
        val wasActive = isPaused(nowMs) || connectInFlight
        pauseUntilMs = 0L
        originMs = null
        connectInFlight = false
        resumeReason = "timeout"
        return wasActive
    }

    /** The reason the current pause ended ("timeout" | "connected" | "failed"); resets to timeout. */
    fun takeResumeReason(): String {
        val r = resumeReason
        resumeReason = "timeout"
        return r
    }
}
