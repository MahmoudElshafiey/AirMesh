package com.airmesh.sim

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import com.airmesh.routing.Router
import com.airmesh.routing.StoreAndForward
import kotlinx.coroutines.runBlocking
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Deterministic virtual-time mesh network for the AirMesh simulator.
 *
 * The network owns all nodes, links, the seeded random generator, and the event
 * queue. No wall-clock time or sleeping is involved: [runUntil] processes
 * events in timestamp order (insertion order for ties) and advances [clock].
 *
 * Links can be supplied explicitly with [connect], or derived from positions
 * and a radio radius with [configureRadioRange]. Mobility, when enabled, uses a
 * simple random-waypoint model and recomputes range links on every fixed step.
 *
 * Node ids must fit the 16-bit router address space. The network is intended to
 * be driven from one thread; the single event loop is what makes seeded runs
 * reproducible.
 *
 * @property clock current virtual time, shared by every node's routing layer.
 */
class SimNetwork(
    seed: Long = 0L,
    private val initialTtl: Int = Router.DEFAULT_TTL,
    private val maxAgeMs: Long = StoreAndForward.DEFAULT_MAX_AGE_MS,
    private val maxStoreSize: Int = StoreAndForward.DEFAULT_MAX_STORE_SIZE,
) {

    private data class RangeConfig(
        val radius: Double,
        val loss: Double,
        val latencyMs: Long,
    )

    private data class MobilityConfig(
        val width: Double,
        val height: Double,
        val minSpeed: Double,
        val maxSpeed: Double,
        val stepMs: Long,
    )

    private class NodeState(
        var x: Double,
        var y: Double,
    ) {
        var destinationX: Double = x
        var destinationY: Double = y
        var speed: Double = 0.0
    }

    init {
        require(initialTtl in 1..0xFF) { "initialTtl must be in 1..255: $initialTtl" }
        require(maxAgeMs > 0) { "maxAgeMs must be positive: $maxAgeMs" }
        require(maxStoreSize > 0) { "maxStoreSize must be positive: $maxStoreSize" }
    }

    /** Current virtual time. */
    val clock = SimClock()

    /** Ids of every node in the network, in ascending order. */
    val nodeIds: Set<Int>
        get() = nodes.keys.toSet()

    private val random = Random(seed)
    private val events = SimEventQueue()
    private val collector = SimMetricsCollector()
    private val nodes = sortedMapOf<Int, SimNode>()
    private val positions = sortedMapOf<Int, NodeState>()
    private val links = LinkedHashMap<LinkKey, SimLink>()

    private var rangeConfig: RangeConfig? = null
    private var mobilityConfig: MobilityConfig? = null

    /**
     * Adds one node at an optional position. Positions only matter in radio
     * range mode; explicit-link tests may ignore them.
     */
    fun addNode(id: Int, x: Double = 0.0, y: Double = 0.0): SimNode {
        require(!nodes.containsKey(id)) { "node $id already exists" }
        require(x.isFinite() && y.isFinite()) { "position must be finite: ($x, $y)" }

        val node = SimNode(
            id = id,
            network = this,
            metrics = collector,
            initialTtl = initialTtl,
            maxAgeMs = maxAgeMs,
            maxStoreSize = maxStoreSize,
        )
        nodes[id] = node
        positions[id] = NodeState(x = x, y = y)

        mobilityConfig?.let { config -> resetDestination(id, config) }
        if (rangeConfig != null) {
            runBlocking { applyLinks(rangeLinks()) }
        }
        return node
    }

    fun node(id: Int): SimNode =
        nodes[id] ?: throw IllegalArgumentException("unknown node: $id")

    /**
     * Adds or updates one explicit bidirectional link. Explicit links and radio
     * range mode are mutually exclusive.
     */
    fun connect(
        a: Int,
        b: Int,
        loss: Double = 0.0,
        latencyMs: Long = 0,
    ) {
        require(rangeConfig == null) {
            "connect() is only available in explicit-link mode; radio range mode manages links"
        }
        require(a != b) { "a node cannot link to itself: $a" }
        require(nodes.containsKey(a)) { "unknown node: $a" }
        require(nodes.containsKey(b)) { "unknown node: $b" }

        val link = SimLink(LinkKey.of(a, b), loss = loss, latencyMs = latencyMs)
        if (links.containsKey(link.key)) {
            links[link.key] = link
        } else {
            runBlocking { applyLinks(links.toMap() + (link.key to link)) }
        }
    }

    /** Removes one explicit link. No queued frame is discarded. */
    fun disconnect(a: Int, b: Int) {
        require(rangeConfig == null) {
            "disconnect() is only available in explicit-link mode; radio range mode manages links"
        }
        val key = LinkKey.of(a, b)
        if (links.remove(key) != null) {
            nodes.getValue(key.low).transport.removeNeighbor(key.high)
            nodes.getValue(key.high).transport.removeNeighbor(key.low)
        }
    }

    /**
     * Enters radio-range mode. Explicit [connect] links and range-derived links
     * are mutually exclusive, so this must be called before any explicit links
     * are added. Links are derived from the current positions.
     */
    fun configureRadioRange(
        range: Double,
        loss: Double = 0.0,
        latencyMs: Long = 0,
    ) {
        require(rangeConfig == null) {
            "configureRadioRange() cannot be called after radio-range mode is enabled"
        }
        require(links.isEmpty()) {
            "configureRadioRange() is only available before explicit links; " +
                "explicit-link and radio-range modes are mutually exclusive"
        }
        require(range > 0.0) { "range must be positive: $range" }
        require(loss in 0.0..1.0) { "loss must be in 0.0..1.0: $loss" }
        require(latencyMs >= 0) { "latencyMs must not be negative: $latencyMs" }

        rangeConfig = RangeConfig(radius = range, loss = loss, latencyMs = latencyMs)
        runBlocking { applyLinks(rangeLinks()) }
    }

    /** Moves a node immediately and recomputes range links if applicable. */
    fun setPosition(id: Int, x: Double, y: Double) {
        require(nodes.containsKey(id)) { "unknown node: $id" }
        require(x.isFinite() && y.isFinite()) { "position must be finite: ($x, $y)" }
        val state = positions.getValue(id)
        state.x = x
        state.y = y
        if (rangeConfig != null) {
            runBlocking { applyLinks(rangeLinks()) }
        }
    }

    /**
     * Enables seeded random-waypoint mobility.
     *
     * [configureRadioRange] must be called first because movement changes the
     * range-derived link set. A speed of zero keeps every node stationary. The
     * first movement step happens one [stepMs] after the current virtual time.
     */
    fun enableMobility(
        width: Double,
        height: Double,
        minSpeed: Double,
        maxSpeed: Double,
        stepMs: Long,
    ) {
        require(rangeConfig != null) {
            "configureRadioRange() must be called before enableMobility()"
        }
        require(width > 0.0 && height > 0.0) {
            "mobility area must be positive: ${width}x$height"
        }
        require(minSpeed >= 0.0) { "minSpeed must not be negative: $minSpeed" }
        require(maxSpeed >= minSpeed) {
            "maxSpeed must be at least minSpeed: min=$minSpeed, max=$maxSpeed"
        }
        require(stepMs > 0) { "stepMs must be positive: $stepMs" }
        require(mobilityConfig == null) { "mobility is already enabled" }

        val config = MobilityConfig(
            width = width,
            height = height,
            minSpeed = minSpeed,
            maxSpeed = maxSpeed,
            stepMs = stepMs,
        )
        mobilityConfig = config
        nodes.keys.forEach { id -> resetDestination(id, config) }
        scheduleMobilityStep()
    }

    /**
     * Schedules one originated message at virtual time [at].
     *
     * The message is actually created when the event loop reaches [at], so the
     * origin sees the topology and clock as they exist at that moment. Its
     * sequence number appears in [metrics] only after [runUntil] has processed
     * it.
     */
    fun originate(
        from: Int,
        payload: ByteArray,
        at: Long = clock.now,
        type: FrameType = FrameType.MSG,
    ) {
        require(nodes.containsKey(from)) { "unknown node: $from" }
        require(at >= clock.now) { "cannot originate in the past: now=${clock.now}, at=$at" }
        require(payload.size <= FrameCodec.MAX_PAYLOAD) {
            "payload is ${payload.size} bytes, max is ${FrameCodec.MAX_PAYLOAD}"
        }

        val payloadCopy = payload.copyOf()
        events.schedule(at) {
            val frame = nodes.getValue(from).originate(type, payloadCopy)
            collector.onOriginated(frame, at)
        }
    }

    /** Runs all events at or before [timeMs], then advances virtual time to it. */
    fun runUntil(timeMs: Long) {
        runBlocking { events.runUntil(timeMs, clock) }
    }

    /** Immutable snapshot of the current run. */
    fun metrics(): SimMetrics {
        val duplicates = nodes.values.sumOf { it.dedup.duplicateCount }
        return collector.snapshot(nodeCount = nodes.size, duplicatesDropped = duplicates)
    }

    /**
     * Called by [SimTransport]. Returns false only when the link does not exist,
     * so the routing layer can store the frame for a later replay.
     */
    /** Current link neighbors of [nodeId], used when a transport restarts. */
    internal fun neighborsOf(nodeId: Int): Set<Int> {
        val result = sortedSetOf<Int>()
        for (key in links.keys) {
            if (key.low == nodeId) result += key.high
            if (key.high == nodeId) result += key.low
        }
        return result
    }

    internal fun transmit(fromNodeId: Int, toNodeId: Int, bytes: ByteArray): Boolean {
        if (fromNodeId == toNodeId) return false
        val key = LinkKey.of(fromNodeId, toNodeId)
        val link = links[key] ?: return false

        collector.recordOnAir()
        // Loss is evaluated once, at handoff time. A dropped frame is never
        // delivered and is not treated as an unreachable neighbor.
        if (random.nextDouble() < link.loss) return true

        val deliveryAt = clock.now + link.latencyMs
        events.schedule(deliveryAt) {
            // A link removed while the frame was in flight drops the frame.
            if (links[key] == null) return@schedule
            nodes[toNodeId]?.receive(senderId = fromNodeId, bytes = bytes)
        }
        return true
    }

    private fun scheduleMobilityStep() {
        val config = mobilityConfig ?: return
        events.schedule(clock.now + config.stepMs) {
            val active = mobilityConfig
            if (active != null) {
                moveNodes(active)
                applyLinks(rangeLinks())
                scheduleMobilityStep()
            }
        }
    }

    private fun moveNodes(config: MobilityConfig) {
        for (id in nodes.keys) {
            val state = positions.getValue(id)
            val dx = state.destinationX - state.x
            val dy = state.destinationY - state.y
            val distance = hypot(dx, dy)
            val step = state.speed * config.stepMs / 1_000.0

            if (distance <= step || distance <= 1e-9) {
                state.x = state.destinationX
                state.y = state.destinationY
                resetDestination(id, config)
            } else {
                state.x += dx / distance * step
                state.y += dy / distance * step
            }
        }
    }

    private fun resetDestination(id: Int, config: MobilityConfig) {
        val state = positions.getValue(id)
        state.destinationX = random.nextDouble(0.0, config.width)
        state.destinationY = random.nextDouble(0.0, config.height)
        state.speed =
            if (config.minSpeed == config.maxSpeed) {
                config.minSpeed
            } else {
                random.nextDouble(config.minSpeed, config.maxSpeed)
            }
    }

    private fun rangeLinks(): Map<LinkKey, SimLink> {
        val config = rangeConfig ?: return emptyMap()
        val ids = nodes.keys.toList()
        val result = LinkedHashMap<LinkKey, SimLink>()

        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val a = ids[i]
                val b = ids[j]
                val pa = positions.getValue(a)
                val pb = positions.getValue(b)
                if (hypot(pa.x - pb.x, pa.y - pb.y) <= config.radius) {
                    val key = LinkKey.of(a, b)
                    result[key] = SimLink(
                        key = key,
                        loss = config.loss,
                        latencyMs = config.latencyMs,
                    )
                }
            }
        }
        return result
    }

    /**
     * Replaces the link set atomically from the routing layer's perspective:
     * every neighbor set is updated before any queued frame is replayed.
     */
    private suspend fun applyLinks(newLinks: Map<LinkKey, SimLink>) {
        val oldKeys = links.keys.toSet()
        val newKeys = newLinks.keys.toSet()
        val removed = oldKeys - newKeys
        val added = newKeys - oldKeys

        for (key in removed) {
            nodes.getValue(key.low).transport.removeNeighbor(key.high)
            nodes.getValue(key.high).transport.removeNeighbor(key.low)
        }

        links.clear()
        links.putAll(newLinks)

        for (key in added) {
            nodes.getValue(key.low).transport.addNeighbor(key.high)
            nodes.getValue(key.high).transport.addNeighbor(key.low)
        }

        for (key in added.sortedWith(compareBy({ it.low }, { it.high }))) {
            val low = nodes.getValue(key.low)
            val high = nodes.getValue(key.high)
            low.onNeighborAppeared(key.high)
            high.onNeighborAppeared(key.low)
        }
    }
}
