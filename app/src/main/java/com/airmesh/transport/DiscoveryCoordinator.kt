package com.airmesh.transport

/**
 * The raw scan operations the coordinator serializes. In production these delegate
 * to WifiP2pPeerDirectory (DNS-SD) and WifiDirectConnector (classic discovery);
 * tests use a fake that records call order.
 */
interface DiscoveryBackend {
    /** Remove all DNS-SD service requests on our channel. */
    fun clearServiceRequests()

    /** Register the AirMesh service request so discoverServices has something to scan for. */
    fun addServiceRequest()

    /** (Re)start a DNS-SD service scan; Android stops it after about two minutes. */
    fun discoverServices()

    /** Classic Wi-Fi Direct peer scan (discoverPeers); cancels any ongoing DNS-SD scan. */
    fun discoverPeers()
}

/**
 * Serializes every P2P scan on the radio: discoverPeers cancels an ongoing DNS-SD
 * service scan (and vice versa), so the two must never overlap. The auto-mesh
 * 60 s refresh cycle runs here; a manual Discover tap takes over the radio for
 * [PEER_SCAN_WINDOW_MS], after which the service cycle resumes if still wanted.
 * Pure Kotlin — no Android imports, injectable clock, unit-testable on the JVM.
 */
class DiscoveryCoordinator(
    private val backend: DiscoveryBackend,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Last time a full service cycle (clear → add → scan) was issued; null when none is active. */
    var lastCycleAtMs: Long? = null
        private set

    /** True while a DNS-SD service scan holds the radio. */
    var serviceDiscoveryActive: Boolean = false
        private set

    /** When the manual peer scan started; null when it is not running. */
    var peerScanStartedAtMs: Long? = null
        private set

    val peerScanActive: Boolean get() = peerScanStartedAtMs != null

    /** Issue one full service cycle in order: clear, add request, start the scan. */
    fun issueServiceCycle() {
        backend.clearServiceRequests()
        backend.addServiceRequest()
        backend.discoverServices()
        lastCycleAtMs = clock()
        serviceDiscoveryActive = true
    }

    /** Re-issue the cycle when [DISCOVERY_REFRESH_MS] has passed since the last one. */
    fun refreshIfDue(nowMs: Long): Boolean {
        val last = lastCycleAtMs ?: return false
        if (!serviceDiscoveryActive || !shouldRefreshDiscovery(last, nowMs)) return false
        issueServiceCycle()
        return true
    }

    /**
     * Manual Discover tap: take over the radio. Clear any active service scan first
     * (discoverPeers would cancel it anyway), then start a classic peer scan that
     * holds the radio for [PEER_SCAN_WINDOW_MS].
     */
    fun requestPeerScan() {
        if (serviceDiscoveryActive) backend.clearServiceRequests()
        serviceDiscoveryActive = false
        lastCycleAtMs = null // after the window, resume with a fresh full cycle
        peerScanStartedAtMs = clock()
        backend.discoverPeers()
    }

    /**
     * Called every loop slice: end an expired manual scan and, if auto-mesh still
     * wants service discovery, resume the cycle (immediately when none is active).
     */
    fun tick(nowMs: Long, serviceWanted: Boolean) {
        val started = peerScanStartedAtMs
        if (started != null && nowMs - started >= PEER_SCAN_WINDOW_MS) {
            peerScanStartedAtMs = null
        }
        if (serviceWanted && !peerScanActive && !serviceDiscoveryActive) issueServiceCycle()
    }

    /** Stop the service scan entirely (auto-mesh paused, or the directory stopped). */
    fun stopService() {
        backend.clearServiceRequests()
        serviceDiscoveryActive = false
        lastCycleAtMs = null
        peerScanStartedAtMs = null
    }

    companion object {
        /** How long a manual peer scan holds the radio before the service cycle resumes. */
        const val PEER_SCAN_WINDOW_MS = 30_000L
    }
}
