package com.airmesh.node

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import com.airmesh.routing.DedupCache
import com.airmesh.routing.Router
import com.airmesh.routing.StoreAndForward
import com.airmesh.transport.ReceivedPacket
import com.airmesh.transport.TcpMeshTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One visible text message, incoming or outgoing.
 *
 * @property senderId node that authored the message (the local id for TX).
 * @property text decoded UTF-8 payload.
 * @property timestamp local receive/send time in milliseconds.
 * @property ttl hops remaining in the received frame (or the initial TTL for
 *   an outgoing frame). This is the only hop information the frame carries.
 * @property outgoing true for messages this node originated.
 */
data class MeshMessage(
    val senderId: Int,
    val text: String,
    val timestamp: Long,
    val ttl: Int,
    val outgoing: Boolean,
)

/**
 * The protocol engine behind the UI, with no Android or Compose imports.
 *
 * It owns the transport, dedup cache, store-and-forward facade, and router. The
 * wiring mirrors the simulator's SimNode and the TCP end-to-end test:
 *
 * - Router sends through `transport.send` and reads `transport.neighbors`.
 * - Every decoded incoming frame is handed to `router.onFrameReceived`.
 * - A neighbor that just appeared gets a `storeAndForward.onNeighborAppeared`
 *   replay so queued frames are delivered after a reconnect, and the same
 *   replay is retried periodically while neighbors stay connected.
 *
 * Coroutines live in a supervised scope created by [start] and cancelled by
 * [stop]; both are idempotent, and the node can be restarted.
 *
 * @param storeFactory builds the durable store around the send callback. The
 *   Android factory passes `createRoomBackedStore(context, send)` with its
 *   default system clock; tests pass an in-memory store.
 * @param clock timestamps messages and store entries. Production uses the
 *   system clock; tests can inject a fixed one.
 */
class AirMeshNode(
    val localNodeId: Int,
    val transport: TcpMeshTransport,
    private val eventLog: EventLog,
    storeFactory: (suspend (neighborId: Int, frame: Frame) -> Boolean) -> StoreAndForward,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val replayIntervalMs: Long = DEFAULT_REPLAY_INTERVAL_MS,
) {

    companion object {
        /** Maximum number of messages kept for the UI. */
        const val MAX_MESSAGES = 500

        /** Hop limit for newly originated messages. */
        const val INITIAL_TTL = Router.DEFAULT_TTL

        /** How often queued frames are retried for currently connected neighbors. */
        const val DEFAULT_REPLAY_INTERVAL_MS = 5_000L

        private const val CORRUPT_POLL_MS = 500L
    }

    init {
        require(localNodeId in 1..0xFFFF) { "localNodeId must be in 1..65535: $localNodeId" }
    }

    private val lifecycleLock = Any()
    private var scope: CoroutineScope? = null
    private var running = false

    private val _messages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val messages: StateFlow<List<MeshMessage>> = _messages.asStateFlow()

    /** Node ids with a live handshaken transport socket. */
    val neighbors: StateFlow<Set<Int>> = transport.neighbors

    /** Shared bounded event log, also written by the Wi-Fi Direct connector. */
    val events: StateFlow<List<LogEntry>> = eventLog.entries

    private val storeAndForward: StoreAndForward
    private val router: Router

    /** Set by the single neighbor collector; access is confined to it. */
    private var previousNeighbors: Set<Int> = emptySet()

    /** Last corrupt-frame count reported, so deltas are logged once. */
    private var lastCorruptFrames = 0

    init {
        storeAndForward = storeFactory { neighborId, frame ->
            sendFrame(neighborId, frame)
        }

        router = Router(
            localNodeId = localNodeId,
            dedup = DedupCache(),
            neighbors = { transport.neighbors.value },
            send = { neighborId, frame -> sendFrame(neighborId, frame) },
            deliver = { frame -> onDelivered(frame) },
            initialTtl = INITIAL_TTL,
            packetStore = storeAndForward,
            clock = clock,
        )
    }

    /** Starts the transport and the collectors. Idempotent and restartable. */
    fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            running = true
            previousNeighbors = transport.neighbors.value
            lastCorruptFrames = transport.droppedCorruptFrames
            transport.start()

            val nodeScope = CoroutineScope(SupervisorJob() + dispatcher)
            scope = nodeScope
            nodeScope.launch {
                transport.incoming.collect { packet -> onIncoming(packet) }
            }
            nodeScope.launch {
                transport.neighbors.collect { current -> onNeighbors(current) }
            }
            nodeScope.launch { logCorruptFrameDeltas() }
            nodeScope.launch { periodicReplay() }
            eventLog.log("node $localNodeId started")
        }
    }

    /** Cancels every coroutine and closes the transport sockets. */
    fun stop() {
        val oldScope: CoroutineScope?
        synchronized(lifecycleLock) {
            if (!running) return
            running = false
            oldScope = scope
            scope = null
        }

        oldScope?.cancel()
        transport.stop()
        eventLog.log("node $localNodeId stopped")
    }

    fun close() = stop()

    /**
     * Encodes [text] as UTF-8 and floods it as one MSG frame.
     *
     * The whole body runs on the node's background dispatcher: a call from the
     * main thread (the UI) hops once and suspends, so encoding, routing, store
     * access, and socket writes never block or run on the main thread.
     *
     * @return false when the UTF-8 payload exceeds [FrameCodec.MAX_PAYLOAD],
     *   so the UI can show a validation error instead of crashing.
     */
    suspend fun sendText(text: String): Boolean = withContext(dispatcher) {
        val payload = text.toByteArray(Charsets.UTF_8)
        if (payload.size > FrameCodec.MAX_PAYLOAD) {
            eventLog.log(
                "send rejected: ${payload.size} UTF-8 bytes exceed ${FrameCodec.MAX_PAYLOAD}",
            )
            false
        } else {
            try {
                val frame = router.originate(FrameType.MSG, payload)
                appendMessage(
                    MeshMessage(
                        senderId = localNodeId,
                        text = text,
                        timestamp = clock(),
                        ttl = frame.ttl,
                        outgoing = true,
                    ),
                )
                eventLog.log("originated MSG seq=${frame.seq} ttl=${frame.ttl} (${payload.size} bytes)")
                true
            } catch (error: Exception) {
                eventLog.log("send failed: ${error.javaClass.simpleName}: ${error.message}")
                false
            }
        }
    }

    fun clearLog() {
        eventLog.clear()
    }

    private suspend fun onIncoming(packet: ReceivedPacket) {
        val frame = FrameCodec.decode(packet.bytes).getOrNull()
        if (frame == null) {
            eventLog.log("RX from ${packet.senderId}: frame decode failed")
            return
        }
        eventLog.log(
            "RX ${frame.type} node=${frame.nodeId} seq=${frame.seq} " +
                "ttl=${frame.ttl} from=${packet.senderId}",
        )
        router.onFrameReceived(frame, packet.senderId)
    }

    private fun onDelivered(frame: Frame) {
        if (frame.type != FrameType.MSG) return
        val text = String(frame.payload, Charsets.UTF_8)
        appendMessage(
            MeshMessage(
                senderId = frame.nodeId,
                text = text,
                timestamp = clock(),
                ttl = frame.ttl,
                outgoing = false,
            ),
        )
        eventLog.log("delivered MSG node=${frame.nodeId} seq=${frame.seq} ttl=${frame.ttl}")
    }

    /**
     * Replays stored frames for newly appeared neighbors only. This mirrors
     * SimNetwork.applyLinks and prevents a removal from triggering a replay.
     */
    private suspend fun onNeighbors(current: Set<Int>) {
        val added = current - previousNeighbors
        val removed = previousNeighbors - current
        previousNeighbors = current

        for (neighborId in removed.sorted()) {
            eventLog.log("neighbor down: $neighborId")
        }
        for (neighborId in added.sorted()) {
            eventLog.log("neighbor up: $neighborId")
            storeAndForward.onNeighborAppeared(neighborId)
        }
    }

    /**
     * Retries store-and-forward delivery for every currently connected neighbor
     * on a fixed interval. A frame whose send failed stays queued; without this,
     * it would only be retried if that same neighbor disconnected and came back.
     */
    private suspend fun periodicReplay() {
        while (currentCoroutineContext().isActive) {
            delay(replayIntervalMs)
            for (neighborId in transport.neighbors.value.sorted()) {
                storeAndForward.onNeighborAppeared(neighborId)
            }
        }
    }

    private suspend fun sendFrame(neighborId: Int, frame: Frame): Boolean {
        val bytes = try {
            FrameCodec.encode(frame)
        } catch (error: Exception) {
            eventLog.log("encode failed: ${error.javaClass.simpleName}: ${error.message}")
            return false
        }

        if (neighborId !in transport.neighbors.value) {
            eventLog.log(
                "TX ${frame.type} seq=${frame.seq} skipped: neighbor $neighborId is not connected",
            )
            return false
        }

        return try {
            transport.send(neighborId, bytes)
            eventLog.log(
                "TX ${frame.type} node=${frame.nodeId} seq=${frame.seq} " +
                    "ttl=${frame.ttl} -> $neighborId",
            )
            true
        } catch (error: Exception) {
            eventLog.log(
                "TX ${frame.type} seq=${frame.seq} -> $neighborId failed: " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
            false
        }
    }

    /**
     * The transport counts corrupt bodies internally and drops them before
     * `incoming`, so the node polls the counter and logs deltas. This keeps
     * CRC failures visible in the UI event log.
     */
    private suspend fun logCorruptFrameDeltas() {
        while (currentCoroutineContext().isActive) {
            delay(CORRUPT_POLL_MS)
            val current = transport.droppedCorruptFrames
            if (current != lastCorruptFrames) {
                eventLog.log(
                    "corrupt frames: ${current - lastCorruptFrames} dropped (total $current)",
                )
                lastCorruptFrames = current
            }
        }
    }

    private fun appendMessage(message: MeshMessage) {
        _messages.update { current ->
            (current + message).takeLast(MAX_MESSAGES)
        }
    }
}
