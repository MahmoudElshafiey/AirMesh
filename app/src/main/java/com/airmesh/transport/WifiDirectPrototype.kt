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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * THROWAWAY PROTOTYPE — verifies that two real phones can form a Wi-Fi Direct
 * group and exchange plain text over TCP. It is intentionally NOT connected to
 * Frame/FrameCodec; delete this package's prototype files once the real
 * transport is written.
 */
data class PrototypeState(
    val permissionsGranted: Boolean = false,
    val p2pEnabled: Boolean? = null,
    val peers: List<WifiP2pDevice> = emptyList(),
    val role: String = "IDLE",
    val groupOwnerAddress: String? = null,
    val tcpConnected: Boolean = false,
    val messages: List<String> = emptyList(),
    val log: List<String> = emptyList(),
)

class WifiDirectPrototype(context: Context) {

    companion object {
        /** Fixed TCP port opened by the group owner. */
        const val PORT = 8988
        private const val MAX_LOG_LINES = 4000
        private const val CONNECT_ATTEMPTS = 5
        private const val CONNECT_RETRY_DELAY_MS = 1_000L
    }

    private val appContext = context.applicationContext
    private val p2p: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _state = MutableStateFlow(PrototypeState())
    val state: StateFlow<PrototypeState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var readJob: Job? = null
    private var receiverRegistered = false
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> onP2pStateChanged(intent)
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeersChanged()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged()
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> onThisDeviceChanged(intent)
                else -> log("Unknown broadcast: ${intent.action}")
            }
        }
    }

    init {
        log("Prototype created (Android SDK ${Build.VERSION.SDK_INT})")
        if (p2p == null) {
            log("WifiP2pManager unavailable — this device has no Wi-Fi Direct support")
        } else {
            try {
                channel = p2p.initialize(appContext, Looper.getMainLooper()) {
                    log("Channel disconnected by the system")
                }
                log("WifiP2pManager.initialize() done")
            } catch (e: Exception) {
                log("initialize failed: ${e.javaClass.simpleName}: ${e.message}")
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
                log("BroadcastReceiver registered")
            } catch (e: Exception) {
                log("registerReceiver failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        log("Prototype started")
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            val locationManager = appContext.getSystemService(LocationManager::class.java)
            log(
                "Location services enabled=${locationManager?.isLocationEnabled} " +
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
                log("BroadcastReceiver unregistered")
            } catch (e: IllegalArgumentException) {
                log("unregisterReceiver failed: ${e.message}")
            }
            receiverRegistered = false
        }

        closeConnection("activity stopped")
        log("Prototype stopped (sockets closed, connection intentionally dropped)")
    }

    fun destroy() {
        stop()
        scope.cancel()
        channel?.close()
        channel = null
        log("Prototype destroyed")
    }

    // --- Permissions -------------------------------------------------------

    /** The runtime permission Wi-Fi Direct needs on this Android version. */
    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permissionsGranted = granted) }
        val name = requiredPermission().substringAfterLast('.')
        log("Runtime permission $name granted=$granted")
        if (granted) refreshConnectionInfo()
    }

    private fun hasP2pPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED

    // --- Wi-Fi Direct actions ---------------------------------------------

    @SuppressLint("MissingPermission")
    fun discover() {
        val manager = p2p ?: return log("Discover impossible: Wi-Fi Direct unsupported")
        val channel = channel ?: return log("Discover impossible: no WifiP2p channel")
        if (!hasP2pPermission()) {
            log("Discover blocked: ${requiredPermission().substringAfterLast('.')} not granted")
            return
        }

        log("discoverPeers() called")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = log("discoverPeers: onSuccess (discovery started)")
            override fun onFailure(reason: Int) = log("discoverPeers: onFailure ${errorName(reason)}")
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        val manager = p2p ?: return log("Connect impossible: Wi-Fi Direct unsupported")
        val channel = channel ?: return log("Connect impossible: no WifiP2p channel")
        if (!hasP2pPermission()) {
            log("Connect blocked: ${requiredPermission().substringAfterLast('.')} not granted")
            return
        }

        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        log("connect() requested to ${device.deviceName} (${device.deviceAddress})")
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = log("connect: onSuccess (waiting for group formation)")
            override fun onFailure(reason: Int) = log("connect: onFailure ${errorName(reason)}")
        })
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val manager = p2p ?: return log("Disconnect impossible: Wi-Fi Direct unsupported")
        val channel = channel ?: return log("Disconnect impossible: no WifiP2p channel")
        if (!hasP2pPermission()) {
            log("Disconnect blocked: ${requiredPermission().substringAfterLast('.')} not granted")
            return
        }

        closeConnection("user pressed Disconnect")
        log("removeGroup() called")
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = log("removeGroup: onSuccess")
            override fun onFailure(reason: Int) = log("removeGroup: onFailure ${errorName(reason)}")
        })
    }

    @SuppressLint("MissingPermission")
    private fun refreshConnectionInfo() {
        val manager = p2p ?: return
        val channel = channel ?: return
        if (!hasP2pPermission()) {
            log("requestConnectionInfo skipped: permission not granted")
            return
        }
        log("requestConnectionInfo() called")
        manager.requestConnectionInfo(channel) { info -> onConnectionInfo(info) }
    }

    @SuppressLint("MissingPermission")
    private fun onP2pStateChanged(intent: Intent) {
        val enabled = intent.getIntExtra(
            WifiP2pManager.EXTRA_WIFI_STATE,
            WifiP2pManager.WIFI_P2P_STATE_DISABLED,
        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED

        _state.update { it.copy(p2pEnabled = enabled, peers = if (enabled) it.peers else emptyList()) }
        log("P2P state changed: enabled=$enabled")
    }

    @SuppressLint("MissingPermission")
    private fun onPeersChanged() {
        log("PEERS_CHANGED broadcast")
        val manager = p2p ?: return
        val channel = channel ?: return
        if (!hasP2pPermission()) {
            log("requestPeers skipped: permission not granted")
            return
        }

        manager.requestPeers(channel) { peers ->
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
        val goAddress = info.groupOwnerAddress?.hostAddress
        log(
            "requestConnectionInfo: groupFormed=${info.groupFormed} " +
                "isGroupOwner=${info.isGroupOwner} groupOwnerAddress=$goAddress",
        )

        if (!info.groupFormed) {
            _state.update {
                it.copy(role = "IDLE", groupOwnerAddress = null, tcpConnected = false)
            }
            closeConnection("group not formed")
            return
        }

        if (info.isGroupOwner) {
            _state.update { it.copy(role = "GROUP OWNER", groupOwnerAddress = goAddress) }
            startServer()
        } else {
            _state.update { it.copy(role = "CLIENT", groupOwnerAddress = goAddress) }
            if (goAddress == null) {
                log("Client: group owner address is null, cannot start TCP")
            } else {
                connectClient(goAddress)
            }
        }
    }

    // --- TCP --------------------------------------------------------------

    private fun startServer() {
        if (serverJob?.isActive == true) {
            log("Server already running on port $PORT")
            return
        }
        if (socket?.isConnected == true) {
            log("Server: socket already connected")
            return
        }

        serverJob = scope.launch {
            try {
                val server = ServerSocket(PORT)
                serverSocket = server
                log("Server listening on 0.0.0.0:$PORT")
                while (isActive) {
                    val client = server.accept()
                    log(
                        "Server accepted ${client.inetAddress?.hostAddress}:${client.port}",
                    )
                    attachSocket(client)
                }
            } catch (e: Exception) {
                if (isActive) log("Server error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun connectClient(groupOwnerAddress: String) {
        if (socket?.isConnected == true) {
            log("Client: already connected to $groupOwnerAddress:$PORT")
            return
        }
        if (clientJob?.isActive == true) {
            log("Client: connect already in progress")
            return
        }

        clientJob = scope.launch {
            var lastError: String? = null
            for (attempt in 1..CONNECT_ATTEMPTS) {
                if (!isActive) return@launch
                log("TCP connect attempt $attempt/$CONNECT_ATTEMPTS to $groupOwnerAddress:$PORT")
                try {
                    val client = Socket()
                    client.connect(InetSocketAddress(groupOwnerAddress, PORT), 3_000)
                    log("TCP connected to $groupOwnerAddress:$PORT")
                    attachSocket(client)
                    return@launch
                } catch (e: Exception) {
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    log("TCP connect attempt $attempt failed: $lastError")
                    delay(CONNECT_RETRY_DELAY_MS)
                }
            }
            log("TCP connect gave up after $CONNECT_ATTEMPTS attempts: $lastError")
        }
    }

    private fun attachSocket(newSocket: Socket) {
        readJob?.cancel()
        readJob = null
        try {
            socket?.close()
        } catch (_: Exception) {
            // Old socket is being replaced.
        }
        socket = newSocket
        _state.update { it.copy(tcpConnected = true) }

        readJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
                while (isActive) {
                    val line = reader.readLine() ?: break
                    log("[RX] $line")
                    _state.update { it.copy(messages = it.messages + "RX: $line") }
                }
                log("Read loop ended: peer closed the socket")
            } catch (e: Exception) {
                if (isActive) log("Read error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                if (socket === newSocket) {
                    socket = null
                    _state.update { it.copy(tcpConnected = false) }
                    log("TCP disconnected")
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return

        val current = socket
        if (current == null || current.isClosed) {
            log("Send failed: no TCP connection")
            return
        }

        scope.launch {
            try {
                PrintWriter(current.getOutputStream(), true).println(message)
                log("[TX] $message")
                _state.update { it.copy(messages = it.messages + "TX: $message") }
            } catch (e: Exception) {
                log("Send failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun closeConnection(reason: String) {
        val wasActive = socket != null || serverSocket != null ||
            serverJob?.isActive == true || clientJob?.isActive == true ||
            readJob?.isActive == true

        serverJob?.cancel()
        serverJob = null
        clientJob?.cancel()
        clientJob = null
        readJob?.cancel()
        readJob = null

        try {
            socket?.close()
        } catch (_: Exception) {
            // Nothing to do.
        }
        socket = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {
            // Nothing to do.
        }
        serverSocket = null

        _state.update { it.copy(tcpConnected = false) }
        if (wasActive) log("Connection closed: $reason")
    }

    fun clearLog() {
        _state.update { it.copy(log = emptyList(), messages = emptyList()) }
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
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$time  $message"
        _state.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }
}
