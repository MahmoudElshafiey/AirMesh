package com.airmesh.transport

/** The DNS-SD / peer-scan calls that can appear in one refresh cycle. */
enum class DnsSdRefreshStep {
    /** Classic discoverPeers; strategy 3 and 4 wait for it before the service scan. */
    DISCOVER_PEERS,
    CLEAR_REQUESTS,
    ADD_REQUEST,
    /** Strategy 4: removeLocalService + addLocalService right before the scan. */
    RE_REGISTER_LOCAL_SERVICE,
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
 *
 * The step plan depends on the discovery strategy (see [DiscoveryStrategySelector]):
 * 1/2 clear → add → discover; 3 adds discoverPeers first; 4 also re-registers
 * the local service right before the scan.
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

    /** Strategy the current (or last) cycle was built for. */
    var strategy: Int = 1
        private set

    /** Remaining steps of the current cycle, each with the delay before it is issued. */
    private var plan: List<Pair<DnsSdRefreshStep, Long>> = emptyList()
    private var planIndex = 0

    /**
     * Start a cycle for [strategy] (1..4, see [DiscoveryStrategySelector]).
     * Ignored while one is already running: a second refresh must never
     * interleave clear/add/scan calls on the radio.
     */
    fun requestRefresh(strategy: Int = 1): DnsSdRefreshAction {
        if (running) return DnsSdRefreshAction.Ignored
        this.strategy = strategy
        plan = planFor(strategy)
        planIndex = 0
        running = true
        consecutiveFailures = 0
        lastActivityMs = clock()
        val first = plan[0]
        currentStep = first.first
        return DnsSdRefreshAction.RunStep(first.first, first.second)
    }

    /** A step reported success: move to the next step of the plan. */
    fun onStepSuccess(step: DnsSdRefreshStep): DnsSdRefreshAction {
        if (!running || step != currentStep) return DnsSdRefreshAction.Ignored
        consecutiveFailures = 0
        lastActivityMs = clock()
        planIndex++
        if (planIndex >= plan.size) {
            running = false
            currentStep = null
            return DnsSdRefreshAction.CycleComplete
        }
        val next = plan[planIndex]
        currentStep = next.first
        return DnsSdRefreshAction.RunStep(next.first, next.second)
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

    /** Step order each strategy runs (the delay travels with the step it precedes). */
    private fun planFor(strategy: Int): List<Pair<DnsSdRefreshStep, Long>> = when (strategy) {
        // 1: filtered request, 2: unfiltered request — only the coordinator's
        // addServiceRequest(filtered=…) differs, the call order is the same.
        1, 2 -> listOf(
            DnsSdRefreshStep.CLEAR_REQUESTS to 0L,
            DnsSdRefreshStep.ADD_REQUEST to 0L,
            DnsSdRefreshStep.DISCOVER_SERVICES to ADD_TO_DISCOVER_DELAY_MS,
        )

        // 3: take the radio with discoverPeers first, scan only after it succeeds.
        3 -> listOf(
            DnsSdRefreshStep.DISCOVER_PEERS to 0L,
            DnsSdRefreshStep.CLEAR_REQUESTS to 0L,
            DnsSdRefreshStep.ADD_REQUEST to 0L,
            DnsSdRefreshStep.DISCOVER_SERVICES to ADD_TO_DISCOVER_DELAY_MS,
        )

        // 4: also re-register our own local service right before the scan.
        else -> listOf(
            DnsSdRefreshStep.DISCOVER_PEERS to 0L,
            DnsSdRefreshStep.CLEAR_REQUESTS to 0L,
            DnsSdRefreshStep.ADD_REQUEST to 0L,
            DnsSdRefreshStep.RE_REGISTER_LOCAL_SERVICE to ADD_TO_DISCOVER_DELAY_MS,
            DnsSdRefreshStep.DISCOVER_SERVICES to 0L,
        )
    }

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
