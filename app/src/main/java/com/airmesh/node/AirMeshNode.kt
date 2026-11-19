package com.airmesh.node

import com.airmesh.profile.InMemoryProfileStorage
import com.airmesh.profile.NameSanitizer
import com.airmesh.profile.ProfileDirectory
import com.airmesh.profile.ProfileInfo
import com.airmesh.profile.ProfileStorage
import com.airmesh.protocol.DirectAckPayload
import com.airmesh.protocol.DirectPayload
import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import com.airmesh.protocol.LocationDecodeException
import com.airmesh.protocol.LocationPayload
import com.airmesh.protocol.ProfileDecodeException
import com.airmesh.protocol.ProfilePayload
import com.airmesh.routing.DedupCache
import com.airmesh.routing.FramePolicy
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

/** Result of [AirMeshNode.sendDirect] (TASK D1 part 3). */
data class DirectSendResult(
    /** Seq of the originated DIRECT frame; later receipts carry it back. */
    val seq: Int,
    /** True when at least one neighbor accepted the write now; false = queued for replay. */
    val handed: Boolean,
)

/** One direct (one-to-one) message received by this node (TASK D1 part 3). */
data class DirectMessage(
    val fromNodeId: Int,
    val text: String,
    /** Frame seq of the DIRECT that carried it; links to [DeliveryReceipt.ackedSeq]. */
    val seq: Int,
    val receivedAt: Long,
)

/** One delivery receipt for a direct message this node sent (TASK D1 part 3). */
data class DeliveryReceipt(
    /** Node that acknowledged — the DM's recipient. */
    val fromNodeId: Int,
    /** Seq of the DIRECT frame being acknowledged. */
    val ackedSeq: Int,
    val receivedAt: Long,
)

/** Every decoded incoming frame proves its origin is alive (TASK D2 part 3). */
data class NodeActivity(
    /** Origin node id from the frame header — not the link peer that relayed it. */
    val nodeId: Int,
    val atMs: Long,
)

/** One piece of user content delivered to this node, with its real frame seq (TASK D2 part 3). */
data class DeliveredContent(
    /** MSG, LOCATION or DIRECT — the only frame types that carry user content. */
    val type: FrameType,
    val originNode: Int,
    val seq: Int,
    val text: String?,
    val latitudeE6: Int?,
    val longitudeE6: Int?,
    val accuracyMeters: Int?,
    val atMs: Long,
)

/** One outgoing send that has no chat row of its own (TASK D2 part 3). */
data class SendResult(
    /** DIRECT or LOCATION — the two kinds whose handoff state no StateFlow exposes. */
    val type: FrameType,
    /** Destination node id for DIRECT; null for LOCATION (broadcast to the mesh room). */
    val destNode: Int?,
    val seq: Int,
    /** True when at least one neighbor accepted the first flood; false = queued for replay. */
    val handed: Boolean,
    val atMs: Long,
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
    /** When true and no name is set yet, announce "User #<localNodeId>" (TASK D2 part 3). */
    private val announceWithoutName: Boolean = false,
    // TASK R2 part 1: PROFILE announcement timing knobs. Defaults keep the spec'd
    // behavior; node tests shorten them to stay fast on real time.
    private val profileBurstDelaysMs: List<Long> = ProfileAnnouncePlanner.DEFAULT_BURST_DELAYS_MS,
    private val profileNewOriginJitterRangeMs: LongRange = ProfileAnnouncePlanner.DEFAULT_NEW_ORIGIN_JITTER_RANGE_MS,
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

        /** How often the "PROFILE ignored" line may repeat for one node (TASK S2 item 3). */
        private const val PROFILE_IGNORE_REPORT_INTERVAL_MS = 60_000L

        /** Storage key for this node's own display name (sanitized). */
        const val PROFILE_NAME_KEY = "profile_name"

        /** Storage key for this node's avatar color index, as a decimal string. */
        const val PROFILE_COLOR_KEY = "profile_color"

        private const val CORRUPT_POLL_MS = 500L

        /** How many (origin, seq) pairs we remember for exactly-one-ack dedup. */
        private const val MAX_ACKED_DIRECTS = 256
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

    /** Direct messages addressed to this node; frames for others are relayed only. */
    private val _directMessages = MutableStateFlow<List<DirectMessage>>(emptyList())
    val directMessages: StateFlow<List<DirectMessage>> = _directMessages.asStateFlow()

    /** Delivery receipts for our own direct messages, newest last. */
    private val _deliveryReceipts = MutableStateFlow<List<DeliveryReceipt>>(emptyList())
    val deliveryReceipts: StateFlow<List<DeliveryReceipt>> = _deliveryReceipts.asStateFlow()

    // TASK D2 part 3 event feeds. tryEmit never suspends; an emission with no
    // collector attached is dropped, which is fine because the service collects
    // from startup and history itself lives in Room.
    /** Origin node of every decoded incoming frame; feeds presence tracking. */
    private val _nodeActivity = MutableSharedFlow<NodeActivity>(extraBufferCapacity = 64)
    val nodeActivity: SharedFlow<NodeActivity> = _nodeActivity.asSharedFlow()

    /** User content delivered to this node, with the real frame seq of each one. */
    private val _deliveredContent = MutableSharedFlow<DeliveredContent>(extraBufferCapacity = 64)
    val deliveredContent: SharedFlow<DeliveredContent> = _deliveredContent.asSharedFlow()

    /** Results of DIRECT/LOCATION sends, which have no StateFlow row. */
    private val _sendResults = MutableSharedFlow<SendResult>(extraBufferCapacity = 64)
    val sendResults: SharedFlow<SendResult> = _sendResults.asSharedFlow()

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

    /**
     * Owns the PROFILE announcement timing (TASK R2 part 1): created in [start] on
     * a real-time scope that dies with the node, so burst retries run on wall clock
     * even when tests drive the node's own dispatcher with virtual time. Null while
     * stopped; callers fall back to a direct announce or skip.
     */
    @Volatile private var profilePlanner: ProfileAnnouncePlanner? = null

    /**
     * First time we saw traffic from each origin (TASK R2 part 2), for the
     * learned-name log. Confined to the incoming collector coroutine: delivery
     * runs synchronously inside [onIncoming], so no other thread touches it.
     */
    private val firstSeenAt = HashMap<Int, Long>()

    /**
     * Throttle for the "PROFILE ignored" line (TASK S2 item 3): last report time and suppressed
     * count per node. Same confinement as [firstSeenAt] — only the incoming collector touches it.
     */
    private val profileIgnoreLastReportMs = HashMap<Int, Long>()
    private val profileIgnoreSuppressedCount = HashMap<Int, Int>()

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

    /**
     * (origin, seq) pairs of DIRECT frames we already acknowledged; a duplicate
     * delivery must not produce a second receipt. Bounded LRU like the message
     * lists. Touched only by the incoming collector thread that calls
     * [deliverDirect], so no lock is needed (same confinement as
     * [previousNeighbors]).
     */
    private val ackedDirects = object : LinkedHashMap<Pair<Int, Int>, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, Unit>?) =
            size > MAX_ACKED_DIRECTS
    }

    /**
     * Seq of my own DIRECT frames that at least one neighbor accepted on the
     * first flood; read by [sendDirect] to report `handed`. Written from any
     * dispatcher thread via [sendFrame], so guarded by [outgoingLock].
     */
    private val directHandoffs = object : LinkedHashMap<Int, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Unit>?) =
            size > MAX_MESSAGES
    }

    /**
     * Seq of my own LOCATION frames that at least one neighbor accepted on the
     * first flood; read by [sendLocationCore] to report `handed`. Same shape as
     * [directHandoffs]; guarded by [outgoingLock]. (TASK D2 part 3)
     */
    private val locationHandoffs = object : LinkedHashMap<Int, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Unit>?) =
            size > MAX_MESSAGES
    }

    /**
     * Node policy for DIRECT traffic (TASK D1 part 3): a frame addressed to me
     * stops here — not forwarded and not queued. Frames for other nodes keep the
     * historical flood-and-store behavior so they can reach their recipient even
     * when it is offline; a malformed payload decodes to null and therefore
     * relays as "not mine", which is the safe default.
     */
    private val directFramePolicy = object : FramePolicy {
        override fun shouldForward(frame: Frame): Boolean = !isDirectTrafficForMe(frame)

        // PROFILE frames are ephemeral presence info: still forwarded, but never
        // queued — a replay would re-flood a stale name long after it changed.
        override fun shouldStore(frame: Frame): Boolean =
            !isDirectTrafficForMe(frame) && frame.type != FrameType.PROFILE
    }

    /** True when [frame] is DIRECT/DIRECT_ACK traffic addressed to this node. */
    private fun isDirectTrafficForMe(frame: Frame): Boolean = when (frame.type) {
        FrameType.DIRECT -> DirectPayload.decode(frame.payload).getOrNull()?.destNodeId == localNodeId
        FrameType.DIRECT_ACK -> DirectAckPayload.decode(frame.payload).getOrNull()?.ackedNodeId == localNodeId
        else -> false
    }

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
            policy = directFramePolicy,
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
            // The planner schedules on real time (Dispatchers.IO, like the announce
            // itself used to run), but as a child of [nodeScope] so stop() cancels it.
            profilePlanner = ProfileAnnouncePlanner(
                scope = CoroutineScope(nodeScope.coroutineContext + Dispatchers.IO),
                clock = clock,
                onAnnounce = { announceProfile() },
                burstDelaysMs = profileBurstDelaysMs,
                newOriginJitterRangeMs = profileNewOriginJitterRangeMs,
                periodicIntervalMs = profileAnnounceIntervalMs,
            )
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
            profilePlanner = null
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
    suspend fun sendText(text: String): Boolean = sendTextCore(text) != null

    /**
     * Like [sendText] but returns the seq of the originated frame so callers can
     * correlate it with later delivery status. Returns -1 when nothing was sent.
     */
    suspend fun sendTextWithSeq(text: String): Int = sendTextCore(text) ?: -1

    /** Shared body of [sendText]/[sendTextWithSeq]; null means rejected or failed. */
    private suspend fun sendTextCore(text: String): Int? = withContext(dispatcher) {
        val payload = text.toByteArray(Charsets.UTF_8)
        if (payload.size > FrameCodec.MAX_PAYLOAD) {
            eventLog.log(
                "send rejected: ${payload.size} UTF-8 bytes exceed ${FrameCodec.MAX_PAYLOAD}",
            )
            null
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
                frame.seq
            } catch (error: Exception) {
                eventLog.log("send failed: ${error.javaClass.simpleName}: ${error.message}")
                null
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
        val planner = profilePlanner
        if (planner != null) {
            planner.onProfileSetOrChanged()
        } else {
            // Node not started yet: keep the historical immediate announce.
            announceProfile()
        }
        true
    }

    /**
     * The name our PROFILE frames carry. Falls back to "User #<localNodeId>" when
     * [announceWithoutName] and no real name is set yet (TASK D2 part 3); the
     * generated string passes NameSanitizer/ProfilePayload unchanged, so it needs
     * no sanitizing here.
     */
    private fun displayNameForAnnounce(): String? =
        _myProfile.value.name ?: if (announceWithoutName) "User #$localNodeId" else null

    /**
     * Floods our current profile as one PROFILE frame. No-op while neither a real
     * name nor the anonymous fallback is available. PROFILE frames are not stored
     * (see [directFramePolicy]), so with no neighbors nothing is queued; the
     * planner's neighbor-up burst in [onNeighbors] retries shortly after a link appears.
     *
     * @return true when a frame was actually originated, so the announcement cap
     *   ([ProfileAnnouncePlanner]) only counts real floods and no-op announces
     *   (no name yet) never consume budget or move the periodic gate.
     */
    private suspend fun announceProfile(): Boolean {
        val displayName = displayNameForAnnounce() ?: return false

        try {
            val payload = ProfilePayload(displayName, _myProfile.value.colorIndex).encode()
            val frame = router.originate(FrameType.PROFILE, payload)
            eventLog.log("originated PROFILE seq=${frame.seq} ttl=${frame.ttl}")
            return true
        } catch (error: Exception) {
            eventLog.log("profile announce failed: ${error.javaClass.simpleName}: ${error.message}")
            return false
        }
    }

    /**
     * Re-announces our PROFILE at most once per [profileAnnounceIntervalMs] while
     * at least one neighbor is live (TASK R2 part 1 moved the interval gate and
     * the per-minute cap into [ProfileAnnouncePlanner]). Nodes that joined after
     * the original flood — or whose directory evicted us — learn our name this
     * way. Polls on the existing replay cadence so no new interval knob is needed.
     */
    private suspend fun periodicProfileAnnounce() {
        while (currentCoroutineContext().isActive) {
            delay(replayIntervalMs)
            if (displayNameForAnnounce() != null && transport.neighbors.value.isNotEmpty()) {
                profilePlanner?.considerPeriodicAnnounce(true)
            }
        }
    }

    /**
     * Floods a position as one LOCATION frame through the same router as text:
     * identical flooding, dedup and store-and-forward — only the payload type
     * differs. Runs on the background dispatcher like [sendText].
     */
    suspend fun sendLocation(latitudeE6: Int, longitudeE6: Int, accuracyMeters: Int): Boolean =
        sendLocationCore(latitudeE6, longitudeE6, accuracyMeters) != null

    /** Like [sendLocation] but returns the seq of the originated frame; -1 when nothing was sent. */
    suspend fun sendLocationWithSeq(latitudeE6: Int, longitudeE6: Int, accuracyMeters: Int): Int =
        sendLocationCore(latitudeE6, longitudeE6, accuracyMeters) ?: -1

    /** Shared body of [sendLocation]/[sendLocationWithSeq]; null means failed. */
    private suspend fun sendLocationCore(
        latitudeE6: Int,
        longitudeE6: Int,
        accuracyMeters: Int,
    ): Int? = withContext(dispatcher) {
        try {
            val payload = LocationPayload(latitudeE6, longitudeE6, accuracyMeters).encode()
            val frame = router.originate(FrameType.LOCATION, payload)
            // The first flood ran inside originate; successful writes were
            // recorded in locationHandoffs by sendFrame before we got here.
            val handed = synchronized(outgoingLock) { locationHandoffs.containsKey(frame.seq) }
            _sendResults.tryEmit(SendResult(FrameType.LOCATION, null, frame.seq, handed, clock()))
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
            frame.seq
        } catch (error: Exception) {
            eventLog.log("send failed: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    /**
     * Sends a direct (one-to-one) message to [destNodeId] as one DIRECT frame.
     * The frame floods like any other, but every node that is not the recipient
     * relays it without showing or storing it; only the recipient surfaces it in
     * [directMessages] and answers with exactly one DIRECT_ACK (TASK D1 part 3).
     *
     * @return null when the send was rejected (self-send, out-of-range id, empty
     *   or oversized text) or failed. Otherwise the seq of the originated frame
     *   and whether at least one neighbor accepted the write now; `handed == false`
     *   means the frame is queued for store-and-forward replay until a path to
     *   [destNodeId] appears.
     */
    suspend fun sendDirect(destNodeId: Int, text: String): DirectSendResult? = withContext(dispatcher) {
        if (destNodeId == localNodeId) {
            eventLog.log("direct send rejected: self-send")
            return@withContext null
        }
        if (destNodeId !in 1..0xFFFF) {
            eventLog.log("direct send rejected: destination $destNodeId out of range")
            return@withContext null
        }
        val textBytes = text.toByteArray(Charsets.UTF_8)
        if (textBytes.isEmpty()) {
            eventLog.log("direct send rejected: empty text")
            return@withContext null
        }
        if (textBytes.size > DirectPayload.MAX_TEXT_BYTES) {
            eventLog.log(
                "direct send rejected: ${textBytes.size} UTF-8 bytes exceed ${DirectPayload.MAX_TEXT_BYTES}",
            )
            return@withContext null
        }

        try {
            val payload = DirectPayload(destNodeId, text).encode()
            val frame = router.originate(FrameType.DIRECT, payload)
            // The first flood ran inside originate; successful writes were
            // recorded in directHandoffs by sendFrame before we got here.
            val handed = synchronized(outgoingLock) { directHandoffs.containsKey(frame.seq) }
            _sendResults.tryEmit(SendResult(FrameType.DIRECT, destNodeId, frame.seq, handed, clock()))
            eventLog.log("direct: sent to #$destNodeId seq=${frame.seq} ${if (handed) "handed" else "queued"}")
            DirectSendResult(seq = frame.seq, handed = handed)
        } catch (error: Exception) {
            eventLog.log("direct send failed: ${error.javaClass.simpleName}: ${error.message}")
            null
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
        // Presence input (TASK D2 part 3): any decoded frame proves its ORIGIN is
        // alive, whether we deliver it or only relay it.
        _nodeActivity.tryEmit(NodeActivity(frame.nodeId, clock()))
        // New-origin trigger (TASK R2 part 1): traffic from an origin whose profile
        // we do not know yet means a newcomer is around; the planner re-announces
        // our own name shortly so it learns us too. Checked BEFORE processing, so
        // the first PROFILE from a stranger also counts as "new".
        if (frame.nodeId != localNodeId && !_profiles.value.containsKey(frame.nodeId)) {
            profilePlanner?.onNewOriginSeen(frame.nodeId)
        }
        // First-sight time per origin for the learned-name log (TASK R2 part 2).
        if (frame.nodeId != localNodeId) {
            firstSeenAt.putIfAbsent(frame.nodeId, clock())
        }
        router.onFrameReceived(frame, packet.senderId)
    }

    private fun onDelivered(frame: Frame) {
        when (frame.type) {
            FrameType.MSG -> deliverText(frame)
            FrameType.LOCATION -> deliverLocation(frame)
            FrameType.PROFILE -> deliverProfile(frame)
            FrameType.DIRECT -> deliverDirect(frame)
            FrameType.DIRECT_ACK -> deliverDirectAck(frame)
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
        _deliveredContent.tryEmit(
            DeliveredContent(FrameType.MSG, frame.nodeId, frame.seq, text, null, null, null, clock()),
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
                _deliveredContent.tryEmit(
                    DeliveredContent(
                        FrameType.LOCATION,
                        frame.nodeId,
                        frame.seq,
                        null,
                        payload.latitudeE6,
                        payload.longitudeE6,
                        payload.accuracyMeters,
                        clock(),
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
     * one bad frame never crashes the node. A changed name or color is accepted
     * immediately ([ProfileDirectory.upsertIfChanged], TASK R2 part 2), an
     * unchanged duplicate only refreshes the entry's timestamp, and more than
     * three changes from one node inside a 10 s window are ignored by the
     * directory's flood guard. The first time we learn an origin's name we log
     * how long after first sight it took.
     */
    private fun deliverProfile(frame: Frame) {
        ProfilePayload.decode(frame.payload).fold(
            onSuccess = { payload ->
                val name = NameSanitizer.sanitize(payload.name)
                if (name == null) {
                    eventLog.log("PROFILE from ${frame.nodeId} dropped: empty name")
                    return@fold
                }
                val knownBefore = _profiles.value.containsKey(frame.nodeId)
                val accepted = profileDirectory.upsertIfChanged(
                    frame.nodeId,
                    name,
                    payload.avatarColorIndex,
                    clock(),
                )
                _profiles.value = profileDirectory.snapshot()
                // TASK R2 part 1: their PROFILE arrived, so our retry burst for them
                // is done — cancel the rest of it (receipt counts, not acceptance).
                profilePlanner?.onProfileReceivedFrom(frame.nodeId)
                if (accepted) {
                    eventLog.log("delivered PROFILE node=${frame.nodeId} seq=${frame.seq}")
                    if (!knownBefore) {
                        // First time we know this origin's name: report the delay.
                        val firstSeen = firstSeenAt[frame.nodeId]
                        if (firstSeen != null) {
                            eventLog.log(
                                "profile: learned name for #${frame.nodeId} after " +
                                    "${clock() - firstSeen}ms since first seen",
                            )
                        }
                    }
                } else {
                    noteProfileIgnored(frame.nodeId)
                }
            },
            onFailure = { error ->
                val reason = (error as? ProfileDecodeException)?.failure?.name ?: error.message.orEmpty()
                eventLog.log("PROFILE from ${frame.nodeId} dropped: $reason")
            },
        )
    }

    /**
     * TASK S2 item 3: the ignore line used to fire on every duplicate PROFILE frame (each node
     * re-announces every ~45 s), so it is reported once per node per 60 s with a count of the
     * suppressed lines instead.
     */
    private fun noteProfileIgnored(nodeId: Int) {
        val now = clock()
        val lastReport = profileIgnoreLastReportMs[nodeId]
        if (lastReport != null && now - lastReport < PROFILE_IGNORE_REPORT_INTERVAL_MS) {
            profileIgnoreSuppressedCount[nodeId] = (profileIgnoreSuppressedCount[nodeId] ?: 0) + 1
            return
        }
        val suppressed = profileIgnoreSuppressedCount.remove(nodeId) ?: 0
        profileIgnoreLastReportMs[nodeId] = now
        eventLog.log(
            "PROFILE from $nodeId ignored: unchanged or too many rapid changes" +
                if (suppressed > 0) " (+$suppressed similar lines suppressed)" else "",
        )
    }

    /**
     * Delivers a DIRECT frame addressed to this node and answers it with exactly
     * one DIRECT_ACK per (origin, seq) pair. Frames for other nodes are relayed
     * by the router but never surfaced here; malformed payloads are logged and
     * dropped so one bad frame cannot crash the node (TASK D1 part 3).
     */
    private fun deliverDirect(frame: Frame) {
        val payload = DirectPayload.decode(frame.payload).getOrNull() ?: run {
            eventLog.log("DIRECT from ${frame.nodeId} dropped: malformed payload")
            return
        }
        if (payload.destNodeId != localNodeId) return // for someone else: relayed, never shown

        appendDirectMessage(DirectMessage(frame.nodeId, payload.text, frame.seq, clock()))
        _deliveredContent.tryEmit(
            DeliveredContent(FrameType.DIRECT, frame.nodeId, frame.seq, payload.text, null, null, null, clock()),
        )
        eventLog.log("direct: received from #${frame.nodeId}")

        val key = frame.nodeId to frame.seq
        if (ackedDirects.containsKey(key)) return // exactly one ack per (origin, seq)
        ackedDirects[key] = Unit
        scope?.launch { sendAckFor(frame) }
    }

    /** Originates the DIRECT_ACK receipt for [frame]; failures are logged only. */
    private suspend fun sendAckFor(frame: Frame) {
        try {
            val payload = DirectAckPayload(ackedNodeId = frame.nodeId, ackedSeq = frame.seq).encode()
            router.originate(FrameType.DIRECT_ACK, payload)
            eventLog.log("direct: ack sent")
        } catch (error: Exception) {
            eventLog.log("direct: ack failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /**
     * Delivers a DIRECT_ACK receipt for one of our own direct messages. Receipts
     * addressed to other nodes are relayed only; malformed payloads are dropped.
     */
    private fun deliverDirectAck(frame: Frame) {
        val payload = DirectAckPayload.decode(frame.payload).getOrNull() ?: run {
            eventLog.log("DIRECT_ACK from ${frame.nodeId} dropped: malformed payload")
            return
        }
        if (payload.ackedNodeId != localNodeId) return // receipt for someone else's DM: relay only

        appendReceipt(DeliveryReceipt(frame.nodeId, payload.ackedSeq, clock()))
        eventLog.log("direct: ack received")
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
            // TASK R2 part 1: instead of one immediate announce, the planner runs a
            // short retry burst — PROFILE frames are not stored, so a lost first
            // shot used to wait for the periodic re-announce. A no-op while no name
            // is set; unchanged duplicates are absorbed by the directory.
            profilePlanner?.onNeighborUp(neighborId)
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
                // My own DIRECT frames feed sendDirect's "handed" flag; the
                // recipient decides later whether to surface it.
                if (frame.nodeId == localNodeId && frame.type == FrameType.DIRECT) {
                    synchronized(outgoingLock) { directHandoffs[frame.seq] = Unit }
                }
                // My own LOCATION frames feed the send-results flow's "handed" flag.
                if (frame.nodeId == localNodeId && frame.type == FrameType.LOCATION) {
                    synchronized(outgoingLock) { locationHandoffs[frame.seq] = Unit }
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

    private fun appendDirectMessage(message: DirectMessage) {
        _directMessages.update { current ->
            (current + message).takeLast(MAX_MESSAGES)
        }
    }

    private fun appendReceipt(receipt: DeliveryReceipt) {
        _deliveryReceipts.update { current ->
            (current + receipt).takeLast(MAX_MESSAGES)
        }
    }
}
