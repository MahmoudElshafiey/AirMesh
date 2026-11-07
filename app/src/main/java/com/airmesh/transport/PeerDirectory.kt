package com.airmesh.transport

import kotlinx.coroutines.flow.StateFlow

/** A nearby AirMesh phone as decoded from its DNS-SD advertisement. */
data class AirMeshPeer(
    val deviceAddress: String,
    val nodeId: Int,
    val isGroupOwner: Boolean,
)

/**
 * Directory of nearby AirMesh phones over Wi-Fi Direct DNS-SD. The pure
 * AutoMeshController consumes [peers]; the Android implementation publishes and
 * discovers with WifiP2pManager. Hidden behind this interface so tests use a fake.
 */
interface PeerDirectory {

    /** Currently visible AirMesh peers (self excluded), sorted by node id. */
    val peers: StateFlow<List<AirMeshPeer>>

    /** Publish our ad; call again when the group-owner role changes. */
    fun startAdvertising(nodeId: Int, isGroupOwner: Boolean)

    /** Start/stop the periodic service request (Android stops discovery after ~2 min). */
    fun setDiscovering(enabled: Boolean)

    /** Terminal: unregister our ad, stop discovering, clear peers and release the P2P channel. */
    fun stop()
}

/** Android stops DNS-SD discovery after roughly two minutes; re-issue well before that. */
const val DISCOVERY_REFRESH_MS = 60_000L

/** Pure refresh decision so the timing is testable with a fake clock (no coroutines). */
fun shouldRefreshDiscovery(lastRequestAtMs: Long?, nowMs: Long): Boolean =
    lastRequestAtMs == null || nowMs - lastRequestAtMs >= DISCOVERY_REFRESH_MS

/**
 * The NSD-style DNS-SD API has no "service removed" callback, so a peer that
 * left range simply stops being re-seen; drop it after this window.
 */
const val PEER_STALE_MS = 120_000L

/** Pure staleness decision so the sweep is testable with a fake clock (no coroutines). */
fun shouldExpirePeer(lastSeenMs: Long, nowMs: Long): Boolean =
    nowMs - lastSeenMs >= PEER_STALE_MS
