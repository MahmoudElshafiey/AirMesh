package com.airmesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.airmesh.R
import com.airmesh.chat.ChatRepository
import com.airmesh.chat.ChatStore
import com.airmesh.chat.MESH_CONVERSATION_ID
import com.airmesh.node.AirMeshNode
import com.airmesh.node.DebugLogFileWriter
import com.airmesh.node.DirectSendResult
import com.airmesh.node.FileLogFileSystem
import com.airmesh.node.EventLog
import com.airmesh.node.LogEntry
import com.airmesh.node.LogUiBatcher
import com.airmesh.node.MeshLocation
import com.airmesh.node.MeshMessage
import com.airmesh.node.MyProfile
import com.airmesh.node.NodeIdProvider
import com.airmesh.node.SharedPreferencesNodeIdStore
import com.airmesh.node.createAirMeshNode
import com.airmesh.presence.PresenceBookkeeping
import com.airmesh.presence.PresenceChangeContext
import com.airmesh.presence.PresenceEvent
import com.airmesh.presence.PresenceEventLog
import com.airmesh.presence.PresenceState as LivePresenceState
import com.airmesh.presence.presenceTransitionLine
import com.airmesh.profile.ProfileInfo
import com.airmesh.protocol.FrameType
import com.airmesh.storage.ChatDatabase
import com.airmesh.storage.RoomChatStore
import com.airmesh.transport.AUTO_JOIN_TIMEOUT_MS
import com.airmesh.transport.AirMeshPeer
import com.airmesh.transport.AutoFailureReason
import com.airmesh.transport.AutoMeshAction
import com.airmesh.transport.AutoMeshController
import com.airmesh.transport.AutoMeshEvent
import com.airmesh.transport.AutoMeshListener
import com.airmesh.transport.AutoMeshRole
import com.airmesh.transport.AutoMeshState
import com.airmesh.transport.ConnectorState
import com.airmesh.transport.DiscoveryBackend
import com.airmesh.transport.DiscoveryCoordinator
import com.airmesh.transport.DiscoveryStrategySelector
import com.airmesh.transport.DiscoveryStrategyStore
import com.airmesh.transport.FreshnessRules
import com.airmesh.transport.ManualPauseAction
import com.airmesh.transport.ManualPausePolicy
import com.airmesh.transport.OwnerScanPolicy
import com.airmesh.transport.P2pRole
import com.airmesh.transport.PeerDirectory
import com.airmesh.transport.RecoveryAction
import com.airmesh.transport.RecoveryPeerInfo
import com.airmesh.transport.RecoveryRecorder
import com.airmesh.transport.RecoverySnapshot
import com.airmesh.transport.RecoverySupervisor
import com.airmesh.transport.StaleGroupGuardState
import com.airmesh.transport.TieBreakToken
import com.airmesh.transport.TimelineRecorder
import com.airmesh.transport.WifiDirectConnector
import com.airmesh.transport.WifiP2pPeerDirectory
import com.airmesh.transport.noAirMeshPeersBannerText
import com.airmesh.ui.EXTRA_MESSAGE_KEY
import com.airmesh.ui.EXTRA_TAB
import com.airmesh.ui.MeshActivity
import com.airmesh.ui.TAB_CHAT
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Date format for debug log file lines (TASK S2 item 4). */
private val LOG_FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Wall-clock [timeMs] as "yyyy-MM-dd HH:mm:ss" in the device's time zone. */
private fun stamp(timeMs: Long): String =
    LOG_FILE_STAMP.format(Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()))

/**
 * Foreground service that owns the mesh: [AirMeshNode], [WifiDirectConnector] and
 * the Room store behind them. The Activity only binds to this service and observes
 * its flows, so the mesh keeps running when the screen is off or the app goes to
 * the background — like a normal messaging app.
 *
 * Lifecycle rules (the pure policy lives in MeshServiceLogic and is tested on the
 * JVM): started from the foreground Activity once Wi-Fi Direct permissions are
 * granted, never at boot; starting it twice is a no-op; the P2P group is removed
 * only on an explicit user disconnect, the notification Stop action, or service
 * destruction — never because the Activity stopped or the screen turned off.
 */
class MeshService : Service() {

    private lateinit var _eventLog: EventLog
    private lateinit var node: AirMeshNode
    private lateinit var connector: WifiDirectConnector

    /**
     * Number of bound UI clients (the Activity). UI-only stats are refreshed only while one is
     * bound and visible (TASK U4 part 4); the networking loops never depend on this.
     */
    private var uiClientCount = 0

    /** Chat history layer (TASK D2 part 4): a separate Room DB from the packet store. */
    private lateinit var _chatStore: RoomChatStore
    private lateinit var _chatRepository: ChatRepository

    /** Truthful presence (TASK U5 part 2): per-node evidence plus event-driven recompute. */
    private val presenceBookkeeping = PresenceBookkeeping()
    private val presenceEventLog = PresenceEventLog()

    // Transition-reason context for the "presence: #id OLD -> NEW reason=X" log lines (TASK U5 part 4).
    /** Neighbor set as of the last noteNeighbors call. */
    private var presenceNeighborSet: Set<Int> = emptySet()
    /** Neighbor set at the previous recompute; compared with [presenceNeighborSet] for link up/down. */
    private var previousPresenceNeighborSet: Set<Int> = emptySet()
    /** Nodes with at least one frame since the previous recompute (fresh evidence). */
    private val freshPresenceFrames = HashSet<Int>()

    /** DNS-SD directory of nearby AirMesh phones; created in [onCreate]. */
    private lateinit var peerDirectory: WifiP2pPeerDirectory

    /** Serializes every P2P scan so a classic pass never cancels the DNS-SD cycle. */
    private lateinit var discoveryCoordinator: DiscoveryCoordinator

    /** The auto-mesh state machine; created in [onCreate], address upgraded when the MAC arrives. */
    private var autoController: AutoMeshController? = null

    /** Paces an owner's merge scans (TASK M1): 15 s empty, one 8 s window per 90 s with clients. */
    private val ownerScanPolicy = OwnerScanPolicy()

    /** Temporary manual-action pauses for auto-connect (TASK S1 part 2); pure, no Android deps. */
    private val manualPausePolicy = ManualPausePolicy()

    /** Live neighbor count; feeds the scan policy and the advertised client count. */
    private var liveNeighborCount = 0

    /** Whether an owner scans for other groups to merge with (Advanced card switch). Persisted. */
    private val _mergeGroupsEnabled = MutableStateFlow(true)
    val mergeGroupsEnabled: StateFlow<Boolean> = _mergeGroupsEnabled.asStateFlow()

    /** Persistence behind [mergeGroupsEnabled]; created in [onCreate]. */
    private lateinit var mergeFlagStore: MergeGroupsFlagStore

    /** Whether the experimental recovery supervisor may act (Debug card switch). Persisted, default off. */
    private val _recoverySupervisorEnabled = MutableStateFlow(RecoverySupervisorFlagStore.DEFAULT_ENABLED)
    val recoverySupervisorEnabled: StateFlow<Boolean> = _recoverySupervisorEnabled.asStateFlow()

    /** Persistence behind [recoverySupervisorEnabled]; created in [onCreate]. */
    private lateinit var recoverySupervisorFlagStore: RecoverySupervisorFlagStore

    /** When we became owner of the current group (TASK S1 part 1); null when not owning one. */
    private var ownGroupFormedAtMs: Long? = null

    /** Monotonic startup-to-neighbor-up timeline recorder. */
    private val timelineRecorder = TimelineRecorder(SystemClock::elapsedRealtime)

    /** Forensic recorder for reconnection episodes (TASK R3 part 1): logs the "recovery:" lines. */
    private val recoveryRecorder = RecoveryRecorder(clock = System::currentTimeMillis) { line -> _eventLog.log(line) }

    /** Rolling debug log file writer (TASK S2 item 4); null in non-debuggable builds. */
    private var debugLogFileWriter: DebugLogFileWriter? = null

    /**
     * Escalating recovery supervisor (TASK R3 part 3); seeded with our node id so phones do not
     * run their level-3 cycles in lockstep. Lazy because the seed needs [node], set in onCreate.
     */
    private val recoverySupervisor by lazy {
        // TASK S1 part 1: the supervisor never removes ANY group, gives our own fresh owner group
        // a 10-minute grace (with or without clients), and never acts while a connect/create/merge
        // is in flight. The old defaults stay available to the existing tests.
        RecoverySupervisor(
            clock = { System.currentTimeMillis() },
            random = Random(node.localNodeId.toLong()),
            allowGroupRemoval = false,
            ownerGraceMs = RECOVERY_OWNER_GRACE_MS,
            blockWhileInFlight = true,
        )
    }

    /** This device's mesh id; set in [onCreate]. */
    val nodeId: Int get() = node.localNodeId

    /** The service's event log; the bound activity writes location phases into it. */
    val eventLog: EventLog get() = _eventLog

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _phase = MutableStateFlow(MeshServicePhase.NOT_STARTED)
    /** Coarse lifecycle of the mesh; NOT_STARTED until the first start. */
    val phase: StateFlow<MeshServicePhase> = _phase.asStateFlow()

    // --- Flows observed by the bound Activity ---------------------------------

    val neighbors: StateFlow<Set<Int>> get() = node.neighbors
    val messages: StateFlow<List<MeshMessage>> get() = node.messages
    val locations: StateFlow<List<MeshLocation>> get() = node.locations
    /** This node's own display profile (name + avatar color). */
    val myProfile: StateFlow<MyProfile> get() = node.myProfile
    /** Display names learned from the mesh, keyed by node id. */
    val profiles: StateFlow<Map<Int, ProfileInfo>> get() = node.profiles

    /** Chat history rows/summaries/contacts for the bound UI (TASK D2 part 4). */
    val chatStore: ChatStore get() = _chatStore
    /** Turns node events into chat rows; also where the UI records its own sends. */
    val chatRepository: ChatRepository get() = _chatRepository

    /**
     * Truthful presence of every node we have ever seen (TASK U5 part 2): recomputed on every
     * neighbor up/down and frame activity, plus a 5 s tick so aging states move without traffic.
     */
    private val _livePresence = MutableStateFlow<Map<Int, LivePresenceState>>(emptyMap())
    val livePresence: StateFlow<Map<Int, LivePresenceState>> = _livePresence.asStateFlow()

    /** Debounced presence transitions per node (TASK U5 part 2); the chat timeline renders them. */
    private val _presenceEventsByNode = MutableStateFlow<Map<Int, List<PresenceEvent>>>(emptyMap())
    val presenceEventsByNode: StateFlow<Map<Int, List<PresenceEvent>>> = _presenceEventsByNode.asStateFlow()

    /**
     * Recompute every node's state and publish it. The event log is noted for EVERY node on
     * every recompute — even when the published map does not change — because a StateFlow
     * suppresses no-change emissions, yet repeated notes are what advance a pending transition
     * past its debounce window while states stay stable.
     */
    private fun recomputePresenceNow() {
        val states = presenceBookkeeping.states()
        for ((nodeId, state) in states) presenceEventLog.note(nodeId, state)
        // One event-log line per transition with its cause (TASK U5 part 4). First sight of a
        // node has no previous published state, so it is not a transition and stays quiet.
        val ownLinkWentDown = presenceNeighborSet.isEmpty() && previousPresenceNeighborSet.isNotEmpty()
        val ownLinkCameUp = presenceNeighborSet.isNotEmpty() && previousPresenceNeighborSet.isEmpty()
        for ((nodeId, next) in states) {
            val previous = _livePresence.value[nodeId] ?: continue
            val line = presenceTransitionLine(
                nodeId,
                previous,
                next,
                PresenceChangeContext(
                    ownLinkWentDown = ownLinkWentDown,
                    ownLinkCameUp = ownLinkCameUp,
                    directLinkDropped = nodeId in previousPresenceNeighborSet &&
                        nodeId !in presenceNeighborSet && presenceNeighborSet.isNotEmpty(),
                    freshFrameArrived = nodeId in freshPresenceFrames,
                ),
            ) ?: continue
            _eventLog.log(line)
        }
        // This round's context is consumed; the next round compares against what we just saw.
        previousPresenceNeighborSet = presenceNeighborSet
        freshPresenceFrames.clear()
        _livePresence.value = states
        val snapshot = presenceEventLog.snapshot()
        if (snapshot != _presenceEventsByNode.value) {
            _presenceEventsByNode.value = snapshot
        }
    }
    val events: StateFlow<List<LogEntry>> get() = _eventLog.entries

    /**
     * Batched view of [events] for the UI (TASK U4 part 4): republished at most every 250 ms, so
     * a burst of log lines costs the UI one recomposition per window instead of one per line.
     */
    private val _uiEvents = MutableStateFlow<List<LogEntry>>(emptyList())
    val uiEvents: StateFlow<List<LogEntry>> = _uiEvents.asStateFlow()

    val connectorState: StateFlow<ConnectorState> get() = connector.state

    /** Whether auto-mesh is on (Network tab switch, default on). Persisted. */
    private val _autoMeshEnabled = MutableStateFlow(true)
    val autoMeshEnabled: StateFlow<Boolean> = _autoMeshEnabled.asStateFlow()

    /** The controller's current state; drives the banner shown above every tab. */
    private val _autoMeshState = MutableStateFlow<AutoMeshState>(AutoMeshState.Idle)
    val autoMeshState: StateFlow<AutoMeshState> = _autoMeshState.asStateFlow()

    /** Why [autoMeshState] is Idle even though auto-mesh is enabled (e.g. missing permission). */
    private val _autoIdleReason = MutableStateFlow<String?>(null)
    val autoIdleReason: StateFlow<String?> = _autoIdleReason.asStateFlow()

    /** Milliseconds left in the temporary manual pause; > 0 shows the Network tab banner (TASK S1 part 2). */
    private val _manualPauseRemainingMs = MutableStateFlow(0L)
    val manualPauseRemainingMs: StateFlow<Long> = _manualPauseRemainingMs.asStateFlow()

    /** AirMesh-tagged peers from the DNS-SD directory; the Network tab lists them first. */
    val airMeshPeers: StateFlow<List<AirMeshPeer>> get() = peerDirectory.peers

    /** Active DNS-SD discovery strategy (1..4), shown as the banner's second line. */
    private val _discoveryStrategy = MutableStateFlow(1)
    val discoveryStrategy: StateFlow<Int> = _discoveryStrategy.asStateFlow()

    /** Raw DNS-SD callbacks since the service started; same second banner line. */
    private val _rawCallbackCount = MutableStateFlow(0)
    val rawCallbackCount: StateFlow<Int> = _rawCallbackCount.asStateFlow()

    private lateinit var notificationManager: NotificationManager
    /** Throttles running-notification updates to at most one per 2 s. */
    private val runningThrottle = NotificationThrottle()

    /** Whether received content posts a notification (Network tab toggle). Persisted. */
    private val _messageNotificationsEnabled = MutableStateFlow(true)
    val messageNotificationsEnabled: StateFlow<Boolean> = _messageNotificationsEnabled.asStateFlow()

    /** Keys of incoming items already notified, so list re-emissions do not repost. */
    private val seenIncoming = LinkedHashSet<String>()

    /** Ids of per-message notifications currently posted; cleared when the UI is visible. */
    private val postedIncomingIds = mutableSetOf<Int>()

    override fun onCreate() {
        super.onCreate()
        timelineRecorder.onStart()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannels()

        // Verbose raw P2P callback lines and interface dumps are stored in debuggable builds only.
        _eventLog = EventLog(verboseEnabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        node = createAirMeshNode(
            applicationContext,
            NodeIdProvider(SharedPreferencesNodeIdStore(this)).getOrCreate(),
            _eventLog,
            // TASK D2 part 4: re-announce the profile every 45 s (fresher names in
            // history) and fall back to "User #id" so a nameless node still shows.
            profileAnnounceIntervalMs = 45_000L,
            announceWithoutName = true,
            // TASK U5 part 5: probe idle direct links every 8 s and declare one dead after
            // 24 s of total silence (no bytes in either direction).
            keepAliveIntervalMs = 8_000L,
            deadAfterMs = 24_000L,
        )
        connector = WifiDirectConnector(applicationContext, node.transport, _eventLog)

        // TASK S2 item 4: rolling debug log file in the app's logs folder, debuggable builds only —
        // the same flag that enables verbose event-log lines above. The header marks this process;
        // on the next start the live file rotates into the previous-session slot.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            val logsDir = getExternalFilesDir("logs")
            if (logsDir != null && (logsDir.exists() || logsDir.mkdirs())) {
                val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
                    .getOrNull() ?: "?"
                debugLogFileWriter = DebugLogFileWriter(FileLogFileSystem(logsDir)).also { writer ->
                    writer.start(
                        "=== process start ${stamp(System.currentTimeMillis())} build $versionName " +
                            "node #${node.localNodeId} device ${Build.MODEL} SDK ${Build.VERSION.SDK_INT} ===",
                    )
                }
            }
        }

        // Chat history (TASK D2 part 4): separate Room DB so chat rows survive the
        // packet store's lifecycle; presence ages out on the wall clock.
        _chatStore = RoomChatStore(ChatDatabase.build(applicationContext))
        _chatRepository = ChatRepository(localNodeId = node.localNodeId, store = _chatStore)

        // Auto-mesh: a DNS-SD directory of nearby AirMesh phones plus the pure
        // state machine that decides when to join or create groups. One coordinator
        // owns every scan on the radio — discoverPeers cancels an ongoing DNS-SD
        // scan (and vice versa), so they must never overlap.
        val tieBreakToken = TieBreakToken.current
        // Every process starts at default strategy 2 (unfiltered) with NoDiscoveryStrategyStore,
        // ignoring any value saved in SharedPreferences by older sessions.
        val selector = DiscoveryStrategySelector.forService()
        if (loggedStartupStrategy.compareAndSet(false, true)) {
            _eventLog.log(selector.startupLogLine())
        }
        discoveryCoordinator = DiscoveryCoordinator(
            backend = object : DiscoveryBackend {
                override fun clearServiceRequests() = peerDirectory.clearServiceRequestsRaw()
                override fun addServiceRequest() = peerDirectory.addServiceRequestRaw()
                override fun discoverServices() {
                    timelineRecorder.onFirstScan()
                    peerDirectory.startServiceScanRaw()
                }
                override fun discoverPeers() = connector.discover()

                // Chained variants: the coordinator advances only when the framework
                // reports the outcome of the previous call.
                override fun clearServiceRequests(onDone: (Boolean, Int) -> Unit) =
                    peerDirectory.clearServiceRequestsRaw(onDone)

                override fun addServiceRequest(onDone: (Boolean, Int) -> Unit) =
                    peerDirectory.addServiceRequestRaw(filtered = true, onDone = onDone)

                override fun discoverServices(onDone: (Boolean, Int) -> Unit) {
                    timelineRecorder.onFirstScan()
                    peerDirectory.startServiceScanRaw(onDone)
                }

                override fun discoverPeers(onDone: (Boolean, Int) -> Unit) =
                    connector.discover(onDone)

                override fun addServiceRequest(filtered: Boolean, onDone: (Boolean, Int) -> Unit) =
                    peerDirectory.addServiceRequestRaw(filtered = filtered, onDone = onDone)

                override fun reRegisterLocalService(onDone: (Boolean, Int) -> Unit) =
                    peerDirectory.reRegisterLocalServiceRaw(onDone)
            },
            log = { line -> _eventLog.log(line) },
            // Delays run on the main looper, the same thread as the P2P callbacks
            // and the directory's own state, so the sequencer needs no locking.
            schedule = { ms, block -> serviceScope.launch { delay(ms); block() } },
            strategySelector = selector,
        )
        // The ad carries node id + token, not a MAC: our own P2P address is a
        // placeholder on many devices and nothing about advertising needs it.
        peerDirectory = WifiP2pPeerDirectory(
            applicationContext,
            myNodeId = node.localNodeId,
            tieBreakToken = tieBreakToken,
            coordinator = discoveryCoordinator,
            log = { line -> _eventLog.log(line) },
        )
        // Advertise immediately — other phones find us through this ad.
        peerDirectory.startAdvertising(isGroupOwner = false)
        connector.autoMeshListener = object : AutoMeshListener {
            override fun onConnectFailed(reason: AutoFailureReason) =
                autoEvent(AutoMeshEvent.ConnectFailed(reason), "connect failed (${reason.name.lowercase()})")

            override fun onIntentionalGroupRemoved() =
                autoEvent(AutoMeshEvent.IntentionalGroupGone, "intentional group removal")
        }

        // Per-message notifications are on by default; the Network tab toggle persists the choice.
        _messageNotificationsEnabled.value = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MESSAGE_NOTIFICATIONS_ENABLED, true)
        // Auto-mesh is on by default; the Network tab switch persists the choice.
        _autoMeshEnabled.value = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_MESH_ENABLED, true)
        // Merging separate groups is on by default (TASK M1); the Advanced card persists it.
        mergeFlagStore = SharedPreferencesMergeGroupsFlagStore(this)
        _mergeGroupsEnabled.value = mergeFlagStore.load()
        // The recovery supervisor stays off until the user opts in from the Debug card (TASK S1 part 1).
        recoverySupervisorFlagStore = SharedPreferencesRecoverySupervisorFlagStore(this)
        _recoverySupervisorEnabled.value = recoverySupervisorFlagStore.load()

        // The election ranks by (node id, tie-break token); our own Wi-Fi Direct
        // address is not used at all — on many devices it stays a placeholder that
        // never becomes known.
        autoController = AutoMeshController(
            myNodeId = node.localNodeId,
            tieBreakToken = tieBreakToken,
            autoEnabled = _autoMeshEnabled.value,
            mergeEnabled = _mergeGroupsEnabled.value,
            // TASK S1 part 2: manual actions pause auto-connect temporarily instead of forever.
            manualPausePolicy = manualPausePolicy,
        )
        _eventLog.log("auto: controller ready (node #${node.localNodeId}, tie-break=${tieBreakToken.toUInt().toString(16)})")

        // The system requires startForeground within 5 s of startForegroundService;
        // onCreate is well inside that window.
        startForeground(RUNNING_NOTIFICATION_ID, buildRunningNotification(0))

        serviceScope.launch {
            var previousNeighbors: Set<Int> = emptySet() // TASK R3 part 1: episode edge detection
            node.neighbors.collect { neighbors ->
                if (neighbors.isNotEmpty()) {
                    timelineRecorder.onNeighborUp()?.let { line -> _eventLog.log(line) }
                }
                // A growing group means a client just joined: the owner's merge scan
                // stays quiet for a few seconds while the new TCP link settles.
                if (neighbors.size > liveNeighborCount) {
                    ownerScanPolicy.noteClientJoined(System.currentTimeMillis())
                }
                // The controller's merge decision needs our live client count
                // (TASK M1 part 4); emitted only on change to keep the log quiet.
                if (neighbors.size != liveNeighborCount) {
                    autoEvent(AutoMeshEvent.MyClientsChanged(neighbors.size), "my clients=${neighbors.size}")
                }
                liveNeighborCount = neighbors.size
                // Owners re-advertise their live group size in the instance name;
                // updateClientCount is a no-op unless we are an owner with a live ad.
                peerDirectory.updateClientCount(neighbors.size)
                if (runningThrottle.shouldPost(System.currentTimeMillis())) {
                    notificationManager.notify(RUNNING_NOTIFICATION_ID, buildRunningNotification(neighbors.size))
                }
                // TASK R3 part 1: track reconnection episodes for the "recovery:" forensic lines.
                if (_autoMeshEnabled.value) {
                    val cs = connector.state.value
                    when {
                        neighbors.isEmpty() && previousNeighbors.isNotEmpty() -> {
                            val cause = when {
                                cs.p2pEnabled == false -> RecoveryRecorder.CAUSE_P2P_DISABLED
                                cs.groupFormed -> RecoveryRecorder.CAUSE_OWN_LINK_LOST // e.g. keepalive close
                                else -> RecoveryRecorder.CAUSE_GROUP_GONE
                            }
                            // TASK S2 item 2: a fresh episode re-anchors the timeline so its phases
                            // never show old totals; an extended one keeps its original start.
                            if (recoveryRecorder.onRecoveryStarted(cause, autoController?.let { describeState(it) } ?: "Idle", cs.groupFormed, cs.role)) {
                                timelineRecorder.onStart()
                            }
                            // TASK R3 part 3 (b): a keepalive closed our link but the group is still formed and we are a
                            // client — restart the TCP retry loop immediately with a fresh backoff.
                            if (cs.groupFormed && cs.role == P2pRole.CLIENT) connector.forceRestartConnectLoop()
                        }
                        neighbors.isNotEmpty() && previousNeighbors.isEmpty() ->
                            recoveryRecorder.onNeighborUp(cs.role, if (recoverySupervisor.didAct) RecoveryRecorder.PATH_SUPERVISOR else null)
                    }
                }
                previousNeighbors = neighbors
                // Truthful presence reacts to every neighbor up/down immediately (TASK U5 part 2).
                presenceBookkeeping.noteNeighbors(neighbors)
                presenceNeighborSet = neighbors // reason context for the next log lines (part 4)
                recomputePresenceNow()
            }
        }

        // One notification per newly received text/location while no Activity is
        // visible; the pure decision lives in shouldPostMessageNotification.
        serviceScope.launch {
            node.messages.collect { messages ->
                for (message in messages) {
                    if (!message.outgoing) {
                        notifyIncoming(IncomingKind.TEXT, message.senderId, message.text, message.timestamp)
                    }
                }
            }
        }
        serviceScope.launch {
            node.locations.collect { locations ->
                for (location in locations) {
                    if (location.senderId != node.localNodeId) {
                        notifyIncoming(IncomingKind.LOCATION, location.senderId, "", location.timestamp)
                    }
                }
            }
        }

        // --- Chat history wiring (TASK D2 part 4) ---------------------------------

        // Any decoded frame proves its origin is alive; also make sure a contact row
        // exists so history can show a name as soon as one arrives.
        serviceScope.launch {
            node.nodeActivity.collect { activity ->
                presenceBookkeeping.noteActivity(activity.nodeId)
                freshPresenceFrames.add(activity.nodeId) // reason context (TASK U5 part 4)
                _chatStore.upsertContact(activity.nodeId, null, 0)
                // A frame is fresh evidence: recompute at once instead of waiting for the tick.
                recomputePresenceNow()
            }
        }

        // PROFILE announcements fill in contact names/colors; the upsert never
        // erases an already-known value.
        serviceScope.launch {
            node.profiles.collect { profiles ->
                for ((nodeId, profile) in profiles) {
                    _chatStore.upsertContact(nodeId, profile.name, profile.colorIndex)
                }
            }
        }

        // User content with its real frame seq: the only feed that can key chat rows.
        serviceScope.launch {
            node.deliveredContent.collect { content ->
                when (content.type) {
                    FrameType.MSG -> _chatRepository.onIncomingBroadcast(content.originNode, content.text.orEmpty(), content.seq)
                    FrameType.LOCATION -> _chatRepository.onIncomingLocation(
                        originNode = content.originNode,
                        latitudeE6 = content.latitudeE6 ?: 0,
                        longitudeE6 = content.longitudeE6 ?: 0,
                        accuracyMeters = content.accuracyMeters ?: 0,
                        originSeq = content.seq,
                    )
                    FrameType.DIRECT -> _chatRepository.onIncomingDirect(content.originNode, content.text.orEmpty(), content.seq)
                    else -> Unit // ACK/HELLO/PROFILE carry no chat content
                }
            }
        }

        // Our own broadcast text rows. The StateFlow re-emits its whole list on every
        // change, so this is idempotent by design: a duplicate insert upgrades the
        // stored row instead (QUEUED -> SENT once a replay hands it to a neighbor).
        serviceScope.launch {
            node.messages.collect { messages ->
                for (message in messages) {
                    val seq = message.seq ?: continue
                    if (!message.outgoing) continue
                    _chatRepository.recordOutgoingBroadcast(message.text, seq, message.handedTo.isNotEmpty())
                }
            }
        }

        // DIRECT and LOCATION sends have no StateFlow row of their own; the result
        // feed is their only handoff signal — upgrade the queued row to SENT (the
        // store never downgrades, so repeats are harmless).
        serviceScope.launch {
            node.sendResults.collect { result ->
                if (!result.handed) return@collect
                val conversationId = when (result.type) {
                    FrameType.DIRECT -> result.destNode ?: return@collect
                    FrameType.LOCATION -> MESH_CONVERSATION_ID
                    else -> return@collect
                }
                _chatRepository.noteOutgoingHanded(conversationId, result.seq)
            }
        }

        // A recipient's DIRECT_ACK marks our DM row DELIVERED (never downgrades).
        serviceScope.launch {
            node.deliveryReceipts.collect { receipts ->
                for (receipt in receipts) {
                    _chatRepository.onReceipt(receipt.fromNodeId, receipt.ackedSeq)
                }
            }
        }

        // Presence ages out on the wall clock; recompute every 5 s so the UI updates without
        // any new frame arriving (TASK U5 part 2). Neighbor up/down and node activity already
        // trigger an immediate recompute in their collectors.
        serviceScope.launch {
            while (isActive) {
                delay(5_000L)
                recomputePresenceNow()
            }
        }

        // The UI consumes a batched view of the log: at most one publish per 250 ms window.
        // collectLatest cancels this body when newer lines arrive, so reaching the end means
        // [entries] is still the newest list — publish it and open the next window (TASK U4 part 4).
        serviceScope.launch {
            val batcher = LogUiBatcher()
            _eventLog.entries.collectLatest { entries ->
                if (!batcher.canPublish()) delay(batcher.waitMs())
                _uiEvents.value = entries
                batcher.markPublished()
            }
        }

        // TASK S2 item 4: mirror every event-log line into the rolling debug file (debuggable
        // builds), timestamped with the date; a second coroutine flushes at most every 2 s.
        val logFileWriter = debugLogFileWriter
        if (logFileWriter != null) {
            serviceScope.launch(Dispatchers.Default) {
                var lastSeq = 0L
                _eventLog.entries.collect { entries ->
                    for (entry in entries) {
                        if (entry.seq > lastSeq) logFileWriter.append("${stamp(entry.timeMs)} ${entry.text}")
                    }
                    if (entries.isNotEmpty()) lastSeq = entries.last().seq
                }
            }
            serviceScope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(DebugLogFileWriter.FLUSH_INTERVAL_MS)
                    logFileWriter.flush()
                }
            }
        }

        // --- Auto-mesh wiring ---------------------------------------------------
        // One tick per second drives backoff expiry, join timeouts and the takeover deadline.
        serviceScope.launch {
            var noAirMeshHintShown = false
            // TASK S2 item 1: the silent keepalive counter is reported once per minute.
            var lastTcpStatsAtMs = System.currentTimeMillis()
            while (isActive) {
                delay(1_000L)
                // Log once when the "no AirMesh peers" hint appears on screen; reset so a
                // later recurrence (peers left, then new classic devices appeared) logs again.
                val controller = autoController
                val hint = if (controller != null) {
                    noAirMeshPeersBannerText(
                        controller.state, System.currentTimeMillis(),
                        peerDirectory.peers.value.size, connector.state.value.peers.size,
                    )
                } else null
                // Discovery diagnostics for the banner's small second line (TASK U4 part 4):
                // refreshed only while a UI client is bound and visible. StateFlow equality
                // already suppresses no-change emissions, so returning to the UI shows at most
                // one second of stale values until this tick runs again.
                if (uiClientCount > 0 && AppForeground.anyActivityVisible) {
                    _discoveryStrategy.value = discoveryCoordinator.currentStrategy
                    _rawCallbackCount.value = discoveryCoordinator.rawCallbackCount
                }
                connector.staleGroupGuard.tick()
                when {
                    hint != null && !noAirMeshHintShown -> {
                        // Log the hint only; the strategy line is a separate concern.
                        _eventLog.log("auto: ${hint.lineSequence().first()}")
                        noAirMeshHintShown = true
                    }
                    hint == null -> noAirMeshHintShown = false
                }
                autoEvent(AutoMeshEvent.Tick, "tick")
                // TASK S1 part 2: feed the banner countdown; the tick above may have resumed auto.
                _manualPauseRemainingMs.value = manualPausePolicy.pauseRemainingMs(System.currentTimeMillis())
                // TASK R3 part 1: periodic "STILL NOT CONNECTED" diagnostic while an episode is active.
                recoveryRecorder.tick(buildRecoverySnapshot())
                // TASK S2 item 1: keepalive HELLOs are silent in the transport; report their count
                // once per minute, like the directory's "dnssd: stats" line.
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastTcpStatsAtMs >= 60_000L) {
                    lastTcpStatsAtMs = nowMs
                    _eventLog.log("tcp: stats keepAlive=${node.transport.keepAliveCount}")
                }
                // TASK R3 part 3: escalating recovery while auto-mesh is on, Wi-Fi Direct is up and no
                // live neighbor exists; each returned action maps to an existing call site (the
                // supervisor never creates a group itself).
                val cs = connector.state.value
                // TASK S1 part 1: the supervisor only runs when opted in from the Debug card.
                if (_recoverySupervisorEnabled.value) {
                    // How long we have owned the current group; feeds the owner grace window.
                    ownGroupFormedAtMs = if (cs.groupFormed && cs.role == P2pRole.GROUP_OWNER) {
                        ownGroupFormedAtMs ?: System.currentTimeMillis()
                    } else {
                        null
                    }
                    val nowMs = System.currentTimeMillis()
                    for (action in recoverySupervisor.tick(
                        RecoverySupervisor.Snapshot(
                            autoMeshOn = _autoMeshEnabled.value,
                            p2pEnabled = cs.p2pEnabled != false,
                            liveNeighborCount = liveNeighborCount,
                            state = controller?.state ?: AutoMeshState.Idle,
                            groupFormed = cs.groupFormed,
                            myClients = if (cs.role == P2pRole.GROUP_OWNER) liveNeighborCount else 0,
                            // TASK S1 part 2: a temporary manual pause holds the supervisor too.
                            paused = manualPausePolicy.isPaused(nowMs),
                            ownerGroupAgeMs = ownGroupFormedAtMs?.let { nowMs - it },
                        ),
                    )) {
                        _eventLog.log("recovery: supervisor ${describeRecoveryAction(action)}")
                        executeRecoveryAction(action)
                    }
                }
            }
        }

        // Every change in the visible AirMesh peer list feeds the controller.
        serviceScope.launch {
            peerDirectory.peers.collect { peers ->
                autoEvent(AutoMeshEvent.PeersChanged(peers), "peers=${peers.size}")
            }
        }

        // Group formation, role changes and permission changes drive the controller;
        // while in a group we keep our ad current with the owner flag set so other
        // phones know exactly whom to join.
        serviceScope.launch {
            var lastRole = connector.state.value.role
            var lastPermReason: String? = "unset" // sentinel: the first emission always feeds the controller
            var lastP2pEnabled: Boolean? = null // TASK R3 part 3 (a): detects Wi-Fi Direct coming back
            connector.state.collect { cs ->
                val permReason = autoMeshPermissionReason(
                    cs.wifiDirectPermissionGranted,
                    cs.localNetworkRequired,
                    cs.localNetworkGranted,
                ) ?: if (cs.p2pEnabled == false) "Wi-Fi Direct is off" else null
                if (permReason != lastPermReason) {
                    lastPermReason = permReason
                    // While paused the controller makes no decisions anyway; feeding it would only
                    // clear idle reasons or start discovery behind the pause.
                    if (_autoMeshEnabled.value) {
                        autoEvent(AutoMeshEvent.PermissionsChanged(permReason == null, permReason), "permissions changed")
                    }
                }

                // TASK R3 part 3 (a): Wi-Fi Direct came back — fresh start: wipe the peer table
                // (everything in it predates the radio), re-register our service, and re-arm the
                // startup burst so a scan starts within 500 ms.
                if (cs.p2pEnabled == true && lastP2pEnabled == false) {
                    peerDirectory.clearPeerTable()
                    peerDirectory.reRegisterLocalServiceRaw { _, _ -> }
                    if (_autoMeshEnabled.value) discoveryCoordinator.onEnterSearching(System.currentTimeMillis())
                }
                lastP2pEnabled = cs.p2pEnabled

                val newRole = cs.role
                if (newRole != lastRole) {
                    val wasInGroup = lastRole != P2pRole.NONE
                    lastRole = newRole
                    when {
                        // A role change while still formed (owner <-> client) re-forms too.
                        cs.groupFormed && newRole != P2pRole.NONE ->
                            autoEvent(AutoMeshEvent.GroupFormed(autoRoleOf(newRole)), "group formed as ${newRole.name.lowercase()}")
                        wasInGroup -> {
                            // TASK R3 part 1: the group loss starts (or extends) a recovery episode.
                            if (_autoMeshEnabled.value &&
                                recoveryRecorder.onRecoveryStarted(
                                    RecoveryRecorder.CAUSE_GROUP_GONE,
                                    autoController?.let { describeState(it) } ?: "Idle",
                                    false,
                                    P2pRole.NONE,
                                )
                            ) {
                                timelineRecorder.onStart() // TASK S2 item 2: fresh episode start re-anchors the timeline
                            }
                            recoveryRecorder.noteGroupLost()
                            autoEvent(AutoMeshEvent.GroupGone, "group gone")
                        }
                    }
                }

                // Keep our ad's owner flag current (TASK R3 part 2): o1 only while we own a live
                // group, so a phone whose group just died re-registers as o0 with clients=0 on this
                // very next state change; the directory also switches to faster peer ageing.
                peerDirectory.noteOurGroup(cs.groupFormed)
                peerDirectory.startAdvertising(FreshnessRules.desiredOwnerFlag(cs.groupFormed, newRole))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's Stop action: end the service and remove the group.
            releaseMesh(StopReason.NOTIFICATION_STOP)
            stopSelf()
            return START_NOT_STICKY
        }
        handleStart()
        // If the system restarts us after process death, resume the mesh; the
        // connector's stale-group cleanup handles any leftover P2P group.
        return START_STICKY
    }

    /** Starts the mesh once; a second start while running is a no-op. */
    private fun handleStart() {
        val next = reduceMeshServicePhase(_phase.value, ServiceEvent.Start)
        if (next == _phase.value) {
            _eventLog.log("service: already running — ignoring duplicate start")
            return
        }
        _phase.value = next
        _eventLog.log("service: mesh started (node #${node.localNodeId})")
        node.start()
        connector.start()
    }

    // Both run on the main thread, like the tick loop that reads [uiClientCount].
    override fun onBind(intent: Intent): IBinder {
        uiClientCount++
        return LocalBinder()
    }

    override fun onUnbind(intent: Intent): Boolean {
        if (uiClientCount > 0) uiClientCount--
        return super.onUnbind(intent)
    }

    /** In-process binding: the Activity gets a direct reference to this service. */
    inner class LocalBinder : Binder() {
        val service: MeshService get() = this@MeshService
    }

    // --- Actions forwarded by the bound Activity ------------------------------

    suspend fun sendText(text: String): Boolean = node.sendText(text)

    /**
     * Shares our location with the mesh and records it in the chat history. The row
     * starts QUEUED because `handed` is only known through the sendResults feed; that
     * collector upgrades it to SENT when a neighbor accepts the first flood.
     */
    suspend fun sendLocation(latitudeE6: Int, longitudeE6: Int, accuracyMeters: Int): Boolean {
        val seq = node.sendLocationWithSeq(latitudeE6, longitudeE6, accuracyMeters)
        if (seq > 0) {
            _chatRepository.recordOutgoingLocation(latitudeE6, longitudeE6, accuracyMeters, seq, handed = false)
        }
        return seq > 0
    }

    /** Sends a one-to-one direct message and records it in the chat history. */
    suspend fun sendDirect(destNodeId: Int, text: String): DirectSendResult? {
        val result = node.sendDirect(destNodeId, text) ?: return null
        // The result already carries `handed`, so the row gets its final status now;
        // the sendResults collector is a no-op safety net for this seq.
        _chatRepository.recordOutgoingDirect(destNodeId, text, result.seq, result.handed)
        return result
    }

    /** Saves and announces this node's display name and avatar color. */
    suspend fun setMyProfile(name: String, colorIndex: Int): Boolean =
        node.setMyProfile(name, colorIndex)

    // Every manual Wi-Fi Direct action pauses auto-mesh first, so the two do not
    // fight over the radio. Since TASK S1 part 2 the pause is TEMPORARY: the policy
    // resumes auto after its window (45 s; Disconnect 120 s), and the Auto switch —
    // which stays on during the pause — remains the persistent control.

    fun discover() {
        pauseAutoTemporarily(ManualPauseAction.DISCOVER)
        // Through the coordinator: it clears any active DNS-SD scan first and holds
        // the radio for a 30 s window before the service cycle resumes.
        discoveryCoordinator.requestPeerScan()
    }

    fun createGroup() {
        pauseAutoTemporarily(ManualPauseAction.CREATE_GROUP)
        connector.createGroup()
    }

    fun retryConnect() {
        pauseAutoTemporarily(ManualPauseAction.RETRY_TCP)
        connector.retryConnect()
    }

    fun connect(device: WifiP2pDevice) {
        pauseAutoTemporarily(ManualPauseAction.CONNECT)
        connector.connect(device)
    }

    /**
     * The UI's Disconnect button: removes the P2P group and closes the transport,
     * but keeps the node (and this service) alive so a new group can be formed
     * without restarting anything.
     */
    fun disconnectFromGroup() {
        pauseAutoTemporarily(ManualPauseAction.DISCONNECT)
        connector.disconnect()
    }

    /**
     * A manual action pauses auto-mesh temporarily (TASK S1 part 2): the controller unwinds its
     * current attempt and [manualPausePolicy] decides when it resumes. The Auto switch itself is
     * untouched, so the Network tab keeps showing what the user chose.
     */
    private fun pauseAutoTemporarily(action: ManualPauseAction) {
        val controller = autoController ?: return
        val actions = controller.onManualAction(action)
        syncAutoUi(controller)
        _manualPauseRemainingMs.value = manualPausePolicy.pauseRemainingMs(System.currentTimeMillis())
        controller.takePauseLogLine()?.let { _eventLog.log("auto: $it") }
        actions.forEach(::executeAutoAction)
    }

    /** The Network tab banner's Resume button: end the temporary manual pause now (TASK S1 part 2). */
    fun resumeManualPause() {
        val controller = autoController ?: return
        val actions = controller.resumeManualPause()
        syncAutoUi(controller)
        _manualPauseRemainingMs.value = manualPausePolicy.pauseRemainingMs(System.currentTimeMillis())
        controller.takePauseLogLine()?.let { _eventLog.log("auto: $it") }
        actions.forEach(::executeAutoAction)
    }

    /** Network tab switch: persist the choice and pause/resume the controller. */
    fun setAutoMeshEnabled(enabled: Boolean) {
        if (_autoMeshEnabled.value == enabled) return
        applyAutoMeshEnabled(enabled, persist = true)
    }

    /** Advanced card switch (TASK M1): start/stop an owner's merge scanning. */
    fun setMergeGroupsEnabled(enabled: Boolean) {
        if (_mergeGroupsEnabled.value == enabled) return
        _mergeGroupsEnabled.value = enabled
        mergeFlagStore.save(enabled)
        val controller = autoController ?: return
        val actions = controller.setMergeEnabled(enabled)
        syncAutoUi(controller)
        _eventLog.log("auto: merge flag ${if (enabled) "on" else "off"} => ${actions.joinToString(", ") { describeAction(it) }}")
        actions.forEach(::executeAutoAction)
    }

    /** Debug card switch (TASK S1 part 1): opt the experimental recovery supervisor in/out. */
    fun setRecoverySupervisorEnabled(enabled: Boolean) {
        if (_recoverySupervisorEnabled.value == enabled) return
        _recoverySupervisorEnabled.value = enabled
        recoverySupervisorFlagStore.save(enabled)
        if (!enabled) recoverySupervisor.reset() // no stale episode may fire on re-enable
        _eventLog.log("recovery: supervisor ${if (enabled) "on" else "off"}")
    }

    private fun applyAutoMeshEnabled(enabled: Boolean, persist: Boolean) {
        _autoMeshEnabled.value = enabled
        if (!enabled) {
            // TASK R3 part 1: manual mode owns reconnection now; stop tracking the episode.
            recoveryRecorder.onRecoveryAborted()
        }
        if (persist) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_MESH_ENABLED, enabled)
                .apply()
        }
        val controller = autoController
        val previousState = controller?.state
        val actions = controller?.setAutoEnabled(enabled) ?: emptyList()
        if (controller != null) syncAutoUi(controller)
        _eventLog.log("auto: ${if (enabled) "resumed" else "paused"} => ${actions.joinToString(", ") { describeAction(it) }}")
        // TASK S1 part 2: turning the switch back on may also clear a running manual pause.
        controller?.takePauseLogLine()?.let { _eventLog.log("auto: $it") }
        // TASK S2 item 2: milestones are still recorded, but the timeline line is no longer logged
        // on every state change (it was printed with stale numbers). It appears only at the first
        // neighbor up of a process and when a recovery episode completes.
        if (previousState != null) {
            timelineRecorder.onAutoStateChanged(previousState, controller.state)
        }
        actions.forEach(::executeAutoAction)
    }

    /** Publishes the controller's state/reason to the UI flows after a decision. */
    private fun syncAutoUi(controller: AutoMeshController) {
        _autoMeshState.value = controller.state
        _autoIdleReason.value = controller.idleReason
    }

    fun clearLog() = _eventLog.clear()

    /** Applies a batch of permission grant results to the connector's state. */
    fun onPermissionResults(results: Map<String, Boolean>) = connector.onPermissionResults(results)

    // --- Auto-mesh event funnel -------------------------------------------------

    /**
     * Feeds [event] to the controller (once it exists), logs every event with an
     * "auto: event" line plus the full peer list the directory currently sees, then
     * logs any decision (state, actions) and executes the returned actions. Tick is
     * not logged per second — its effects surface as state-change lines instead.
     */
    private fun autoEvent(event: AutoMeshEvent, describe: String) {
        val controller = autoController ?: return
        val previousState = controller.state
        if (event is AutoMeshEvent.PeersChanged && event.peers.isNotEmpty()) {
            timelineRecorder.onFirstPeer()
        }
        if (event !is AutoMeshEvent.Tick) {
            _eventLog.log("auto: event $describe peers=${describePeers(peerDirectory.peers.value)}")
        }
        val actions = controller.onEvent(event)
        // The controller queues at most one "merge ..." line per outcome change (TASK M1 part 4).
        controller.takeMergeLogLine()?.let { _eventLog.log("auto: $it") }
        // TASK S1 part 2: the manual-pause lines ("manual pause until +Ns" / "resumed (reason=...)").
        controller.takePauseLogLine()?.let { _eventLog.log("auto: $it") }
        // TASK R3 part 1: record every transition and pending timer for recovery forensics.
        if (controller.state != previousState) {
            recoveryRecorder.noteControllerTransition(recoveryStateLine(previousState), describeState(controller))
            when (val s = controller.state) {
                is AutoMeshState.Backoff -> recoveryRecorder.noteTimers(mapOf("backoff" to s.untilMs))
                is AutoMeshState.Joining -> recoveryRecorder.noteTimers(mapOf("joinTimeout" to (s.sinceMs + AUTO_JOIN_TIMEOUT_MS)))
                is AutoMeshState.Yielding -> {
                    recoveryRecorder.noteTimers(mapOf("mergeJoinTimeout" to (s.sinceMs + AUTO_JOIN_TIMEOUT_MS)))
                    recoveryRecorder.noteMergeAttempted()
                }
                else -> recoveryRecorder.noteTimers(emptyMap())
            }
            if (previousState is AutoMeshState.Yielding &&
                controller.state !is AutoMeshState.Client && controller.state !is AutoMeshState.Owner
            ) {
                recoveryRecorder.noteMergeAbandoned()
            }
        }
        syncAutoUi(controller)
        if (actions.isNotEmpty() || event !is AutoMeshEvent.Tick && event !is AutoMeshEvent.PeersChanged) {
            _eventLog.log(
                "auto: ${describeState(controller)} <- $describe => " +
                    "${actions.joinToString(", ") { describeAction(it) }}",
            )
        }
        val isStaleGroupEvent = event is AutoMeshEvent.GroupFormed &&
            connector.staleGroupGuard.state is StaleGroupGuardState.Removing
        // TASK S2 item 2: record milestones only — see applyAutoMeshEnabled for why this no longer logs.
        timelineRecorder.onAutoStateChanged(
            previous = previousState,
            current = controller.state,
            stale = isStaleGroupEvent,
        )
        actions.forEach(::executeAutoAction)
    }

    /** The full peer list in one line, e.g. "[#24066@AA:.. owner, #58081@CC:..]". */
    private fun describePeers(peers: List<AirMeshPeer>): String =
        if (peers.isEmpty()) "[]" else peers.joinToString(prefix = "[", postfix = "]") { p ->
            "#${p.nodeId}@${p.deviceAddress}${if (p.isGroupOwner) " owner" else ""}"
        }

    /** Executes one controller action against the connector and peer directory. */
    private fun executeAutoAction(action: AutoMeshAction) {
        when (action) {
            is AutoMeshAction.StartDiscovery -> if (autoController?.state is AutoMeshState.Owner && _mergeGroupsEnabled.value) {
                // An owner with merge on scans through the policy gate, not the
                // Searching burst/refresh cadence (TASK M1).
                startOwnerScanning()
            } else {
                peerDirectory.setDiscovering(true)
            }

            is AutoMeshAction.StopDiscovery -> peerDirectory.setDiscovering(false)
            is AutoMeshAction.CreateGroup -> connector.createGroup()
            is AutoMeshAction.ConnectTo -> connector.connectByAddress(action.deviceAddress)
            is AutoMeshAction.RemoveGroup -> {
                autoController?.markIntentionalGroupRemoval()
                connector.removeGroup("auto-mesh: clearing a stale group before retry")
            }
        }
    }

    /** Executes one supervisor action against the same call sites the controller uses (TASK R3 part 3). */
    private fun executeRecoveryAction(action: RecoveryAction) {
        val cs = connector.state.value
        when (action) {
            is RecoveryAction.ClearStalePeers -> peerDirectory.clearStalePeersNow()
            is RecoveryAction.ReRegisterService ->
                peerDirectory.startAdvertising(FreshnessRules.desiredOwnerFlag(cs.groupFormed, cs.role))
            is RecoveryAction.FreshScanBurst -> discoveryCoordinator.onEnterSearching(System.currentTimeMillis())
            is RecoveryAction.ResetTcpRetry -> connector.forceRestartConnectLoop()
            is RecoveryAction.ResetGroupPresence -> connector.resetGroupPresenceTracker()
            is RecoveryAction.RemoveOwnGroup -> {
                autoController?.markIntentionalGroupRemoval()
                connector.removeGroup("recovery: level 2 — own group has no clients")
            }
            is RecoveryAction.FullCycle -> {
                peerDirectory.reRegisterLocalServiceRaw { _, _ -> }
                discoveryCoordinator.fullCycleRestart()
            }
        }
    }

    /** One-line form of a supervisor action for the "recovery: supervisor ..." log line. */
    private fun describeRecoveryAction(action: RecoveryAction): String = when (action) {
        is RecoveryAction.ClearStalePeers -> "ClearStalePeers"
        is RecoveryAction.ReRegisterService -> "ReRegisterService"
        is RecoveryAction.FreshScanBurst -> "FreshScanBurst"
        is RecoveryAction.ResetTcpRetry -> "ResetTcpRetry"
        is RecoveryAction.ResetGroupPresence -> "ResetGroupPresence"
        is RecoveryAction.RemoveOwnGroup -> "RemoveOwnGroup"
        is RecoveryAction.FullCycle -> "FullCycle"
    }

    /**
     * Arm the coordinator's owner-scan gate and start discovering. The gate asks
     * [ownerScanPolicy] on every loop slice, so scans follow the merge policy
     * (15 s empty / one 8 s window per 90 s with clients) instead of the normal
     * burst + 60 s refresh cadence.
     */
    private fun startOwnerScanning() {
        discoveryCoordinator.ownerScanGate = { nowMs ->
            ownerScanPolicy.shouldScan(
                clients = liveNeighborCount,
                mergeEnabled = _mergeGroupsEnabled.value,
                // No scans while a merge is in flight (TASK M1 part 4).
                mergeInProgress = autoController?.state is AutoMeshState.Yielding,
                nowMs = nowMs,
            )
        }
        peerDirectory.setDiscovering(true)
    }

    private fun describeState(controller: AutoMeshController): String = when (val s = controller.state) {
        is AutoMeshState.Idle -> "Idle${controller.idleReason?.let { " ($it)" } ?: ""}"
        is AutoMeshState.Searching -> "Searching"
        is AutoMeshState.Creating -> "Creating"
        is AutoMeshState.Joining -> "Joining(#${s.targetNodeId} ${s.targetAddress})"
        is AutoMeshState.Yielding -> "Yielding(#${s.targetNodeId} ${s.targetAddress}, ${s.targetPhones} phones)"
        is AutoMeshState.Owner -> "Owner"
        is AutoMeshState.Client -> "Client"
        is AutoMeshState.Backoff -> "Backoff(${s.reason})"
    }

    private fun describeAction(action: AutoMeshAction): String = when (action) {
        is AutoMeshAction.StartDiscovery -> "StartDiscovery"
        is AutoMeshAction.StopDiscovery -> "StopDiscovery"
        is AutoMeshAction.CreateGroup -> "CreateGroup"
        is AutoMeshAction.ConnectTo -> "ConnectTo(${action.deviceAddress})"
        is AutoMeshAction.RemoveGroup -> "RemoveGroup"
    }

    /** Live context for the recovery recorder's diagnostic line (TASK R3 part 1). */
    private fun buildRecoverySnapshot(): RecoverySnapshot {
        val cs = connector.state.value
        val nowMs = System.currentTimeMillis()
        return RecoverySnapshot(
            stateLine = autoController?.let(::describeState) ?: "Idle",
            peers = peerDirectory.peers.value.map { p ->
                RecoveryPeerInfo(p.nodeId, p.isGroupOwner, p.clients, (nowMs - p.lastSeenMs).coerceAtLeast(0L))
            },
            discoveryActive = discoveryCoordinator.serviceDiscoveryActive,
            lastScanAgoMs = discoveryCoordinator.lastCycleAtMs?.let { (nowMs - it).coerceAtLeast(0L) },
            serviceAdvertOwner = peerDirectory.currentAdvertOwner,
            serviceAdvertClients = peerDirectory.currentAdvertClients,
            groupFormed = cs.groupFormed,
            tcpRetryRunning = connector.tcpConnectLoopRunning,
        )
    }

    /** One-line form of a controller state for recovery forensics (TASK R3 part 1); no idle reason. */
    private fun recoveryStateLine(s: AutoMeshState): String = when (s) {
        is AutoMeshState.Idle -> "Idle"
        is AutoMeshState.Searching -> "Searching"
        is AutoMeshState.Creating -> "Creating"
        is AutoMeshState.Joining -> "Joining(#${s.targetNodeId} ${s.targetAddress})"
        is AutoMeshState.Yielding -> "Yielding(#${s.targetNodeId} ${s.targetAddress}, ${s.targetPhones} phones)"
        is AutoMeshState.Owner -> "Owner"
        is AutoMeshState.Client -> "Client"
        is AutoMeshState.Backoff -> "Backoff(${s.reason})"
    }

    /** Maps the connector's role to the controller's (the service is the boundary). */
    private fun autoRoleOf(role: P2pRole): AutoMeshRole = when (role) {
        P2pRole.GROUP_OWNER -> AutoMeshRole.OWNER
        else -> AutoMeshRole.CLIENT
    }

    // --- Teardown --------------------------------------------------------------

    /**
     * Tears down the mesh for [reason]. The group-removal policy is pure and tested
     * (shouldRemoveGroupOnStop): only an explicit user disconnect, the notification
     * Stop action, or service destruction remove the P2P group — activity stop and
     * screen-off never do. In this design those two don't stop the mesh at all: the
     * foreground service keeps running while the app is in the background.
     */
    fun releaseMesh(reason: StopReason) {
        if (!shouldRemoveGroupOnStop(reason)) return
        _phase.value = reduceMeshServicePhase(_phase.value, ServiceEvent.Stop)
        _eventLog.log("service: releasing the mesh ($reason)")
        node.stop()
        connector.destroy()
    }

    override fun onDestroy() {
        releaseMesh(StopReason.SERVICE_DESTROYED)
        // Terminal for the directory too: it closes its own P2P channel, and a new
        // service instance gets a fresh one on restart.
        peerDirectory.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- Running notification ---------------------------------------------------

    private fun createChannels() {
        // Low importance + ongoing: a quiet "mesh is alive" indicator, not an alert.
        val running = NotificationChannel(
            CHANNEL_RUNNING,
            "AirMesh running",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows that the mesh is active and how many neighbors are live" }
        // Default importance: one notification per received text/location while no
        // Activity is visible. Grouped so several collapse into a single entry.
        val messages = NotificationChannel(
            CHANNEL_MESSAGES,
            "AirMesh messages",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "One notification per received text or location share" }
        notificationManager.createNotificationChannel(running)
        notificationManager.createNotificationChannel(messages)
    }

    /**
     * Posts a per-message notification for one newly received item. Dedup by
     * (kind, sender, timestamp): the StateFlows re-emit their whole list on every
     * change, so only items not seen before are notified. The decision itself is
     * pure and tested: nothing posts while an Activity is visible or the user has
     * turned message notifications off in the Network tab.
     */
    private fun notifyIncoming(kind: IncomingKind, senderId: Int, text: String, timestampMs: Long) {
        val key = "$kind:$senderId:$timestampMs"
        if (!seenIncoming.add(key)) return
        // Bound the dedup set; drop the oldest entries first (insertion order).
        while (seenIncoming.size > MAX_SEEN_INCOMING) {
            val oldest = seenIncoming.first()
            if (!seenIncoming.remove(oldest)) break
        }
        if (!shouldPostMessageNotification(AppForeground.anyActivityVisible, _messageNotificationsEnabled.value)) return

        val content = messageNotificationContent(kind, senderId, text, node.profiles.value[senderId]?.name)
        // Tapping opens the chat scrolled to this item. The dedup key doubles as
        // the navigation extra: parseMessageKey inverts exactly this format.
        val openChat = PendingIntent.getActivity(
            this, key.hashCode(), Intent(this, MeshActivity::class.java)
                .putExtra(EXTRA_TAB, TAB_CHAT)
                .putExtra(EXTRA_MESSAGE_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setSmallIcon(R.drawable.ic_stat_airmesh)
            .setGroup(GROUP_MESSAGES)
            .setContentIntent(openChat)
            .setAutoCancel(true)
            .build()
        // Hash of the dedup key: stable per item (a repost replaces its own slot),
        // different items almost never collide. The request code matches, so a
        // repost updates this notification's intent instead of creating a new one.
        val id = key.hashCode()
        postedIncomingIds.add(id)
        notificationManager.notify(id, notification)
    }

    /**
     * Dismisses the per-message notifications while the UI is visible — the chat
     * list already shows those items. Never touches the ongoing running
     * notification (a different id and channel).
     */
    fun clearMessageNotifications() {
        if (postedIncomingIds.isEmpty()) return
        val count = postedIncomingIds.size
        postedIncomingIds.forEach { id -> notificationManager.cancel(id) }
        postedIncomingIds.clear()
        _eventLog.log("service: cleared $count message notification(s) — the app is visible")
    }

    /** Network tab toggle: persist the choice and apply it to future posts. */
    fun setMessageNotificationsEnabled(enabled: Boolean) {
        _messageNotificationsEnabled.value = enabled
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MESSAGE_NOTIFICATIONS_ENABLED, enabled)
            .apply()
        _eventLog.log("service: message notifications ${if (enabled) "enabled" else "disabled"}")
    }

    private fun buildRunningNotification(neighborCount: Int): Notification {
        // Tapping the notification opens the chat tab; singleTop + onNewIntent in
        // MeshActivity keep it from stacking a second copy of the activity.
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MeshActivity::class.java).putExtra(EXTRA_TAB, TAB_CHAT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The Stop action ends the service and removes the P2P group.
        val stopAction = PendingIntent.getService(
            this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setContentTitle("AirMesh")
            .setContentText(runningNotificationText(neighborCount))
            .setSmallIcon(R.drawable.ic_stat_airmesh)
            .setContentIntent(openApp)
            .addAction(NotificationCompat.Action.Builder(null, "Stop", stopAction).build())
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        /** Intent action for the notification's Stop button. */
        const val ACTION_STOP = "com.airmesh.service.MESH_ACTION_STOP"
        private const val CHANNEL_RUNNING = "mesh_running"
        private const val RUNNING_NOTIFICATION_ID = 1
        private const val CHANNEL_MESSAGES = "mesh_messages"
        /** Group id so per-message notifications collapse into one entry. */
        private const val GROUP_MESSAGES = "airmesh_incoming"
        /** Max remembered incoming items for dedup; oldest are dropped first. */
        private const val MAX_SEEN_INCOMING = 500
        private const val PREFS_NAME = "airmesh_service"
        private const val KEY_MESSAGE_NOTIFICATIONS_ENABLED = "message_notifications_enabled"
        /** Network tab switch: whether auto-mesh is on (default true). */
        private const val KEY_AUTO_MESH_ENABLED = "auto_mesh_enabled"
        /** TASK S1 part 1: no supervisor action while we own a group younger than this. */
        private const val RECOVERY_OWNER_GRACE_MS = 600_000L
        /** Ensures the initial DNS-SD strategy configuration is logged once per process. */
        private val loggedStartupStrategy = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
