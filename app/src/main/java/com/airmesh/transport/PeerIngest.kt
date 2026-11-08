package com.airmesh.transport

/**
 * Pure-logic peer ingest: decides whether a DNS-SD callback should be
 * accepted as an AirMesh peer.  Extracted from the Android-specific
 * [WifiP2pPeerDirectory] so the accept/ignore logic is unit-testable
 * on the JVM without mocking WifiP2pManager.
 *
 * Design:
 * - The **service callback** is sufficient to accept a peer when the
 *   instance name parses via [parseAirMeshInstance].
 * - The **TXT callback** never rejects: if `map["ad"]` or the full
 *   domain parses, it refreshes the same peer entry (idempotent).
 * - Adverts whose [AirMeshAdvert.nodeId] equals [myNodeId] are
 *   silently ignored.
 * - Adverts with an unsupported [AirMeshAdvert.version] are logged
 *   and ignored.
 *
 * "Accepted" is counted **once per peer address** (not per callback),
 * so a TXT refresh after a service accept does not double-count.
 */
class PeerIngest(
    private val myNodeId: Int,
    private val supportedVersion: Int = SUPPORTED_VERSION,
    private val log: (String) -> Unit = {},
    private val stats: DnsSdStats = DnsSdStats(),
) {

    /**
     * A peer as seen by the ingest layer.  [lastSeenMs] is updated on
     * every matching callback; the rest is immutable per address.
     */
    data class Peer(
        val deviceAddress: String,
        val nodeId: Int,
        val tieBreak: Long,
        val owner: Boolean,
        val lastSeenMs: Long,
    )

    /** Currently tracked peers, keyed by device address. */
    private val _peers = mutableMapOf<String, Peer>()
    val peers: Map<String, Peer> get() = _peers.toMap()

    /** Set of addresses already counted as "accepted". */
    private val acceptedAddresses = mutableSetOf<String>()

    // ----- service callback --------------------------------------------------

    /**
     * Called when a DNS-SD service callback arrives.
     *
     * @param instanceName the instance name from the callback (may have
     *   `.local.` or other DNS suffixes).
     * @param serviceType  the service type string (for logging only; the
     *   instance name is the authority).
     * @param srcAddress   the P2P device address of the sender.
     * @param nowMs        current timestamp (millis).
     */
    fun onServiceAvailable(
        instanceName: String?,
        serviceType: String?,
        srcAddress: String?,
        nowMs: Long,
    ) {
        val ad = parseAirMeshInstance(instanceName)
        if (ad == null) {
            stats.onIgnored()
            log("dnssd: ignored: foreign service type='$serviceType' instance='$instanceName'")
            return
        }
        if (ad.nodeId == myNodeId) {
            stats.onIgnored()
            log("dnssd: ignored own ad (#${ad.nodeId})")
            return
        }
        if (ad.version != supportedVersion) {
            stats.onIgnored()
            log("dnssd: ignored unsupported version ${ad.version} from #${ad.nodeId}")
            return
        }
        if (srcAddress.isNullOrEmpty() || srcAddress == PEER_PLACEHOLDER_ADDRESS) {
            stats.onIgnored()
            log("dnssd: ignored #${ad.nodeId} — no usable source device address")
            return
        }
        upsert(ad, srcAddress, nowMs)
        log("dnssd: accepted AirMesh peer #${ad.nodeId} owner=${if (ad.owner) 1 else 0} src=$srcAddress")
    }

    // ----- TXT callback ------------------------------------------------------

    /**
     * Called when a DNS-SD TXT-record callback arrives.
     *
     * The TXT callback never rejects.  If `map["ad"]` or [fullDomain]
     * parses, the peer entry is refreshed (idempotent — no second
     * "accepted" count).
     *
     * @param fullDomain the "service type" string the framework delivers
     *   for TXT callbacks (often the full domain, e.g.
     *   `_airmesh._tcp.airmesh-n52647-v2-t7e1b99fe-o0.local.`).
     * @param txtRecord  the key/value map from the TXT record.
     * @param srcAddress the P2P device address of the sender.
     * @param nowMs      current timestamp.
     */
    fun onTxtRecordAvailable(
        fullDomain: String?,
        txtRecord: Map<String, String>,
        srcAddress: String?,
        nowMs: Long,
    ) {
        // Try to extract an advert from the TXT map entry first, then
        // from the full domain string.
        val ad = parseAirMeshInstance(txtRecord[TXT_AD_KEY])
            ?: parseAirMeshInstance(fullDomain)

        if (ad == null) {
            log("dnssd: TXT no parseable ad domain='$fullDomain' map=$txtRecord")
            return
        }
        if (ad.nodeId == myNodeId) {
            log("dnssd: TXT ignored own ad (#${ad.nodeId})")
            return
        }
        if (ad.version != supportedVersion) {
            log("dnssd: TXT ignored unsupported version ${ad.version} from #${ad.nodeId}")
            return
        }
        if (srcAddress.isNullOrEmpty() || srcAddress == PEER_PLACEHOLDER_ADDRESS) {
            log("dnssd: TXT ignored #${ad.nodeId} — no usable source device address")
            return
        }
        upsert(ad, srcAddress, nowMs)
        log("dnssd: TXT refreshed peer #${ad.nodeId} src=$srcAddress")
    }

    // ----- internal ----------------------------------------------------------

    private fun upsert(ad: AirMeshAdvert, address: String, nowMs: Long) {
        _peers[address] = Peer(
            deviceAddress = address,
            nodeId = ad.nodeId,
            tieBreak = ad.tieBreak,
            owner = ad.owner,
            lastSeenMs = nowMs,
        )
        if (acceptedAddresses.add(address)) {
            stats.onAccepted()
        }
    }

    companion object {
        /** The TXT record key carrying the ad token. */
        const val TXT_AD_KEY = "ad"

        /** The protocol version this build understands. */
        const val SUPPORTED_VERSION = 2
    }
}
