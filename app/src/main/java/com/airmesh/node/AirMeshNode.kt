package com.airmesh.node

import com.airmesh.profile.InMemoryProfileStorage
import com.airmesh.profile.NameSanitizer
import com.airmesh.profile.ProfileDirectory
import com.airmesh.profile.ProfileInfo
import com.airmesh.profile.ProfileStorage
import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import com.airmesh.protocol.LocationDecodeException
import com.airmesh.protocol.LocationPayload
import com.airmesh.protocol.ProfileDecodeException
import com.airmesh.protocol.ProfilePayload
import com.airmesh.routing.DedupCache
import com.airmesh.routing.ReplayTrigger
import com.airmesh.routing.Router
import com.airmesh.routing.StoreAndForward
import com.airmesh.transport.ReceivedPacket
import com.airmesh.transport.TcpMeshTransport
import kotlinx.coroutines.CancellationException
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
 * @property seq frame sequence number of an outgoing message (null for
 *   received ones); links later replay sends back to this chat row.
 * @property handedTo neighbor ids this node's own frame was successfully
 *   written to (first flood or a store-and-forward replay). This is hop-level
 *   feedback only: the protocol has no end-to-end ACK, so it says "left this
 *   phone", not "read by everyone". Always empty for received messages.
 */
data class MeshMessage(
    val senderId: Int,
    val text: String,
    val timestamp: Long,
    val ttl: Int,
    val outgoing: Boolean,
    val seq: Int? = null,
    val handedTo: Set<Int> = emptySet(),
)

/**
 * One shared location, received or sent. Kept in its own flow, separate from
 * [MeshMessage], so text and location items never mix in one list.
 *
 * @property senderId node that reported the position (the local id for TX).
 * @property latitudeE6 latitude in micro-degrees (-90_000_000..90_000_000).
 * @property longitudeE6 longitude in micro-degrees (-180_000_000..180_000_000).
 * @property accuracyMeters horizontal accuracy reported by the GPS, in meters.
 * @property timestamp local receive/send time in milliseconds.
 * @property ttl hops remaining in the received frame (or the initial TTL for
 *   an outgoing frame), like [MeshMessage.ttl].
 */
data class MeshLocation(
    val senderId: Int,
    val latitudeE6: Int,
    val longitudeE6: Int,
    val accuracyMeters: Int,
    val timestamp: Long,
    val ttl: Int,
)

/**
 * This node's own display profile. [name] is null until [AirMeshNode.setMyProfile]
 * succeeds; while it is null the node never announces a PROFILE frame.
 */
data class MyProfile(
    val name: String?,
    val colorIndex: Int,
)

/**
 * The protocol engine behind the UI, with no Android or Compose imports.
 *
 * It owns the transport, dedup cache, store-and-forward facade, and router. The
 * wiring mirrors the simulator's SimNode and the TCP end-to-end test:
 *
 * - Router sends through `transport.trySend` and reads `transport.neighbors`.
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
    private val profileStorage: ProfileStorage = InMemoryProfileStorage(),
    private val profileAnnounceIntervalMs: Long = DEFAULT_PROFILE_ANNOUNCE_INTERVAL_MS,
) {

    companion object {
        /** Maximum number of messages kept for the UI. */
        const val MAX_MESSAGES = 500

        /** Hop limit for newly originated messages. */
        const val INITIAL_TTL = Router.DEFAULT_TTL

        /** How often queued frames are retried for currently connected neighbors. */
        const val DEFAULT_REPLAY_INTERVAL_MS = 5_000L

        /** How often our PROFILE is re-announced while at least one neighbor lives. */
        const val DEFAULT_PROFILE_ANNOUNCE_INTERVAL_MS = 120_000L

        /** Storage key for this node's own display name (sanitized). */
        const val PROFILE_NAME_KEY = "profile_name"

        /** Storage key for this node's avatar color index, as a decimal string. */
        const val PROFILE_COLOR_KEY = "profile_color"

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

    /** Delivered (and locally sent) locations, separate from [messages]. */
    private val _locations = MutableStateFlow<List<MeshLocation>>(emptyList())
    val locations: StateFlow<List<MeshLocation>> = _locations.asStateFlow()

    /** This node's own display profile; loaded from storage at construction. */
    private val _myProfile = MutableStateFlow(
        MyProfile(
            name = profileStorage.getString(PROFILE_NAME_KEY),
            colorIndex = profileStorage.getString(PROFILE_COLOR_KEY)?.toIntOrNull() ?: 0,
        ),
    )
    val myProfile: StateFlow<MyProfile> = _myProfile.asStateFlow()

    /** Known display names of other nodes, keyed by node id. */
    private val profileDirectory = ProfileDirectory(localNodeId, clock, profileStorage)
    private val _profiles = MutableStateFlow<Map<Int, ProfileInfo>>(profileDirectory.snapshot())
    val profiles: StateFlow<Map<Int, ProfileInfo>> = _profiles.asStateFlow()

    /** Time (injected [clock]) at which we last originated a PROFILE frame. */
    @Volatile private var lastProfileAnnounceAt = 0L

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

    /**
     * Neighbors each of my own MSG frames was handed to, keyed by seq.
     *
     * Router.originate sends the first flood *before* sendText can append the
     * chat row, so successful sends are recorded here first and copied into the
     * row when it is created; later replay sends update the row in place.
     * Bounded like the message list (oldest seq evicted first). Guarded by
     * [outgoingLock] together with the matching [_messages] update, so a send
     * racing with the append can never be lost.
     */
    private val outgoingHandoffs = object : LinkedHashMap<Int, Set<Int>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Set<Int>>?) =
            size > MAX_MESSAGES
    }
    private val outgoingLock = Any()

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
            log = eventLog::log,
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
            nodeScope.launch { periodicProfileAnnounce() }
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
                // Same lock as recordHandoff: the row starts with every neighbor
                // the first flood reached, and no concurrent replay is missed.
                synchronized(outgoingLock) {
                    appendMessage(
                        MeshMessage(
                            senderId = localNodeId,
                            text = text,
                            timestamp = clock(),
                            ttl = frame.ttl,
                            outgoing = true,
                            seq = frame.seq,
                            handedTo = outgoingHandoffs[frame.seq].orEmpty(),
                        ),
                    )
                }
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

    /**
     * Sets this node's display name and avatar color, persists them under
     * [PROFILE_NAME_KEY]/[PROFILE_COLOR_KEY], and floods a PROFILE frame so the
     * mesh learns who we are. The name is sanitized first; an empty-after-
     * sanitize name or an out-of-range color is logged and rejected without
     * changing state. Runs on the background dispatcher like [sendText].
     */
    suspend fun setMyProfile(name: String, colorIndex: Int): Boolean = withContext(dispatcher) {
        if (colorIndex !in 0..ProfilePayload.MAX_COLOR_INDEX) {
            eventLog.log("profile rejected: color index $colorIndex out of range")
            return@withContext false
        }
        val sanitized = NameSanitizer.sanitize(name)
        if (sanitized == null) {
            eventLog.log("profile rejected: name is empty after sanitizing")
            return@withContext false
        }

        profileStorage.putString(PROFILE_NAME_KEY, sanitized)
        profileStorage.putString(PROFILE_COLOR_KEY, colorIndex.toString())
        _myProfile.value = MyProfile(sanitized, colorIndex)
        eventLog.log("profile set: name=$sanitized color=$colorIndex")
        announceProfile()
        true
    }

    /**
     * Floods our current profile as one PROFILE frame. No-op while no name is
     * set; with no neighbors the frame simply lands in store-and-forward and is
     * replayed when a link appears, so nothing is lost by announcing early.
     */
    private suspend fun announceProfile() {
        val profile = _myProfile.value
        if (profile.name == null) return

        try {
            val payload = ProfilePayload(profile.name, profile.colorIndex).encode()
            val frame = router.originate(FrameType.PROFILE, payload)
            lastProfileAnnounceAt = clock()
            eventLog.log("originated PROFILE seq=${frame.seq} ttl=${frame.ttl}")
        } catch (error: Exception) {
            eventLog.log("profile announce failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /**
     * Re-announces our PROFILE at most once per [profileAnnounceIntervalMs]
     * (measured with the injected [clock]) while at least one neighbor is live.
     * Nodes that joined after the original flood — or whose directory evicted
     * us — learn our name this way. Polls on the existing replay cadence so no
     * new interval knob is needed.
     */
    private suspend fun periodicProfileAnnounce() {
        while (currentCoroutineContext().isActive) {
            delay(replayIntervalMs)
            val now = clock()
            if (_myProfile.value.name != null &&
                transport.neighbors.value.isNotEmpty() &&
                now - lastProfileAnnounceAt >= profileAnnounceIntervalMs
            ) {
                announceProfile()
            }
        }
    }

    /**
     * Floods a position as one LOCATION frame through the same router as text:
     * identical flooding, dedup and store-and-forward — only the payload type
     * differs. Runs on the background dispatcher like [sendText].
     */
    suspend fun sendLocation(latitudeE6: Int, longitudeE6: Int, accuracyMeters: Int): Boolean =
        withContext(dispatcher) {
            try {
                val payload = LocationPayload(latitudeE6, longitudeE6, accuracyMeters).encode()
                val frame = router.originate(FrameType.LOCATION, payload)
                appendLocation(
                    MeshLocation(
                        senderId = localNodeId,
                        latitudeE6 = latitudeE6,
                        longitudeE6 = longitudeE6,
                        accuracyMeters = accuracyMeters,
                        timestamp = clock(),
                        ttl = frame.ttl,
                    ),
                )
                eventLog.log("originated LOCATION seq=${frame.seq} ttl=${frame.ttl} (${payload.size} bytes)")
                true
            } catch (error: Exception) {
                eventLog.log("send failed: ${error.javaClass.simpleName}: ${error.message}")
                false
            }
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
        when (frame.type) {
            FrameType.MSG -> deliverText(frame)
            FrameType.LOCATION -> deliverLocation(frame)
            FrameType.PROFILE -> deliverProfile(frame)
            // ACK/HELLO frames are protocol traffic, not user content.
            else -> Unit
        }
    }

    private fun deliverText(frame: Frame) {
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
     * Delivers a LOCATION frame. A malformed payload is logged and dropped —
     * one bad frame from a neighbor must never crash the node, so decode
     * failures are handled instead of thrown.
     */
    private fun deliverLocation(frame: Frame) {
        LocationPayload.decode(frame.payload).fold(
            onSuccess = { payload ->
                appendLocation(
                    MeshLocation(
                        senderId = frame.nodeId,
                        latitudeE6 = payload.latitudeE6,
                        longitudeE6 = payload.longitudeE6,
                        accuracyMeters = payload.accuracyMeters,
                        timestamp = clock(),
                        ttl = frame.ttl,
                    ),
                )
                eventLog.log("delivered LOCATION node=${frame.nodeId} seq=${frame.seq} ttl=${frame.ttl}")
            },
            onFailure = { error ->
                val reason = (error as? LocationDecodeException)?.failure?.name ?: error.message.orEmpty()
                eventLog.log("LOCATION from ${frame.nodeId} dropped: $reason")
            },
        )
    }

    /**
     * Delivers a PROFILE frame to the directory. The payload is decoded and the
     * name re-sanitized (a hostile node could send bidi or control characters
     * that pass the size checks); malformed payloads are logged and dropped so
     * one bad frame never crashes the node. Updates inside the directory's 10s
     * window are ignored, which also absorbs duplicate announcements.
     */
    private fun deliverProfile(frame: Frame) {
        ProfilePayload.decode(frame.payload).fold(
            onSuccess = { payload ->
                val name = NameSanitizer.sanitize(payload.name)
                if (name == null) {
                    eventLog.log("PROFILE from ${frame.nodeId} dropped: empty name")
                    return@fold
                }
                val accepted = profileDirectory.acceptFromNetwork(
                    frame.nodeId,
                    name,
                    payload.avatarColorIndex,
                )
                _profiles.value = profileDirectory.snapshot()
                if (accepted) {
                    eventLog.log("delivered PROFILE node=${frame.nodeId} seq=${frame.seq}")
                } else {
                    eventLog.log("PROFILE from ${frame.nodeId} ignored: update too soon")
                }
            },
            onFailure = { error ->
                val reason = (error as? ProfileDecodeException)?.failure?.name ?: error.message.orEmpty()
                eventLog.log("PROFILE from ${frame.nodeId} dropped: $reason")
            },
        )
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
            replayFor(neighborId, ReplayTrigger.APPEARED)
            // Let the newcomer know who we are right away; a no-op while no
            // name is set. The directory's 10s window absorbs any overlap with
            // a store-and-forward replay of an earlier PROFILE frame.
            announceProfile()
        }
    }

    /**
     * Runs one store-and-forward replay without letting a failure kill the
     * caller: an exception here used to stop both this collector and
     * [periodicReplay] forever, leaving queued frames undelivered with no log.
     */
    private suspend fun replayFor(neighborId: Int, trigger: ReplayTrigger) {
        try {
            storeAndForward.onNeighborAppeared(neighborId, trigger)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            eventLog.log(
                "replay failed for neighbor $neighborId: ${error.javaClass.simpleName}: ${error.message}",
            )
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
                replayFor(neighborId, ReplayTrigger.PERIODIC)
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
            // trySend reports false when the peer is gone or the write failed;
            // returning that keeps the frame queued for replay instead of
            // marking a lost frame delivered.
            val sent = transport.trySend(neighborId, bytes)
            if (sent) {
                eventLog.log(
                    "TX ${frame.type} node=${frame.nodeId} seq=${frame.seq} " +
                        "ttl=${frame.ttl} -> $neighborId",
                )
                // Only my own text frames feed the chat delivery status;
                // forwarded frames of other nodes are not shown as mine.
                if (frame.nodeId == localNodeId && frame.type == FrameType.MSG) {
                    recordHandoff(frame.seq, neighborId)
                }
            } else {
                eventLog.log(
                    "TX ${frame.type} seq=${frame.seq} -> $neighborId failed: no live socket",
                )
            }
            sent
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

    /**
     * Records that my MSG frame [seq] reached [neighborId] and updates the
     * matching outgoing chat row (newest first, since seq wraps at 16 bits).
     * If the row does not exist yet, sendText picks the set up when it appends.
     */
    private fun recordHandoff(seq: Int, neighborId: Int) {
        synchronized(outgoingLock) {
            val handedTo = outgoingHandoffs[seq].orEmpty() + neighborId
            outgoingHandoffs[seq] = handedTo
            _messages.update { current ->
                val index = current.indexOfLast { it.outgoing && it.seq == seq }
                if (index < 0) {
                    current
                } else {
                    current.toMutableList().also { it[index] = it[index].copy(handedTo = handedTo) }
                }
            }
        }
    }

    private fun appendMessage(message: MeshMessage) {
        _messages.update { current ->
            (current + message).takeLast(MAX_MESSAGES)
        }
    }

    private fun appendLocation(location: MeshLocation) {
        _locations.update { current ->
            (current + location).takeLast(MAX_MESSAGES)
        }
    }
}
