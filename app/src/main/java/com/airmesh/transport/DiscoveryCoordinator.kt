package com.airmesh.transport

/**
 * The raw scan operations the coordinator serializes. In production these delegate
 * to WifiP2pPeerDirectory (DNS-SD) and WifiDirectConnector (classic discovery);
 * tests use a fake that records call order.
 */
interface DiscoveryBackend {
    /** Remove all DNS-SD service requests on our channel. */
    fun clearServiceRequests()

    /** Register the AirMesh service request so discoverServices has something to scan for. */
    fun addServiceRequest()

    /** (Re)start a DNS-SD service scan; Android stops it after about two minutes. */
    fun discoverServices()

    /** Classic Wi-Fi Direct peer scan (discoverPeers); cancels any ongoing DNS-SD scan. */
    fun discoverPeers()

    /**
     * Chained variants used by the refresh cycle: report (success, reason) when
     * the framework call actually completes. The default bodies keep the old
     * fire-and-forget behaviour for fake backends, reporting immediate success.
     */
    fun clearServiceRequests(onDone: (Boolean, Int) -> Unit) {
        clearServiceRequests()
        onDone(true, 0)
    }

    fun addServiceRequest(onDone: (Boolean, Int) -> Unit) {
        addServiceRequest()
        onDone(true, 0)
    }

    fun discoverServices(onDone: (Boolean, Int) -> Unit) {
        discoverServices()
        onDone(true, 0)
    }

    /** Chained classic discoverPeers; strategies 3 and 4 wait for its callback. */
    fun discoverPeers(onDone: (Boolean, Int) -> Unit) {
        discoverPeers()
        onDone(true, 0)
    }

    /**
     * Chained addServiceRequest. [filtered] false registers the request without a
     * service type (strategy 2); the default ignores the flag and keeps the old
     * behaviour for fakes.
     */
    fun addServiceRequest(filtered: Boolean, onDone: (Boolean, Int) -> Unit) {
        addServiceRequest(onDone)
    }

    /** Strategy 4: removeLocalService then addLocalService right before the scan. */
    fun reRegisterLocalService(onDone: (Boolean, Int) -> Unit) {
        onDone(true, 0)
    }
}

/**
 * Serializes every P2P scan on the radio: discoverPeers cancels an ongoing DNS-SD
 * service scan (and vice versa), so the two must never overlap. The auto-mesh
 * 60 s refresh cycle runs here; a manual Discover tap takes over the radio for
 * [PEER_SCAN_WINDOW_MS], after which the service cycle resumes if still wanted.
 * Pure Kotlin — no Android imports, injectable clock, unit-testable on the JVM.
 */
class DiscoveryCoordinator(
    private val backend: DiscoveryBackend,
    /** Runs [block] after the given delay. Default executes immediately (JVM tests). */
    private val schedule: (Long, () -> Unit) -> Unit = { _, block -> block() },
    private val log: (String) -> Unit = {},
    /** Picks the scan variant and remembers the one that worked. */
    private val strategySelector: DiscoveryStrategySelector =
        DiscoveryStrategySelector(NoDiscoveryStrategyStore),
    // Clock stays last: existing callers use `DiscoveryCoordinator(backend) { now }`.
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Strict clear → add → (500 ms) → discover chaining, driven by callbacks. */
    private val sequencer = DnsSdRefreshSequencer(clock)

    /** Strategy the running cycle was built for (1 filtered, 2..4 fallbacks). */
    private var activeStrategy: Int = DiscoveryStrategySelector.MIN_STRATEGY

    /** Current strategy, for logs and the 60 s stats line. */
    val currentStrategy: Int get() = strategySelector.strategy

    /** Raw DNS-SD callbacks seen since this coordinator was created (cumulative). */
    var rawCallbackCount: Int = 0
        private set

    /** Last time a full service cycle (clear → add → scan) was issued; null when none is active. */
    var lastCycleAtMs: Long? = null
        private set

    /** True while a DNS-SD service scan holds the radio. */
    var serviceDiscoveryActive: Boolean = false
        private set

    /** When the manual peer scan started; null when it is not running. */
    var peerScanStartedAtMs: Long? = null
        private set

    val peerScanActive: Boolean get() = peerScanStartedAtMs != null

    /**
     * Issue one full service cycle in strict order: clear, add request, then the
     * scan 500 ms after the add succeeded. Returns false when a cycle is already
     * running (its retries own the radio until it finishes or is reset).
     */
    fun issueServiceCycle(): Boolean {
        val strategy = strategySelector.strategy
        val action = sequencer.requestRefresh(strategy)
        if (action == DnsSdRefreshAction.Ignored) {
            log("dnssd: refresh already in progress — request ignored")
            return false
        }
        activeStrategy = strategy
        log("dnssd: strategy=$strategy")
        lastCycleAtMs = clock()
        serviceDiscoveryActive = true
        execute(action)
        return true
    }

    /** Run a sequencer action, scheduling it when it carries a delay. */
    private fun execute(action: DnsSdRefreshAction) {
        when (action) {
            is DnsSdRefreshAction.RunStep -> {
                val step = action.step
                if (action.delayMs <= 0L) {
                    runStep(step)
                } else {
                    schedule(action.delayMs) {
                        // A reset (stop, manual scan) may have abandoned the cycle meanwhile.
                        if (sequencer.isRunningStep(step)) runStep(step) else
                            log("dnssd: stale wait for ${step.name.lowercase()} dropped")
                    }
                }
            }

            DnsSdRefreshAction.CycleComplete -> log("dnssd: service cycle complete")
            DnsSdRefreshAction.Ignored -> Unit
        }
    }

    /** Issue one framework call; its completion callback advances the chain. */
    private fun runStep(step: DnsSdRefreshStep) {
        val onDone: (Boolean, Int) -> Unit = { success, reason -> onStepDone(step, success, reason) }
        when (step) {
            DnsSdRefreshStep.DISCOVER_PEERS -> backend.discoverPeers(onDone)
            DnsSdRefreshStep.CLEAR_REQUESTS -> backend.clearServiceRequests(onDone)
            // Strategy 1 asks the framework to filter by service type; from 2 on we
            // register the request unfiltered and filter in our own listener.
            DnsSdRefreshStep.ADD_REQUEST ->
                backend.addServiceRequest(filtered = activeStrategy == 1, onDone)

            DnsSdRefreshStep.RE_REGISTER_LOCAL_SERVICE -> backend.reRegisterLocalService(onDone)
            DnsSdRefreshStep.DISCOVER_SERVICES -> {
                // A scan is starting: open the 25 s silence watch for this strategy.
                strategySelector.scanStarted(clock())
                backend.discoverServices(onDone)
            }
        }
    }

    /** Feed a RAW DNS-SD callback (before any filtering) into the strategy selector. */
    fun notifyRawCallback(nowMs: Long = clock()) {
        rawCallbackCount++
        strategySelector.rawCallback(nowMs)
    }

    private fun onStepDone(step: DnsSdRefreshStep, success: Boolean, reason: Int) {
        if (success) {
            execute(sequencer.onStepSuccess(step))
        } else {
            log("dnssd: ${step.name.lowercase()} failed reason=$reason")
            execute(sequencer.onStepFailure(step, reason))
        }
    }

    /** Re-issue the cycle when [DISCOVERY_REFRESH_MS] has passed since the last one. */
    fun refreshIfDue(nowMs: Long): Boolean {
        val last = lastCycleAtMs ?: return false
        if (!serviceDiscoveryActive || !shouldRefreshDiscovery(last, nowMs)) return false
        return issueServiceCycle()
    }

    /**
     * Manual Discover tap: take over the radio. Clear any active service scan first
     * (discoverPeers would cancel it anyway), then start a classic peer scan that
     * holds the radio for [PEER_SCAN_WINDOW_MS].
     */
    fun requestPeerScan() {
        if (serviceDiscoveryActive) backend.clearServiceRequests()
        sequencer.reset() // abandon any in-flight chained cycle and its retries
        serviceDiscoveryActive = false
        lastCycleAtMs = null // after the window, resume with a fresh full cycle
        peerScanStartedAtMs = clock()
        backend.discoverPeers()
    }

    /**
     * Called every loop slice: end an expired manual scan and, if auto-mesh still
     * wants service discovery, resume the cycle (immediately when none is active).
     */
    fun tick(nowMs: Long, serviceWanted: Boolean) {
        val started = peerScanStartedAtMs
        if (started != null && nowMs - started >= PEER_SCAN_WINDOW_MS) {
            peerScanStartedAtMs = null
        }
        if (serviceWanted && !peerScanActive && !serviceDiscoveryActive) issueServiceCycle()
        // A strategy whose scan stayed silent for 25 s rotates to the next fallback.
        if (serviceWanted && !peerScanActive) rotateIfSilent(nowMs)
    }

    /** Rotate the discovery strategy when the current scan window passed with no raw callback. */
    private fun rotateIfSilent(nowMs: Long) {
        val event = strategySelector.tick(nowMs)
        if (event !is DiscoveryStrategyEvent.Rotate) return
        log("dnssd: strategy ${event.from} silent for ${DiscoveryStrategySelector.WATCH_MS / 1000} s — rotating to ${event.to}")
        // Abandon whatever the old strategy was doing and start the new plan now.
        sequencer.reset()
        lastCycleAtMs = null
        serviceDiscoveryActive = true // let the fresh cycle below own the radio
        issueServiceCycle()
    }

    /** Stop the service scan entirely (auto-mesh paused, or the directory stopped). */
    fun stopService() {
        sequencer.reset()
        backend.clearServiceRequests()
        serviceDiscoveryActive = false
        lastCycleAtMs = null
        peerScanStartedAtMs = null
    }

    companion object {
        /** How long a manual peer scan holds the radio before the service cycle resumes. */
        const val PEER_SCAN_WINDOW_MS = 30_000L
    }
}
