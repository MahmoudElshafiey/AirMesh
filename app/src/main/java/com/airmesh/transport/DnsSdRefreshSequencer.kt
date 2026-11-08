package com.airmesh.transport

/** The three DNS-SD calls of one refresh cycle, in order. */
enum class DnsSdRefreshStep {
    CLEAR_REQUESTS,
    ADD_REQUEST,
    DISCOVER_SERVICES,
}

/** What the caller should do after feeding a callback into [DnsSdRefreshSequencer]. */
sealed interface DnsSdRefreshAction {
    /** Run [step] now (delayMs == 0) or after [delayMs]. */
    data class RunStep(val step: DnsSdRefreshStep, val delayMs: Long) : DnsSdRefreshAction

    /** The whole clear → add → discover chain finished. */
    data object CycleComplete : DnsSdRefreshAction

    /** Stale or duplicate callback, or a refresh requested while one is running. */
    data object Ignored : DnsSdRefreshAction
}

/**
 * Strict chaining of the DNS-SD refresh cycle: clearServiceRequests, then
 * addServiceRequest from its success callback, then discoverServices 500 ms
 * after the add succeeds (issuing it back to back was the bug: discoverServices
 * ran before the earlier calls had reported success).
 *
 * A failed step retries itself with exponential backoff (2 s, 4 s, 8 s, 16 s,
 * 30 s, then 30 s); any successful step resets the backoff. Pure Kotlin: the
 * caller owns timers, the injected [clock] only timestamps activity, so tests
 * drive everything through callbacks and a fake clock.
 */
class DnsSdRefreshSequencer(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** True while a cycle is in flight (including backoff waits). */
    var running: Boolean = false
        private set

    /** The step whose callback we are waiting for; null when idle. */
    var currentStep: DnsSdRefreshStep? = null
        private set

    /** Consecutive failures of the current step; drives the backoff. */
    var consecutiveFailures: Int = 0
        private set

    /** When the last action was issued or last callback consumed (fake clock in tests). */
    var lastActivityMs: Long = 0L
        private set

    /**
     * Start a cycle. Ignored while one is already running: a second refresh must
     * never interleave clear/add/discover calls on the radio.
     */
    fun requestRefresh(): DnsSdRefreshAction {
        if (running) return DnsSdRefreshAction.Ignored
        running = true
        consecutiveFailures = 0
        currentStep = DnsSdRefreshStep.CLEAR_REQUESTS
        lastActivityMs = clock()
        return DnsSdRefreshAction.RunStep(DnsSdRefreshStep.CLEAR_REQUESTS, 0L)
    }

    /** A step reported success: move to the next step. */
    fun onStepSuccess(step: DnsSdRefreshStep): DnsSdRefreshAction {
        if (!running || step != currentStep) return DnsSdRefreshAction.Ignored
        consecutiveFailures = 0
        lastActivityMs = clock()
        val action = when (step) {
            DnsSdRefreshStep.CLEAR_REQUESTS ->
                DnsSdRefreshAction.RunStep(DnsSdRefreshStep.ADD_REQUEST, 0L)

            // 500 ms so the framework settles the request before the scan starts.
            DnsSdRefreshStep.ADD_REQUEST ->
                DnsSdRefreshAction.RunStep(DnsSdRefreshStep.DISCOVER_SERVICES, ADD_TO_DISCOVER_DELAY_MS)

            DnsSdRefreshStep.DISCOVER_SERVICES -> {
                running = false
                currentStep = null
                DnsSdRefreshAction.CycleComplete
            }
        }
        if (action is DnsSdRefreshAction.RunStep) currentStep = action.step
        return action
    }

    /** A step failed: retry the same step after the next backoff delay. */
    fun onStepFailure(step: DnsSdRefreshStep, reason: Int): DnsSdRefreshAction {
        if (!running || step != currentStep) return DnsSdRefreshAction.Ignored
        consecutiveFailures++
        lastActivityMs = clock()
        return DnsSdRefreshAction.RunStep(step, backoffDelayMs(consecutiveFailures))
    }

    /** Abandon the cycle (service stopped, or a manual peer scan takes the radio). */
    fun reset() {
        running = false
        currentStep = null
        consecutiveFailures = 0
        lastActivityMs = clock()
    }

    /** True when a scheduled step may still run: the cycle was not abandoned meanwhile. */
    fun isRunningStep(step: DnsSdRefreshStep): Boolean = running && currentStep == step

    companion object {
        /** Gap between "add request ok" and discoverServices, per the chaining spec. */
        const val ADD_TO_DISCOVER_DELAY_MS = 500L

        /** First retry delay; doubles per consecutive failure. */
        const val BASE_BACKOFF_MS = 2_000L

        /** Backoff ceiling. */
        const val MAX_BACKOFF_MS = 30_000L

        /** 2 s, 4 s, 8 s, 16 s, 30 s, 30 s … for failures = 1, 2, 3, … */
        fun backoffDelayMs(failures: Int): Long {
            if (failures <= 0) return 0L
            var delay = BASE_BACKOFF_MS
            var i = 1
            while (i < failures && delay < MAX_BACKOFF_MS) {
                delay *= 2
                i++
            }
            return minOf(delay, MAX_BACKOFF_MS)
        }
    }
}
