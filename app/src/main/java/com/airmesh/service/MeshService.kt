package com.airmesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.airmesh.R
import com.airmesh.node.AirMeshNode
import com.airmesh.node.EventLog
import com.airmesh.node.LogEntry
import com.airmesh.node.MeshLocation
import com.airmesh.node.MeshMessage
import com.airmesh.node.NodeIdProvider
import com.airmesh.node.SharedPreferencesNodeIdStore
import com.airmesh.node.createAirMeshNode
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
import com.airmesh.transport.P2pRole
import com.airmesh.transport.PeerDirectory
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    /** DNS-SD directory of nearby AirMesh phones; created in [onCreate]. */
    private lateinit var peerDirectory: WifiP2pPeerDirectory

    /** Serializes every P2P scan so a classic pass never cancels the DNS-SD cycle. */
    private lateinit var discoveryCoordinator: DiscoveryCoordinator

    /** The auto-mesh state machine; created in [onCreate], address upgraded when the MAC arrives. */
    private var autoController: AutoMeshController? = null

    /** Monotonic startup-to-neighbor-up timeline recorder. */
    private val timelineRecorder = TimelineRecorder(SystemClock::elapsedRealtime)

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
    val events: StateFlow<List<LogEntry>> get() = _eventLog.entries
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

        _eventLog = EventLog()
        node = createAirMeshNode(
            applicationContext,
            NodeIdProvider(SharedPreferencesNodeIdStore(this)).getOrCreate(),
            _eventLog,
        )
        connector = WifiDirectConnector(applicationContext, node.transport, _eventLog)

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

        // The election ranks by (node id, tie-break token); our own Wi-Fi Direct
        // address is not used at all — on many devices it stays a placeholder that
        // never becomes known.
        autoController = AutoMeshController(
            myNodeId = node.localNodeId,
            tieBreakToken = tieBreakToken,
            autoEnabled = _autoMeshEnabled.value,
        )
        _eventLog.log("auto: controller ready (node #${node.localNodeId}, tie-break=${tieBreakToken.toUInt().toString(16)})")

        // The system requires startForeground within 5 s of startForegroundService;
        // onCreate is well inside that window.
        startForeground(RUNNING_NOTIFICATION_ID, buildRunningNotification(0))

        serviceScope.launch {
            node.neighbors.collect { neighbors ->
                if (neighbors.isNotEmpty()) {
                    timelineRecorder.onNeighborUp()?.let { line -> _eventLog.log(line) }
                }
                if (runningThrottle.shouldPost(System.currentTimeMillis())) {
                    notificationManager.notify(RUNNING_NOTIFICATION_ID, buildRunningNotification(neighbors.size))
                }
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

        // --- Auto-mesh wiring ---------------------------------------------------
        // One tick per second drives backoff expiry, join timeouts and the takeover deadline.
        serviceScope.launch {
            var noAirMeshHintShown = false
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
                // Discovery diagnostics for the banner's small second line.
                _discoveryStrategy.value = discoveryCoordinator.currentStrategy
                _rawCallbackCount.value = discoveryCoordinator.rawCallbackCount
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
            connector.state.collect { cs ->
                val permReason = autoMeshPermissionReason(
                    cs.wifiDirectPermissionGranted,
                    cs.localNetworkRequired,
                    cs.localNetworkGranted,
                )
                if (permReason != lastPermReason) {
                    lastPermReason = permReason
                    autoEvent(AutoMeshEvent.PermissionsChanged(permReason == null, permReason), "permissions changed")
                }

                val newRole = cs.role
                if (newRole != lastRole) {
                    val wasInGroup = lastRole != P2pRole.NONE
                    lastRole = newRole
                    when {
                        // A role change while still formed (owner <-> client) re-forms too.
                        cs.groupFormed && newRole != P2pRole.NONE ->
                            autoEvent(AutoMeshEvent.GroupFormed(autoRoleOf(newRole)), "group formed as ${newRole.name.lowercase()}")
                        wasInGroup -> autoEvent(AutoMeshEvent.GroupGone, "group gone")
                    }
                }

                // Keep our ad's owner flag current; advertising needs no own MAC.
                peerDirectory.startAdvertising(cs.groupFormed && newRole == P2pRole.GROUP_OWNER)
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

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    /** In-process binding: the Activity gets a direct reference to this service. */
    inner class LocalBinder : Binder() {
        val service: MeshService get() = this@MeshService
    }

    // --- Actions forwarded by the bound Activity ------------------------------

    suspend fun sendText(text: String): Boolean = node.sendText(text)

    suspend fun sendLocation(latitudeE6: Int, longitudeE6: Int, accuracyMeters: Int): Boolean =
        node.sendLocation(latitudeE6, longitudeE6, accuracyMeters)

    // Every manual Wi-Fi Direct action pauses auto-mesh first, so the two do not
    // fight over the radio (rule: manual control wins until auto is re-enabled).

    fun discover() {
        pauseAutoForManualUse()
        // Through the coordinator: it clears any active DNS-SD scan first and holds
        // the radio for a 30 s window before the service cycle resumes.
        discoveryCoordinator.requestPeerScan()
    }

    fun createGroup() {
        pauseAutoForManualUse()
        connector.createGroup()
    }

    fun retryConnect() {
        pauseAutoForManualUse()
        connector.retryConnect()
    }

    fun connect(device: WifiP2pDevice) {
        pauseAutoForManualUse()
        connector.connect(device)
    }

    /**
     * The UI's Disconnect button: removes the P2P group and closes the transport,
     * but keeps the node (and this service) alive so a new group can be formed
     * without restarting anything.
     */
    fun disconnectFromGroup() {
        pauseAutoForManualUse()
        connector.disconnect()
    }

    /**
     * A manual action pauses auto-mesh for the rest of this session. Deliberately
     * NOT persisted: one tap on Discover must not kill auto-mesh across app
     * restarts — the Network tab switch is the persistent control.
     */
    private fun pauseAutoForManualUse() {
        if (!_autoMeshEnabled.value) return
        _eventLog.log("auto: paused by a manual action (turn Auto connect back on to resume)")
        applyAutoMeshEnabled(false, persist = false)
    }

    /** Network tab switch: persist the choice and pause/resume the controller. */
    fun setAutoMeshEnabled(enabled: Boolean) {
        if (_autoMeshEnabled.value == enabled) return
        applyAutoMeshEnabled(enabled, persist = true)
    }

    private fun applyAutoMeshEnabled(enabled: Boolean, persist: Boolean) {
        _autoMeshEnabled.value = enabled
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
        if (previousState != null) {
            timelineRecorder.onAutoStateChanged(previousState, controller.state)?.let { line ->
                _eventLog.log(line)
            }
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
        syncAutoUi(controller)
        if (actions.isNotEmpty() || event !is AutoMeshEvent.Tick && event !is AutoMeshEvent.PeersChanged) {
            _eventLog.log(
                "auto: ${describeState(controller)} <- $describe => " +
                    "${actions.joinToString(", ") { describeAction(it) }}",
            )
        }
        val isStaleGroupEvent = event is AutoMeshEvent.GroupFormed &&
            connector.staleGroupGuard.state is StaleGroupGuardState.Removing
        timelineRecorder.onAutoStateChanged(
            previous = previousState,
            current = controller.state,
            stale = isStaleGroupEvent,
        )?.let { line ->
            _eventLog.log(line)
        }
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
            is AutoMeshAction.StartDiscovery -> peerDirectory.setDiscovering(true)
            is AutoMeshAction.StopDiscovery -> peerDirectory.setDiscovering(false)
            is AutoMeshAction.CreateGroup -> connector.createGroup()
            is AutoMeshAction.ConnectTo -> connector.connectByAddress(action.deviceAddress)
            is AutoMeshAction.RemoveGroup -> {
                autoController?.markIntentionalGroupRemoval()
                connector.removeGroup("auto-mesh: clearing a stale group before retry")
            }
        }
    }

    private fun describeState(controller: AutoMeshController): String = when (val s = controller.state) {
        is AutoMeshState.Idle -> "Idle${controller.idleReason?.let { " ($it)" } ?: ""}"
        is AutoMeshState.Searching -> "Searching"
        is AutoMeshState.Creating -> "Creating"
        is AutoMeshState.Joining -> "Joining(#${s.targetNodeId} ${s.targetAddress})"
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

        val content = messageNotificationContent(kind, senderId, text)
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
        /** Ensures the initial DNS-SD strategy configuration is logged once per process. */
        private val loggedStartupStrategy = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
