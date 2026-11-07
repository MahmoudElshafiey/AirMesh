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
import com.airmesh.transport.ConnectorState
import com.airmesh.transport.WifiDirectConnector
import com.airmesh.ui.MeshActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private lateinit var notificationManager: NotificationManager
    /** Throttles running-notification updates to at most one per 2 s. */
    private val runningThrottle = NotificationThrottle()

    /** Whether received content posts a notification (Network tab toggle). Persisted. */
    private val _messageNotificationsEnabled = MutableStateFlow(true)
    val messageNotificationsEnabled: StateFlow<Boolean> = _messageNotificationsEnabled.asStateFlow()

    /** Keys of incoming items already notified, so list re-emissions do not repost. */
    private val seenIncoming = LinkedHashSet<String>()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannels()

        _eventLog = EventLog()
        node = createAirMeshNode(
            applicationContext,
            NodeIdProvider(SharedPreferencesNodeIdStore(this)).getOrCreate(),
            _eventLog,
        )
        connector = WifiDirectConnector(applicationContext, node.transport, _eventLog)

        // Per-message notifications are on by default; the Network tab toggle persists the choice.
        _messageNotificationsEnabled.value = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MESSAGE_NOTIFICATIONS_ENABLED, true)

        // The system requires startForeground within 5 s of startForegroundService;
        // onCreate is well inside that window.
        startForeground(RUNNING_NOTIFICATION_ID, buildRunningNotification(0))

        serviceScope.launch {
            node.neighbors.collect { neighbors ->
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

    fun discover() = connector.discover()
    fun createGroup() = connector.createGroup()
    fun retryConnect() = connector.retryConnect()
    fun connect(device: WifiP2pDevice) = connector.connect(device)

    /**
     * The UI's Disconnect button: removes the P2P group and closes the transport,
     * but keeps the node (and this service) alive so a new group can be formed
     * without restarting anything.
     */
    fun disconnectFromGroup() = connector.disconnect()

    fun clearLog() = _eventLog.clear()

    /** Applies a batch of permission grant results to the connector's state. */
    fun onPermissionResults(results: Map<String, Boolean>) = connector.onPermissionResults(results)

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
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setSmallIcon(R.drawable.ic_notification_mesh)
            .setGroup(GROUP_MESSAGES)
            .setAutoCancel(true)
            .build()
        // Hash of the dedup key: stable per item (a repost replaces its own slot),
        // different items almost never collide.
        notificationManager.notify(key.hashCode(), notification)
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
        // Tapping the notification opens the app.
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MeshActivity::class.java),
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
            .setSmallIcon(R.drawable.ic_notification_mesh)
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
    }
}
