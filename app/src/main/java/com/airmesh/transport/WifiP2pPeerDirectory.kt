package com.airmesh.transport

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [PeerDirectory] over Wi-Fi Direct DNS-SD, using the NSD-style WifiP2pManager
 * API (initialize/addLocalService/addServiceRequest/discoverServices — the
 * classic registerService/requestServices methods were removed in API 37). We
 * publish one ad per node: the token rides both as the TXT record entry
 * [TXT_AD_KEY] and as the instance name. All scan issuance (the ~60 s service
 * refresh cycle, manual peer scans) goes through a [DiscoveryCoordinator], so a
 * classic discoverPeers pass can never cancel an ongoing DNS-SD scan; peers that
 * stop being seen are swept after [PEER_STALE_MS] (this API has no removal
 * callback). Only devices advertising [AirMeshAdCodec.SERVICE_TYPE] with a
 * decodable token appear in [peers], so foreign Wi-Fi Direct peers never leak
 * into the list.
 *
 * All methods must be called on the main thread: the channel is bound to the
 * main looper, matching how WifiDirectConnector uses its own channel. [stop] is
 * terminal — it closes the channel, like WifiDirectConnector.destroy().
 */
class WifiP2pPeerDirectory(
    context: Context,
    /** Our mesh id; the ad carries it instead of a MAC. */
    private val myNodeId: Int,
    /** Our process-lifetime election token; advertised in the TXT record. */
    private val tieBreakToken: Int,
    private val coordinator: DiscoveryCoordinator,
    private val log: (String) -> Unit = {},
) : PeerDirectory {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private var channel: WifiP2pManager.Channel? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    private val _peers = MutableStateFlow<List<AirMeshPeer>>(emptyList())
    override val peers: StateFlow<List<AirMeshPeer>> = _peers.asStateFlow()

    /** The owner flag of the ad currently registered; null when not advertising. */
    private var advertisedOwnerFlag: Boolean? = null
    /** The service info we registered, so removeLocalService can match it. */
    private var serviceInfo: WifiP2pDnsSdServiceInfo? = null
    private val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(AirMeshAdCodec.SERVICE_TYPE)

    private var discovering = false
    /** When each peer was last seen; entries older than [PEER_STALE_MS] are swept. */
    private val lastSeen = mutableMapOf<String, Long>()
    private var loopJob: Job? = null

    /** Callback/accept/ignore counters, reported once per [STATS_INTERVAL_MS]. */
    private val stats = DnsSdStats()
    private var lastStatsMs = 0L

    private val serviceListener = object : WifiP2pManager.DnsSdServiceResponseListener {
        override fun onDnsSdServiceAvailable(serviceType: String, instanceName: String, device: WifiP2pDevice) {
            // First statement, before any filtering: log what the framework actually delivered.
            log(
                "dnssd: raw service instance='$instanceName' type='$serviceType' " +
                    "src=${device?.deviceAddress ?: "(no address)"} " +
                    "name=${device?.deviceName ?: "(no name)"}",
            )
            stats.onRawService()
            log("dnssd: service available '$instanceName' from ${device?.deviceAddress ?: "(no address)"}")
            // The token doubles as the instance name; the TXT callback refines it.
            upsertPeer(AirMeshAdCodec.decode(instanceName), device)
        }
    }

    private val txtListener = object : WifiP2pManager.DnsSdTxtRecordListener {
        override fun onDnsSdTxtRecordAvailable(
            serviceType: String,
            txtRecord: Map<String, String>,
            device: WifiP2pDevice,
        ) {
            // First statement, before any filtering: log what the framework actually delivered.
            log(
                "dnssd: raw txt fullDomain='$serviceType' map=$txtRecord " +
                    "src=${device?.deviceAddress ?: "(no address)"}",
            )
            stats.onRawTxt()
            log("dnssd: TXT record $txtRecord from ${device?.deviceAddress ?: "(no address)"}")
            upsertPeer(AirMeshAdCodec.decode(txtRecord[TXT_AD_KEY]), device)
        }
    }

    init {
        if (manager == null) {
            log("dnssd: WifiP2pManager unavailable — this device has no Wi-Fi Direct support")
        } else {
            try {
                channel = manager.initialize(appContext, Looper.getMainLooper()) {
                    log("dnssd: P2P channel disconnected by the system")
                }
                // One pair of listeners for the lifetime of the channel. Both
                // callbacks carry the peer's WifiP2pDevice, so we get its MAC
                // without a TXT round-trip.
                manager.setDnsSdResponseListeners(channel!!, serviceListener, txtListener)
                log("dnssd: response listeners registered (service + TXT)")
            } catch (error: Exception) {
                log("dnssd: initialize failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    override fun startAdvertising(isGroupOwner: Boolean) {
        val ch = channel ?: return log("dnssd: cannot advertise — no P2P channel")
        if (advertisedOwnerFlag == isGroupOwner && serviceInfo != null) return // already advertising this role
        removeLocalServiceQuietly(ch)

        // No own MAC needed: the ad carries node id + token, and peers are addressed
        // by the source device of the DNS-SD callback.
        val token = AirMeshAdCodec.encode(myNodeId, tieBreakToken, isGroupOwner)
        log("dnssd: addLocalService instance='$token' txt={${TXT_AD_KEY}=$token}")
        val info = WifiP2pDnsSdServiceInfo.newInstance(
            AirMeshAdCodec.SERVICE_TYPE,
            token,                     // instance name: the token on every version
            mapOf(TXT_AD_KEY to token), // TXT record: same token under one key
        )
        advertisedOwnerFlag = isGroupOwner
        serviceInfo = info
        manager?.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("dnssd: addLocalService ok (owner=$isGroupOwner)")
            }

            override fun onFailure(reason: Int) {
                advertisedOwnerFlag = null // allow a later startAdvertising to retry
                serviceInfo = null
                log("dnssd: addLocalService failed reason=$reason")
            }
        })
        ensureLoop()
    }

    override fun setDiscovering(enabled: Boolean) {
        if (discovering == enabled) return
        discovering = enabled
        if (!enabled) {
            coordinator.stopService()
            return
        }
        // The loop's first tick issues the full cycle through the coordinator, so a
        // classic peer scan and this service scan can never overlap on the radio.
        ensureLoop()
    }

    /** One 5 s-slice loop: let the coordinator drive scans and sweep stale peers. */
    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                val nowMs = System.currentTimeMillis()
                // The coordinator owns all scan issuance: it ends stale manual scans,
                // resumes the service cycle when wanted, and re-issues every ~60 s.
                coordinator.tick(nowMs, discovering)
                coordinator.refreshIfDue(nowMs)
                sweepStalePeers(nowMs)
                reportStatsIfDue(nowMs)
                delay(CHECK_SLICE_MS)
            }
        }
    }

    // --- Raw DNS-SD operations ---------------------------------------------------
    // Called only through the [DiscoveryCoordinator] so a classic peer scan and a
    // service scan never overlap on the radio.

    fun clearServiceRequestsRaw() {
        val ch = channel ?: return
        try {
            manager?.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = log("dnssd: clearServiceRequests ok")

                override fun onFailure(reason: Int) = log("dnssd: clearServiceRequests failed reason=$reason")
            })
        } catch (error: Exception) {
            log("dnssd: clearServiceRequests failed: ${error.message}")
        }
    }

    fun addServiceRequestRaw() {
        val ch = channel ?: return
        log("dnssd: addServiceRequest(${AirMeshAdCodec.SERVICE_TYPE})")
        try {
            manager?.addServiceRequest(ch, serviceRequest, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = log("dnssd: addServiceRequest ok")

                override fun onFailure(reason: Int) = log("dnssd: addServiceRequest failed reason=$reason")
            })
        } catch (error: Exception) {
            log("dnssd: adding service request failed: ${error.message}")
        }
    }

    fun startServiceScanRaw() {
        val ch = channel ?: return
        try {
            // discoverServices is a toggle (like discoverPeers): re-issuing it
            // restarts the scan, which is exactly what we want before Android's
            // ~2 min discovery timeout kills the previous one.
            log("dnssd: discoverServices() issued")
            stats.onScan()
            manager?.discoverServices(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = log("dnssd: discoverServices ok (scan started)")

                override fun onFailure(reason: Int) = log("dnssd: discoverServices failed reason=$reason")
            })
        } catch (error: Exception) {
            log("dnssd: service discovery failed: ${error.message}")
        }
    }

    private fun upsertPeer(ad: AirMeshAd?, device: WifiP2pDevice?) {
        if (ad == null) {
            // A DNS-SD hit that is not a well-formed AirMesh token; ignore it.
            stats.onIgnored()
            log("dnssd: ignored: no AirMesh TXT")
            return
        }
        if (ad.nodeId == myNodeId) {
            // Our own advertisement bouncing back; never a peer.
            stats.onIgnored()
            log("dnssd: ignored own ad (#${ad.nodeId})")
            return
        }
        val address = device?.deviceAddress
        if (address.isNullOrEmpty() || address == PEER_PLACEHOLDER_ADDRESS) {
            // A peer we can see but never connect to must not block our own creation.
            stats.onIgnored()
            log("dnssd: ignored #${ad.nodeId} — no usable source device address")
            return
        }
        lastSeen[address] = System.currentTimeMillis()
        _peers.update { current ->
            val peer = AirMeshPeer(address, ad.nodeId, ad.isGroupOwner, ad.tieBreakToken)
            (current.filterNot { it.deviceAddress == address } + peer).sortedBy { it.nodeId }
        }
        stats.onAccepted()
        log("dnssd: accepted AirMesh peer #${ad.nodeId} owner=${if (ad.isGroupOwner) 1 else 0} ($address)")
    }

    /** Print one counters line per [STATS_INTERVAL_MS], then start a new window. */
    private fun reportStatsIfDue(nowMs: Long) {
        if (lastStatsMs == 0L) {
            // First tick starts the window here, so the first line covers a full minute.
            lastStatsMs = nowMs
            return
        }
        if (nowMs - lastStatsMs < STATS_INTERVAL_MS) return
        lastStatsMs = nowMs
        log(stats.statsLine())
        stats.reset()
    }

    private fun sweepStalePeers(nowMs: Long) {
        // The NSD-style API has no "service removed" callback, so a phone that
        // left range simply stops being re-seen; drop it after [PEER_STALE_MS].
        val stale = lastSeen.filterValues { shouldExpirePeer(it, nowMs) }.keys
        if (stale.isEmpty()) return
        lastSeen.keys.removeAll(stale)
        _peers.update { current -> current.filterNot { it.deviceAddress in stale } }
    }

    override fun stop() {
        discovering = false
        coordinator.stopService()
        channel?.let { ch ->
            serviceInfo?.let { info ->
                try { manager?.removeLocalService(ch, info, null) } catch (_: Exception) {}
            }
            // Terminal, like WifiDirectConnector.destroy(): the channel is closed.
            try { ch.close() } catch (_: Exception) {}
        }
        channel = null
        advertisedOwnerFlag = null
        serviceInfo = null
        lastSeen.clear()
        _peers.value = emptyList()
        loopJob?.cancel()
        loopJob = null
    }

    private fun removeLocalServiceQuietly(ch: WifiP2pManager.Channel) {
        val info = serviceInfo ?: return
        try { manager?.removeLocalService(ch, info, null) } catch (_: Exception) {}
        serviceInfo = null
    }

    companion object {
        /** The single TXT record key carrying the full ad token. */
        const val TXT_AD_KEY = "ad"

        /** How often the loop wakes; discovery re-issue is gated by [shouldRefreshDiscovery]. */
        private const val CHECK_SLICE_MS = 5_000L

        /** How often one counters line is logged (each line covers only this window). */
        private const val STATS_INTERVAL_MS = 60_000L
    }
}
