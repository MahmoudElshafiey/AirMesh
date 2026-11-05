package com.airmesh.transport

import android.Manifest
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Role this device currently has in the Wi-Fi Direct group. */
enum class P2pRole {
    NONE,
    GROUP_OWNER,
    CLIENT,
}

/** UI-facing snapshot of the Wi-Fi Direct side. */
data class ConnectorState(
    val permissionsGranted: Boolean = false,
    val p2pEnabled: Boolean? = null,
    val peers: List<WifiP2pDevice> = emptyList(),
    val role: P2pRole = P2pRole.NONE,
    val groupFormed: Boolean = false,
    val groupOwnerAddress: String? = null,
)

/**
 * Wi-Fi Direct discovery/group management for the real mesh.
 *
 * This is deliberately separate from the throwaway prototype so the prototype
 * keeps working unchanged. Instead of exchanging raw text, it hands the TCP
 * layer to [TcpMeshTransport]:
 *
 * - group owner: `transport.listen(PORT)`
 * - client: `transport.connect(groupOwnerAddress, PORT)` with a few retries,
 *   because the owner may not have bound the port yet.
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

        private const val CONNECT_ATTEMPTS = 5

        /** Timeout for the raw reachability probe before HELLO attempts start. */
        private const val PROBE_TIMEOUT_MS = 5_000L
    }

    private val appContext = context.applicationContext
    private val p2p: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _state = MutableStateFlow(ConnectorState())
    val state: StateFlow<ConnectorState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var receiverRegistered = false
    private var started = false
    private var listening = false
    private var connectJob: Job? = null

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permissionsGranted = granted) }
        val name = requiredPermission().substringAfterLast('.')
        log("runtime permission $name granted=$granted")
        if (granted) refreshConnectionInfo()
    }

    private fun hasP2pPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED

    // --- Wi-Fi Direct actions ---------------------------------------------

    @SuppressLint("MissingPermission")
    fun discover() {
        val manager = p2p ?: return log("discover impossible: Wi-Fi Direct unsupported")
        val currentChannel = channel ?: return log("discover impossible: no channel")
        if (!hasP2pPermission()) {
            log("discover blocked: ${requiredPermission().substringAfterLast('.')} not granted")
            return
        }

        log("discoverPeers() called")
        manager.discoverPeers(currentChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = log("discoverPeers: onSuccess (discovery started)")
            override fun onFailure(reason: Int) = log("discoverPeers: onFailure ${errorName(reason)}")
        })
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
        manager.connect(currentChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = log("connect: onSuccess (waiting for group formation)")
            override fun onFailure(reason: Int) = log("connect: onFailure ${errorName(reason)}")
        })
    }

    @SuppressLint("MissingPermission")
    fun createGroup() {
        val manager = p2p ?: return log("createGroup impossible: Wi-Fi Direct unsupported")
        val currentChannel = channel ?: return log("createGroup impossible: no channel")
        if (!hasP2pPermission()) {
            log("createGroup blocked: ${requiredPermission().substringAfterLast('.')} not granted")
            return
        }

        log("createGroup() called")
        manager.createGroup(currentChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("createGroup: onSuccess")
                refreshConnectionInfo()
            }

            override fun onFailure(reason: Int) = log("createGroup: onFailure ${errorName(reason)}")
        })
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val manager = p2p ?: return log("disconnect impossible: Wi-Fi Direct unsupported")
        val currentChannel = channel ?: return log("disconnect impossible: no channel")
        if (!hasP2pPermission()) {
            log("disconnect blocked: ${requiredPermission().substringAfterLast('.')} not granted")
            return
        }

        closeTransport("user pressed Disconnect")
        _state.update {
            it.copy(role = P2pRole.NONE, groupFormed = false, groupOwnerAddress = null)
        }

        log("removeGroup() called")
        manager.removeGroup(currentChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = log("removeGroup: onSuccess")
            override fun onFailure(reason: Int) = log("removeGroup: onFailure ${errorName(reason)}")
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
    }

    private fun onConnectionInfo(info: WifiP2pInfo) {
        val ownerAddress = info.groupOwnerAddress?.hostAddress
        log(
            "requestConnectionInfo: groupFormed=${info.groupFormed} " +
                "isGroupOwner=${info.isGroupOwner} groupOwnerAddress=$ownerAddress",
        )

        if (!info.groupFormed) {
            _state.update {
                it.copy(role = P2pRole.NONE, groupFormed = false, groupOwnerAddress = null)
            }
            closeTransport("group not formed")
            return
        }

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

    private fun startClient(groupOwnerAddress: String) {
        if (connectJob?.isActive == true) {
            log("client: connect already in progress to $groupOwnerAddress")
            return
        }

        connectJob = scope.launch {
            // Raw reachability probe first: a plain TCP connect with no HELLO,
            // so "no route / port closed" is separable from protocol problems.
            runReachabilityProbe(groupOwnerAddress)

            for (attempt in 1..CONNECT_ATTEMPTS) {
                if (!isActive) return@launch
                if (transport.neighbors.value.isNotEmpty()) {
                    log("TCP already connected (neighbors=${transport.neighbors.value})")
                    return@launch
                }

                logNetworkInterfaces("before connect attempt $attempt")
                log("TCP connect attempt $attempt/$CONNECT_ATTEMPTS to $groupOwnerAddress:$PORT")
                val outcome = try {
                    transport.connect(groupOwnerAddress, PORT)
                } catch (error: Exception) {
                    log(
                        "TCP connect attempt $attempt failed to start: " +
                            "${error.javaClass.simpleName}: ${error.message}",
                    )
                    continue
                }
                when (outcome) {
                    is ConnectOutcome.Success -> {
                        log("TCP connected to $groupOwnerAddress:$PORT neighbor=${outcome.remoteNodeId}")
                        return@launch
                    }

                    is ConnectOutcome.Failure -> log(
                        "TCP connect attempt $attempt failed in ${outcome.phase} phase after " +
                            "${outcome.elapsedMs}ms: ${outcome.exceptionClass}: ${outcome.message}",
                    )
                }
            }
            log("TCP connect gave up after $CONNECT_ATTEMPTS attempts")
        }
    }

    private fun closeTransport(reason: String) {
        connectJob?.cancel()
        connectJob = null
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

    private fun errorName(code: Int): String = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.ERROR -> "ERROR"
        else -> "UNKNOWN($code)"
    }

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
