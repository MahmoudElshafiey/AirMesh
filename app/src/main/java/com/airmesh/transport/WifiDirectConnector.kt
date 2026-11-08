package com.airmesh.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.airmesh.node.EventLog
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Role this device currently has in the Wi-Fi Direct group. */
enum class P2pRole {
    NONE,
    GROUP_OWNER,
    CLIENT,
}

/**
 * Failure reports for the auto-mesh controller: a connect() or createGroup()
 * request failed and the controller must decide whether to retry (BUSY gets one
 * special retry) or back off. The service wires this to AutoMeshEvent.ConnectFailed.
 */
interface AutoMeshListener {
    fun onConnectFailed(reason: AutoFailureReason)
}

/** UI-facing snapshot of the Wi-Fi Direct side. */
data class ConnectorState(
    /** True when every runtime permission required on this device is granted. */
    val permissionsGranted: Boolean = false,
    /** The Wi-Fi Direct permission alone (NEARBY_WIFI_DEVICES or location). */
    val wifiDirectPermissionGranted: Boolean = false,
    val p2pEnabled: Boolean? = null,
    val peers: List<WifiP2pDevice> = emptyList(),
    val role: P2pRole = P2pRole.NONE,
    val groupFormed: Boolean = false,
    val groupOwnerAddress: String? = null,
    /** True while a manager.connect() request is in flight. */
    val connecting: Boolean = false,
    /** True on devices where ACCESS_LOCAL_NETWORK is required (SDK >= 37). */
    val localNetworkRequired: Boolean = false,
    val localNetworkGranted: Boolean = false,
)

/**
 * Wi-Fi Direct discovery/group management for the real mesh.
 *
 * This is deliberately separate from the throwaway prototype so the prototype
 * keeps working unchanged. Instead of exchanging raw text, it hands the TCP
 * layer to [TcpMeshTransport]:
 *
 * - group owner: `transport.listen(PORT)`
 * - client: a retry loop around `transport.connect(groupOwnerAddress, PORT)`
 *   with growing backoff (1 s, 2 s, 3 s, then every 5 s), because the owner
 *   may not have bound the port yet and the link can drop later.
 *
 * A removed group calls `transport.stop()` (closing all sockets) so the next
 * group formation starts from a clean transport and can reconnect without
 * restarting the app.
 */
class WifiDirectConnector(
    context: Context,
    private val transport: TcpMeshTransport,
    private val eventLog: EventLog,
) {

    companion object {
        /** Single TCP port used by the group owner. */
        const val PORT = 8988

        /** Timeout for the raw reachability probe before HELLO attempts start. */
        private const val PROBE_TIMEOUT_MS = 5_000L

        /** Reason reported when we could not even issue the framework call. */
        const val NO_CHANNEL_REASON = -1

        /** Reason reported when the Wi-Fi Direct runtime permission is missing. */
        const val NO_PERMISSION_REASON = -2
    }

    private val appContext = context.applicationContext
    private val p2p: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _state = MutableStateFlow(ConnectorState())
    val state: StateFlow<ConnectorState> = _state.asStateFlow()

    /** Our own MAC, learned from THIS_DEVICE_CHANGED; null until the first report. */
    private val _selfAddress = MutableStateFlow<String?>(null)
    val selfAddress: StateFlow<String?> = _selfAddress.asStateFlow()

    /** Set by the service so auto-mesh can react to failed connect/create attempts. */
    var autoMeshListener: AutoMeshListener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var receiverRegistered = false
    private var started = false
    private var listening = false
    private var connectJob: Job? = null

    /** Owner address the current [connectJob] targets, if any. */
    private var connectTarget: String? = null

    /** Should-retry / should-stop rules for the client's TCP connect loop. */
    private val connectDecider = ClientConnectDecider()

    /** Debounces "group not formed" reports during formation. */
    private val presenceTracker = GroupPresenceTracker()

    /** One-shot job that confirms a pending "group gone" episode after the grace period. */
    private var groupGoneJob: Job? = null

    /** True until the first connection-info report after [start] has been handled. */
    private var staleGroupCheckPending = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> onP2pStateChanged(intent)
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeersChanged()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged()
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> onThisDeviceChanged(intent)
                else -> log("unknown broadcast: ${intent.action}")
            }
        }
    }

    init {
        log("connector created (Android SDK ${Build.VERSION.SDK_INT})")
        if (p2p == null) {
            log("WifiP2pManager unavailable — this device has no Wi-Fi Direct support")
        } else {
            try {
                channel = p2p.initialize(appContext, Looper.getMainLooper()) {
                    log("channel disconnected by the system")
                }
                log("WifiP2pManager.initialize() done")
            } catch (error: Exception) {
                log("initialize failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    // --- Lifecycle ---------------------------------------------------------

    fun start() {
        if (started) return
        started = true

        // Once per node start: device and permission snapshot so on-device logs
        // are self-explanatory when LAN TCP misbehaves.
        log(
            "device: model=${Build.MODEL} SDK_INT=${Build.VERSION.SDK_INT} " +
                "targetSdk=$targetSdkVersion ${describePermissionStates()}",
        )

        if (p2p == null) {
            log("start(): no Wi-Fi Direct support, nothing to do")
            return
        }

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            try {
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                receiverRegistered = true
                log("broadcast receiver registered")
            } catch (error: Exception) {
                log("registerReceiver failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            val locationManager = appContext.getSystemService(LocationManager::class.java)
            log(
                "location services enabled=${locationManager?.isLocationEnabled} " +
                    "(must be ON for discovery on Android 12 and below)",
            )
        }
        // The first connection-info report after start() may reveal a group that
        // survived from an earlier session; onConnectionInfo removes it.
        staleGroupCheckPending = true
        refreshConnectionInfo()
    }

    fun stop() {
        if (!started) return
        started = false

        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(receiver)
                log("broadcast receiver unregistered")
            } catch (error: IllegalArgumentException) {
                log("unregisterReceiver failed: ${error.message}")
            }
            receiverRegistered = false
        }

        // A group left behind by a stopped node blocks new formation with BUSY.
        if (state.value.groupFormed) {
            removeGroupForCleanup("node stopped")
        }
        closeTransport("activity stopped")
    }

    fun destroy() {
        stop()
        scope.cancel()
        channel?.close()
        channel = null
        log("connector destroyed")
    }

    // --- Permissions -------------------------------------------------------

    /** Runtime permission Wi-Fi Direct needs on this Android version. */
    fun requiredPermission(): String =
        PermissionRequirements.wifiDirectPermission(Build.VERSION.SDK_INT)

    /** Every runtime permission AirMesh must hold on this device. */
    fun requiredPermissions(): List<String> =
        PermissionRequirements.required(Build.VERSION.SDK_INT)

    /** Applies a batch of grant results (from the launcher or an initial check). */
    fun onPermissionResults(results: Map<String, Boolean>) {
        val required = requiredPermissions()
        _state.update { state ->
            state.copy(
                permissionsGranted = required.all { results[it] == true },
                wifiDirectPermissionGranted = results[requiredPermission()] == true,
                localNetworkRequired = PermissionRequirements.ACCESS_LOCAL_NETWORK in required,
                localNetworkGranted = results[PermissionRequirements.ACCESS_LOCAL_NETWORK] == true,
            )
        }
        required.forEach { permission ->
            log("runtime permission ${permission.substringAfterLast('.')} granted=${results[permission] == true}")
        }
        if (required.all { results[it] == true }) refreshConnectionInfo()
    }

    private fun hasP2pPermission(): Boolean = isGranted(requiredPermission())

    /** True when [permission] is currently held. */
    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Granted state of every permission AirMesh may need on this device, e.g.
     * "NEARBY_WIFI_DEVICES=granted ACCESS_LOCAL_NETWORK=denied". Makes timeout
     * logs self-explanatory: a dropped (not refused) LAN TCP connect is the
     * Local Network Protection signature when that permission is missing.
     */
    private fun describePermissionStates(): String = listOf(
        PermissionRequirements.NEARBY_WIFI_DEVICES,
        PermissionRequirements.ACCESS_LOCAL_NETWORK,
        PermissionRequirements.ACCESS_FINE_LOCATION,
    ).joinToString(" ") { permission ->
        "${permission.substringAfterLast('.')}=${if (isGranted(permission)) "granted" else "denied"}"
    }

    // --- Wi-Fi Direct actions ---------------------------------------------

    @SuppressLint("MissingPermission")
    fun discover() = discover { _, _ -> }

    /**
     * Classic peer scan. [onDone] reports (success, reason) so the DNS-SD refresh
     * cycle can chain discoverServices after the framework accepted this scan.
     */
    @SuppressLint("MissingPermission")
    fun discover(onDone: (Boolean, Int) -> Unit) {
        val manager = p2p ?: return noChannel("discover impossible: Wi-Fi Direct unsupported", onDone)
        val currentChannel = channel ?: return noChannel("discover impossible: no channel", onDone)
        if (!hasP2pPermission()) {
            log("discover blocked: ${requiredPermission().substringAfterLast('.')} not granted")
            return onDone(false, NO_PERMISSION_REASON)
        }

        log("discoverPeers() called")
        manager.discoverPeers(currentChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("discoverPeers: onSuccess (discovery started)")
                onDone(true, 0)
            }

            override fun onFailure(reason: Int) {
                log("discoverPeers: onFailure ${errorName(reason)}")
                onDone(false, reason)
            }
        })
    }

    private fun noChannel(message: String, onDone: (Boolean, Int) -> Unit) {
        log(message)
        onDone(false, NO_CHANNEL_REASON)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        val manager = p2p ?: return log("connect impossible: Wi-Fi Direct unsupported")
        val currentChannel = channel ?: return log("connect impossible: no channel")
        if (!hasP2pPermission()) {
            log("connect blocked: ${requiredPermission().substringAfterLast('.')} not granted")
            return
        }

        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        log("connect() requested to ${device.deviceName} (${device.deviceAddress})")
        _state.update { it.copy(connecting = true) }
        manager.connect(currentChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = log("connect: onSuccess (waiting for group formation)")

            override fun onFailure(reason: Int) {
                _state.update { it.copy(connecting = false) }
                log("connect: onFailure ${errorName(reason)}")
                autoMeshListener?.onConnectFailed(autoFailureReason(reason))
            }
        })
    }

    /**
     * Connects to a peer by MAC address (the auto-mesh controller only knows the
     * address from the DNS-SD ad, not a WifiP2pDevice). Delegates to [connect].
     */
    @SuppressLint("MissingPermission")
    fun connectByAddress(deviceAddress: String) {
        val device = WifiP2pDevice().apply { this.deviceAddress = deviceAddress }
        connect(device)
    }

    /** Public wrapper so the auto-mesh controller can clear a stale group before retrying. */
    @SuppressLint("MissingPermission")
    fun removeGroup(reason: String) = removeGroupForCleanup(reason)

    @SuppressLint("MissingPermission")
    fun createGroup() {
        doCreateGroup(allowBusyRetry = true)
    }

    /**
     * Calls manager.createGroup. When the system reports BUSY (a group from an
     * earlier session is still lingering), removes that group and retries once;
     * a second failure gives up.
     */
    @SuppressLint("MissingPermission")
    private fun doCreateGroup(allowBusyRetry: Boolean) {
        val manager = p2p ?: return log("createGroup impossible: Wi-Fi Direct unsupported")
        val currentChannel = channel ?: return log("createGroup impossible: no channel")
        if (!hasP2pPermission()) {
            log("createGroup blocked: ${requiredPermission().substringAfterLast('.')} not granted")
            return
        }

        log(if (allowBusyRetry) "createGroup() called" else "createGroup() retry after BUSY")
        manager.createGroup(currentChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("createGroup: onSuccess")
                refreshConnectionInfo()
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY && allowBusyRetry) {
                    // A stale group blocks new formation; remove it, wait for the
                    // removal to finish, then try once more.
                    log("createGroup failed with BUSY — removing the stale group, then retrying once")
                    manager.removeGroup(currentChannel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = doCreateGroup(allowBusyRetry = false)

                        override fun onFailure(r: Int) {
                            log("removeGroup after BUSY failed ${errorName(r)} — giving up on createGroup")
                            autoMeshListener?.onConnectFailed(AutoFailureReason.OTHER)
                        }
                    })
                } else {
                    log("createGroup: onFailure ${errorName(reason)}")
                    // Final give-up (non-BUSY, or a second BUSY after the retry):
                    // let the controller back off and try again later.
                    autoMeshListener?.onConnectFailed(autoFailureReason(reason))
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (p2p == null) return log("disconnect impossible: Wi-Fi Direct unsupported")
        if (channel == null) return log("disconnect impossible: no channel")
        if (!hasP2pPermission()) {
            log("disconnect blocked: ${requiredPermission().substringAfterLast('.')} not granted")
            return
        }

        closeTransport("user pressed Disconnect")
        _state.update {
            it.copy(
                role = P2pRole.NONE,
                groupFormed = false,
                groupOwnerAddress = null,
                connecting = false,
            )
        }

        removeGroupForCleanup("user pressed Disconnect")
    }

    /**
     * Removes the current P2P group during cleanup (stale group at startup,
     * node stop, user disconnect). Logs the outcome; safe to call when there is
     * no manager/channel/permission.
     */
    @SuppressLint("MissingPermission")
    private fun removeGroupForCleanup(reason: String) {
        val manager = p2p ?: return log("removeGroup skipped ($reason): Wi-Fi Direct unsupported")
        val currentChannel = channel ?: return log("removeGroup skipped ($reason): no channel")
        if (!hasP2pPermission()) {
            log("removeGroup skipped ($reason): permission not granted")
            return
        }

        log("removeGroup() called: $reason")
        manager.removeGroup(currentChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = log("removeGroup: onSuccess ($reason)")
            override fun onFailure(reason: Int) = log("removeGroup: onFailure ${errorName(reason)} ($reason)")
        })
    }

    // --- Wi-Fi Direct callbacks -------------------------------------------

    @SuppressLint("MissingPermission")
    private fun refreshConnectionInfo() {
        val manager = p2p ?: return
        val currentChannel = channel ?: return
        if (!hasP2pPermission()) {
            log("requestConnectionInfo skipped: permission not granted")
            return
        }
        log("requestConnectionInfo() called")
        manager.requestConnectionInfo(currentChannel) { info -> onConnectionInfo(info) }
    }

    @SuppressLint("MissingPermission")
    private fun onP2pStateChanged(intent: Intent) {
        val enabled = intent.getIntExtra(
            WifiP2pManager.EXTRA_WIFI_STATE,
            WifiP2pManager.WIFI_P2P_STATE_DISABLED,
        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED

        _state.update {
            it.copy(p2pEnabled = enabled, peers = if (enabled) it.peers else emptyList())
        }
        log("P2P state changed: enabled=$enabled")
    }

    @SuppressLint("MissingPermission")
    private fun onPeersChanged() {
        log("PEERS_CHANGED broadcast")
        val manager = p2p ?: return
        val currentChannel = channel ?: return
        if (!hasP2pPermission()) {
            log("requestPeers skipped: permission not granted")
            return
        }

        manager.requestPeers(currentChannel) { peers ->
            val list = peers.deviceList.toList()
            log("requestPeers: ${list.size} peer(s)")
            list.forEach { device ->
                log(
                    "  peer: ${device.deviceName.ifEmpty { "(unnamed)" }} " +
                        "${device.deviceAddress} status=${deviceStatus(device.status)}",
                )
            }
            _state.update { it.copy(peers = list) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onConnectionChanged() {
        log("CONNECTION_CHANGED broadcast")
        refreshConnectionInfo()
    }

    @SuppressLint("MissingPermission")
    private fun onThisDeviceChanged(intent: Intent) {
        log("THIS_DEVICE_CHANGED broadcast")
        val device = intent.wifiP2pDeviceExtra() ?: return
        log(
            "  this device: ${device.deviceName} ${device.deviceAddress} " +
                "status=${deviceStatus(device.status)}",
        )
        // Some OEMs report a placeholder MAC; keep the last real address instead.
        if (device.deviceAddress.isNotEmpty() && device.deviceAddress != PEER_PLACEHOLDER_ADDRESS) {
            _selfAddress.value = device.deviceAddress
        }
    }

    private fun onConnectionInfo(info: WifiP2pInfo) {
        val ownerAddress = info.groupOwnerAddress?.hostAddress
        log(
            "requestConnectionInfo: groupFormed=${info.groupFormed} " +
                "isGroupOwner=${info.isGroupOwner} groupOwnerAddress=$ownerAddress",
        )

        // First report after start(): a group that survived from an earlier
        // session is stale — remove it before anything else, unless a live TCP
        // link already uses it (possible when the activity restarts in place).
        if (staleGroupCheckPending) {
            staleGroupCheckPending = false
            if (info.groupFormed && transport.neighbors.value.isEmpty()) {
                log("group already formed at startup with no live link — removing the stale group")
                removeGroupForCleanup("stale group at startup")
                return
            }
        }

        val nowMs = System.currentTimeMillis()

        if (!info.groupFormed) {
            // Only debounce when there is a live link worth protecting; a false
            // report with nothing open can be handled immediately.
            if (hasLiveLink() && !presenceTracker.onReport(false, nowMs)) {
                log(
                    "transient groupFormed=false; keeping the live transport for up to " +
                        "${GroupPresenceTracker.DEFAULT_GRACE_MS}ms",
                )
                scheduleGroupGoneCheck(nowMs)
            } else {
                _state.update {
                    it.copy(role = P2pRole.NONE, groupFormed = false, groupOwnerAddress = null)
                }
                closeTransport("group not formed")
            }
            return
        }

        // A formed-group report cancels any pending "gone" episode and ends a
        // connect request that is still in flight.
        presenceTracker.onReport(true, nowMs)
        groupGoneJob?.cancel()
        groupGoneJob = null
        _state.update { it.copy(connecting = false) }

        logNetworkInterfaces(
            "group formed (role=${if (info.isGroupOwner) "owner" else "client"})",
        )

        if (info.isGroupOwner) {
            _state.update {
                it.copy(
                    role = P2pRole.GROUP_OWNER,
                    groupFormed = true,
                    groupOwnerAddress = ownerAddress,
                )
            }
            connectJob?.cancel()
            connectJob = null
            startListener()
        } else {
            _state.update {
                it.copy(
                    role = P2pRole.CLIENT,
                    groupFormed = true,
                    groupOwnerAddress = ownerAddress,
                )
            }
            if (ownerAddress == null) {
                log("client: group owner address is null, cannot start TCP")
            } else {
                startClient(ownerAddress)
            }
        }
    }

    // --- TCP ownership -----------------------------------------------------

    private fun startListener() {
        if (listening) {
            log("listener already active on port $PORT")
            return
        }
        listening = true
        scope.launch {
            try {
                val bound = transport.listen(PORT)
                if (!listening) {
                    // The group disappeared while the bind was in flight.
                    transport.stop()
                    return@launch
                }
                log("TCP listening on 0.0.0.0:$bound")
            } catch (error: Exception) {
                listening = false
                log(
                    "TCP listen failed on $PORT: " +
                        "${error.javaClass.simpleName}: ${error.message}",
                )
            }
        }
    }

    /**
     * Starts (or restarts) the client's TCP retry loop for [groupOwnerAddress].
     *
     * The loop keeps trying with growing backoff while the group exists and no
     * live link to the owner is registered, so a slow or late-binding owner
     * port does not cost us the connection. It also resumes automatically if an
     * established link drops later. A call for a different address restarts the
     * loop; a duplicate call for the same address is ignored.
     *
     * Every iteration is gated by [ClientConnectDecider]: the loop stops as soon
     * as the group is no longer formed (a confirmed-gone group must not be
     * retried through the normal Wi-Fi interface) and refuses to attempt while
     * no up interface holds a 192.168.49.x address, because such an attempt
     * would leave over wlan0 toward an address that does not exist there.
     */
    private fun startClient(groupOwnerAddress: String) {
        val currentJob = connectJob
        if (currentJob?.isActive == true && connectTarget == groupOwnerAddress) {
            log("client: connect already in progress to $groupOwnerAddress")
            return
        }
        currentJob?.cancel()

        connectTarget = groupOwnerAddress
        connectDecider.reset()
        connectJob = scope.launch {
            // Raw reachability probe first: a plain TCP connect with no HELLO,
            // so "no route / port closed" is separable from protocol problems.
            // Skipped when the p2p link itself is missing — probing would just
            // send SYNs over wlan0 toward an address that does not exist there.
            if (p2pHasMeshAddress()) {
                runReachabilityProbe(groupOwnerAddress)
            } else {
                log("reachability probe skipped: no up interface holds a 192.168.49.x address")
            }

            var attempt = 0
            while (isActive) {
                when (val decision = connectDecider.decide(state.value.groupFormed, p2pHasMeshAddress())) {
                    is ClientConnectDecider.Decision.Stop -> {
                        log("TCP connect loop stopped: group no longer formed")
                        return@launch
                    }

                    is ClientConnectDecider.Decision.Wait -> {
                        delay((decision.untilMs - System.currentTimeMillis()).coerceAtLeast(0L))
                        continue
                    }

                    ClientConnectDecider.Decision.Attempt -> Unit
                }

                // Wait out any live link: skips work while connected and makes
                // the loop resume automatically if the link drops later.
                transport.neighbors.first { it.isEmpty() }

                attempt += 1
                logNetworkInterfaces("before connect attempt $attempt")
                log("TCP connect attempt $attempt to $groupOwnerAddress:$PORT")
                val outcome = try {
                    transport.connect(groupOwnerAddress, PORT)
                } catch (error: CancellationException) {
                    // A cancelled attempt is not a failure; let the coroutine die
                    // quietly instead of logging "failed ... retrying".
                    throw error
                } catch (error: Exception) {
                    log(
                        "TCP connect attempt $attempt failed to start: " +
                            "${error.javaClass.simpleName}: ${error.message}",
                    )
                    ConnectOutcome.Failure(
                        phase = ConnectPhase.CONNECT,
                        exceptionClass = error.javaClass.simpleName,
                        message = error.message,
                        elapsedMs = 0L,
                    )
                }

                when (outcome) {
                    is ConnectOutcome.Success -> {
                        connectDecider.onSuccess()
                        log("TCP connected to $groupOwnerAddress:$PORT neighbor=${outcome.remoteNodeId}")
                    }

                    is ConnectOutcome.Failure -> {
                        if (!isActive) {
                            // The loop was cancelled while the attempt ran; report
                            // the outcome without claiming a retry that will never
                            // happen.
                            log(
                                "TCP connect attempt $attempt failed in ${outcome.phase} phase after " +
                                    "${outcome.elapsedMs}ms: ${outcome.exceptionClass}: ${outcome.message}; " +
                                    "loop cancelled, not retrying",
                            )
                        } else {
                            val delayMs = connectDecider.recordFailure()
                            log(
                                "TCP connect attempt $attempt failed in ${outcome.phase} phase after " +
                                    "${outcome.elapsedMs}ms: ${outcome.exceptionClass}: ${outcome.message}; " +
                                    "retrying in ${delayMs}ms",
                            )
                            if (outcome.phase == ConnectPhase.CONNECT && outcome.exceptionClass == "SocketTimeoutException") {
                                // A timeout means packets are dropped, not refused — the
                                // Local Network Protection signature when a required
                                // permission is missing. Log which ones are held.
                                log("connect timed out; current permission states: ${describePermissionStates()}")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Manual retry for the client role: restarts the TCP retry loop with a fresh
     * backoff schedule. No-op unless this device is a group client in a formed group.
     */
    fun retryConnect() {
        val snapshot = state.value
        if (snapshot.role != P2pRole.CLIENT || !snapshot.groupFormed) {
            log("retry ignored: role=${snapshot.role} groupFormed=${snapshot.groupFormed}")
            return
        }
        val address = snapshot.groupOwnerAddress ?: run {
            log("retry impossible: no group owner address")
            return
        }
        log("manual TCP retry requested")
        startClient(address)
    }

    /** True when a live TCP link exists that a false report must not tear down. */
    private fun hasLiveLink(): Boolean = listening || transport.neighbors.value.isNotEmpty()

    /**
     * True when some up interface holds an address in the P2P group subnet, i.e.
     * the Wi-Fi Direct link is actually present on this device. The connect loop
     * refuses to attempt without it: a connect to the owner IP would otherwise
     * leave over wlan0 toward an address that does not exist there.
     */
    private fun p2pHasMeshAddress(): Boolean = runCatching {
        NetworkDiagnostics.hasGroupAddress(NetworkDiagnostics.enumerate())
    }.getOrDefault(false)

    /**
     * Schedules the one-shot check that fires at the end of the pending grace
     * period and closes the transport if "group gone" is still unrefuted. A
     * second false report while the job is pending does not restart it: the
     * window stays anchored to the first false report.
     */
    private fun scheduleGroupGoneCheck(nowMs: Long) {
        if (groupGoneJob?.isActive == true) return // this episode is already watched
        val remaining = presenceTracker.pendingGraceRemaining(nowMs) ?: GroupPresenceTracker.DEFAULT_GRACE_MS
        groupGoneJob = scope.launch {
            delay(remaining)
            if (!presenceTracker.onReport(false, System.currentTimeMillis())) return@launch
            _state.update {
                it.copy(role = P2pRole.NONE, groupFormed = false, groupOwnerAddress = null)
            }
            closeTransport("group confirmed gone after ${GroupPresenceTracker.DEFAULT_GRACE_MS}ms")
        }
    }

    private fun closeTransport(reason: String) {
        connectJob?.cancel()
        connectJob = null
        groupGoneJob?.cancel()
        groupGoneJob = null
        presenceTracker.reset()
        listening = false
        transport.stop()
        log("transport closed: $reason")
    }

    /**
     * Plain TCP connect to the owner port with no HELLO exchange. If this fails
     * while a later real attempt succeeds, the problem was timing; if both fail,
     * the socket path itself (route/interface) is suspect. The server treats it
     * as an ordinary connection that closes before any frame arrives.
     */
    private fun runReachabilityProbe(host: String) {
        val startedAt = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, PORT), PROBE_TIMEOUT_MS.toInt())
            }
            log(
                "reachability probe to $host:$PORT succeeded in " +
                    "${System.currentTimeMillis() - startedAt}ms",
            )
        } catch (error: Exception) {
            log(
                "reachability probe to $host:$PORT failed after " +
                    "${System.currentTimeMillis() - startedAt}ms: " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    /** Dumps every network interface (flagging p2p / 192.168.49.x) into the event log. */
    private fun logNetworkInterfaces(context: String) {
        runCatching { NetworkDiagnostics.enumerate() }
            .onSuccess { interfaces ->
                log("interfaces ($context):")
                NetworkDiagnostics.describe(interfaces).forEach { line -> log("  $line") }
            }
            .onFailure { error ->
                log("interface dump failed: ${error.javaClass.simpleName}: ${error.message}")
            }
    }

    // --- Helpers ----------------------------------------------------------

    /** The app's target SDK. The field is deprecated on API 37 but works everywhere. */
    @Suppress("DEPRECATION")
    private val targetSdkVersion: Int get() = appContext.applicationInfo.targetSdkVersion

    private fun errorName(code: Int): String = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.ERROR -> "ERROR"
        else -> "UNKNOWN($code)"
    }

    /** BUSY gets one special retry in the controller; everything else is a plain failure. */
    private fun autoFailureReason(code: Int): AutoFailureReason =
        if (code == WifiP2pManager.BUSY) AutoFailureReason.BUSY else AutoFailureReason.OTHER

    private fun deviceStatus(status: Int): String = when (status) {
        WifiP2pDevice.AVAILABLE -> "AVAILABLE"
        WifiP2pDevice.INVITED -> "INVITED"
        WifiP2pDevice.CONNECTED -> "CONNECTED"
        WifiP2pDevice.FAILED -> "FAILED"
        WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN($status)"
    }

    @Suppress("DEPRECATION")
    private fun Intent.wifiP2pDeviceExtra(): WifiP2pDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
        } else {
            getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
        }

    private fun log(message: String) {
        eventLog.log("P2P: $message")
    }
}
