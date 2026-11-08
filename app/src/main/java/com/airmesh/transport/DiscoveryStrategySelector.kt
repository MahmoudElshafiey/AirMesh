package com.airmesh.transport

/**
 * Persists the last strategy that actually produced a DNS-SD callback, so the
 * next session starts from the one that worked on this device instead of
 * re-learning from strategy 1.
 */
interface DiscoveryStrategyStore {
    /** The last good strategy (1..4), or null when nothing was saved yet. */
    fun load(): Int?

    /** Remember [strategy] as good. */
    fun save(strategy: Int)
}

/** Store used when nothing should be persisted (JVM tests, default wiring). */
object NoDiscoveryStrategyStore : DiscoveryStrategyStore {
    override fun load(): Int? = null

    override fun save(strategy: Int) = Unit
}

/** What [DiscoveryStrategySelector.tick] wants the caller to do. */
sealed interface DiscoveryStrategyEvent {
    /** Start a scan with [to] instead of [from]. */
    data class Rotate(val from: Int, val to: Int) : DiscoveryStrategyEvent

    /** Nothing to do. */
    data object None : DiscoveryStrategyEvent
}

/**
 * Picks how the DNS-SD scan is issued, falling back to the next variant when a
 * scan brings back no raw callbacks at all:
 *
 * 1. service request with a type filter;
 * 2. request without the filter (our listener does the filtering);
 * 3. strategy 2, but discoverPeers first and discoverServices in its onSuccess;
 * 4. strategy 3 plus re-registering the local service right before the scan.
 *
 * Each scan opens a [WATCH_MS] window. Silence for the whole window rotates to
 * the next strategy (4 wraps to 1); the first raw callback proves the current
 * strategy works, stops rotation for the session and is persisted.
 *
 * Pure Kotlin: the caller supplies timestamps and acts on the returned events.
 * A rotation disarms the watch — the caller re-arms it with [scanStarted] when
 * the next scan actually begins.
 */
class DiscoveryStrategySelector(
    private val store: DiscoveryStrategyStore,
    defaultStrategy: Int = MIN_STRATEGY,
    rotationOrder: List<Int> = DEFAULT_ROTATION_ORDER,
    val silenceWindowMs: Long = WATCH_MS,
) {

    /** Active rotation order (sanitized to 1..4, non-empty); wraps last -> first. */
    val order: List<Int> = rotationOrder
        .filter { it in MIN_STRATEGY..MAX_STRATEGY }
        .distinct()
        .ifEmpty { DEFAULT_ROTATION_ORDER }

    /**
     * Current strategy, 1..4. Restored from storage, or starts at [defaultStrategy]
     * when nothing was saved yet; neither value is trusted blindly.
     */
    var strategy: Int = sanitize(store.load() ?: sanitize(defaultStrategy))
        private set

    /** True once a raw callback has proved the current strategy works. */
    var rotationStopped: Boolean = false
        private set

    /** True while a scan window is open. */
    var watching: Boolean = false
        private set

    private var scanStartedAtMs: Long? = null
    private var sawCallbackThisScan = false

    /** A scan is about to start: open a fresh [silenceWindowMs] window from [nowMs]. */
    fun scanStarted(nowMs: Long) {
        if (rotationStopped) return
        watching = true
        scanStartedAtMs = nowMs
        sawCallbackThisScan = false
    }

    /** A raw service/TXT callback arrived: this strategy works, stop rotating. */
    fun rawCallback(nowMs: Long) {
        sawCallbackThisScan = true
        if (!rotationStopped) {
            rotationStopped = true
            store.save(strategy)
        }
        watching = false
        scanStartedAtMs = null
    }

    /** Check the open window; rotate when it expired without any raw callback. */
    fun tick(nowMs: Long): DiscoveryStrategyEvent {
        if (rotationStopped || !watching) return DiscoveryStrategyEvent.None
        val startedAt = scanStartedAtMs ?: return DiscoveryStrategyEvent.None
        // >= so a tick landing exactly on the boundary rotates; a callback that
        // arrives at the same instant still counts, because it is processed first.
        if (nowMs - startedAt < silenceWindowMs) return DiscoveryStrategyEvent.None
        val from = strategy
        strategy = nextInOrder(from)
        // Disarm: the caller re-arms with scanStarted() for the next scan.
        watching = false
        scanStartedAtMs = null
        sawCallbackThisScan = false
        return DiscoveryStrategyEvent.Rotate(from = from, to = strategy)
    }

    /** True when the current window has run out but nobody called [tick] yet. */
    fun isWindowExpired(nowMs: Long): Boolean {
        val startedAt = scanStartedAtMs ?: return false
        return watching && !rotationStopped && nowMs - startedAt >= silenceWindowMs
    }

    /** Set by the last scan's outcome; exposed for logging only. */
    fun sawCallbackSinceScanStart(): Boolean = sawCallbackThisScan

    /** One-line startup summary logged once per process. */
    fun startupLogLine(): String =
        "dnssd: starting strategy=$strategy (default, not persisted) " +
            "order=${order.joinToString(",")} window=${silenceWindowMs / 1000}s"

    private fun nextInOrder(current: Int): Int {
        val clean = sanitize(current)
        val index = order.indexOf(clean)
        return if (index < 0) order.first() else order[(index + 1) % order.size]
    }

    companion object {
        /** Default silence window before the strategy rotates (kept for existing tests). */
        const val WATCH_MS = 25_000L

        /** Production silence window: 12 s before rotating to the next strategy. */
        const val SERVICE_SILENCE_WINDOW_MS = 12_000L

        /** Production starting strategy (unfiltered DNS-SD service request). */
        const val SERVICE_DEFAULT_STRATEGY = 2

        const val MIN_STRATEGY = 1
        const val MAX_STRATEGY = 4

        /** Default fallback order: 1 -> 2 -> 3 -> 4 -> 1. */
        val DEFAULT_ROTATION_ORDER: List<Int> = listOf(1, 2, 3, 4)

        /** Production fallback order: 2 -> 4 -> 3 -> 1 -> 2 (4 is a superset of 3). */
        val SERVICE_ROTATION_ORDER: List<Int> = listOf(2, 4, 3, 1)

        /** Builds the production selector: no persistence, starts at 2, order 2,4,3,1, 12 s window. */
        fun forService(): DiscoveryStrategySelector = DiscoveryStrategySelector(
            store = NoDiscoveryStrategyStore,
            defaultStrategy = SERVICE_DEFAULT_STRATEGY,
            rotationOrder = SERVICE_ROTATION_ORDER,
            silenceWindowMs = SERVICE_SILENCE_WINDOW_MS,
        )

        /** Next strategy in the default fallback order, wrapping 4 → 1. */
        fun nextStrategy(current: Int): Int =
            if (sanitize(current) >= MAX_STRATEGY) MIN_STRATEGY else sanitize(current) + 1

        /** Only 1..4 are meaningful; anything else (null, 0, 99) means "start at 1". */
        fun sanitize(value: Int?): Int =
            if (value != null && value in MIN_STRATEGY..MAX_STRATEGY) value else MIN_STRATEGY
    }
}
