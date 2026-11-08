package com.airmesh.transport

import java.util.Random

/** The role we hold in a Wi-Fi Direct group, as seen by the auto-mesh logic. */
enum class AutoMeshRole { OWNER, CLIENT }

/** Why an automatic connect/create attempt failed; BUSY gets one special retry. */
enum class AutoFailureReason { BUSY, OTHER }

/** States of the auto-mesh state machine (pure Kotlin — no Android imports). */
sealed interface AutoMeshState {
    /** Not searching: waiting for permissions, or paused by a manual action. */
    data object Idle : AutoMeshState

    /** Discovering peers and deciding whether to join, create, or wait. */
    data class Searching(val sinceMs: Long) : AutoMeshState

    /** createGroup() was issued; waiting for the group to form. */
    data object Creating : AutoMeshState

    /** connect() was issued toward [targetAddress]; waiting for it to be accepted. */
    data class Joining(
        val targetAddress: String,
        val targetNodeId: Int,
        val sinceMs: Long,
    ) : AutoMeshState

    /** In a group as the owner; keeps advertising with the owner flag set. */
    data object Owner : AutoMeshState

    /** In a group as a client. */
    data object Client : AutoMeshState

    /** Quiet period after a failure or a lost group; search again at [untilMs]. */
    data class Backoff(val untilMs: Long, val reason: String) : AutoMeshState
}

/** Actions the service executes on behalf of the controller. */
sealed interface AutoMeshAction {
    data object StartDiscovery : AutoMeshAction
    data object StopDiscovery : AutoMeshAction
    data object CreateGroup : AutoMeshAction
    data class ConnectTo(val deviceAddress: String) : AutoMeshAction
    data object RemoveGroup : AutoMeshAction
}

/** Events that drive the controller. */
sealed interface AutoMeshEvent {
    /** Permission state changed; [missingReason] explains why auto-mesh cannot run. */
    data class PermissionsChanged(val granted: Boolean, val missingReason: String? = null) : AutoMeshEvent

    /** The peer directory's visible-peer list changed. */
    data class PeersChanged(val peers: List<AirMeshPeer>) : AutoMeshEvent

    /** A group formed and we hold [role] in it (accepted even while paused). */
    data class GroupFormed(val role: AutoMeshRole) : AutoMeshEvent

    /** The group we were in is gone, or a join target vanished. */
    data object GroupGone : AutoMeshEvent

    /** An automatic connect/create attempt failed; [reason] distinguishes BUSY. */
    data class ConnectFailed(val reason: AutoFailureReason) : AutoMeshEvent

    /** Periodic wake-up (the service ticks about once per second). */
    data object Tick : AutoMeshEvent
}

/** No owner appeared within this window while peers were visible; take over and create. */
const val AUTO_TAKEOVER_BASE_MS = 20_000L

/** Max random jitter added to the takeover window (and to capped failure delays). */
const val AUTO_JITTER_MAX_MS = 5_000L

/** A join attempt that has not formed a group by this age is cancelled and backed off. */
const val AUTO_JOIN_TIMEOUT_MS = 40_000L

/** After this, the banner switches from "Connecting…" to "Waiting for acceptance…". */
const val AUTO_JOIN_WAITING_TEXT_MS = 3_000L

/** A lost group waits base + jitter(0..AUTO_GROUP_GONE_WAIT_JITTER_MS) before searching again. */
const val AUTO_GROUP_GONE_WAIT_BASE_MS = 1_000L
const val AUTO_GROUP_GONE_WAIT_JITTER_MS = 2_000L

/** Failure backoff: base * 2^(failures-1) for the first four failures, then capped + jitter. */
const val AUTO_FAILURE_BASE_MS = 2_000L
const val AUTO_FAILURE_CAP_MS = 30_000L

/**
 * One random 32-bit token per process. Node ids are unique per install, but if two
 * phones ever share an id the election must still be symmetric: both sides see each
 * other's token in the ad and rank by (node id, token). Generated once at class load
 * so it is stable for the whole process lifetime.
 */
object TieBreakToken {
    // nextInt is an instance method on java.util.Random, so a throwaway instance.
    val current: Int = Random().nextInt()
}

/**
 * The owner to join when several are visible: lowest node id wins, ties broken by
 * tie-break token so every phone computes the same winner (no own address needed).
 */
fun chooseAutoMeshOwner(peers: List<AirMeshPeer>): AirMeshPeer? =
    peers.filter { it.isGroupOwner }.minWithOrNull(compareBy({ it.nodeId }, { it.tieBreakToken }))

/**
 * Whether I create the group when no owner is visible: the highest (node id, token)
 * pair among {me ∪ visible peers} creates, everyone else waits for the takeover
 * window. The comparison uses only values both sides advertise, so it is symmetric.
 */
fun iAmAutoMeshCreator(myNodeId: Int, myTieBreakToken: Int, peers: List<AirMeshPeer>): Boolean {
    val me = myNodeId to myTieBreakToken
    val candidates = peers.map { it.nodeId to it.tieBreakToken } + me
    return candidates.maxWith(compareBy<Pair<Int, Int>>({ it.first }, { it.second })) == me
}

/** Pure backoff schedule: 2/4/8/16 s for the first four failures, then 30 s + up to 5 s jitter. */
fun autoMeshFailureDelayMs(failures: Int, random: Random): Long = when {
    failures <= 0 -> AUTO_FAILURE_BASE_MS
    failures in 1..4 -> AUTO_FAILURE_BASE_MS shl (failures - 1)
    else -> AUTO_FAILURE_CAP_MS + random.nextInt(AUTO_JITTER_MAX_MS.toInt() + 1)
}

/**
 * Human-readable banner for the current state, or null when nothing is worth
 * showing (Idle without a reason). [neighborCount] feeds the connected text.
 */
fun autoMeshBannerText(
    state: AutoMeshState,
    neighborCount: Int,
    idleReason: String?,
    nowMs: Long,
): String? = when (val s = state) {
    is AutoMeshState.Idle -> idleReason
    is AutoMeshState.Searching -> "Searching for nearby AirMesh phones…"
    is AutoMeshState.Creating -> "Creating a group — waiting for another phone to join"
    is AutoMeshState.Joining -> if (nowMs - s.sinceMs > AUTO_JOIN_WAITING_TEXT_MS) {
        "Waiting for the other phone to accept the connection request"
    } else {
        "Connecting to #${s.targetNodeId}…"
    }
    is AutoMeshState.Owner, is AutoMeshState.Client -> "Connected · $neighborCount neighbors"
    is AutoMeshState.Backoff -> {
        val secondsLeft = ((s.untilMs - nowMs) / 1000 + 1).coerceAtLeast(1)
        "Retrying in ${secondsLeft}s — ${s.reason}"
    }
}

/**
 * Event-driven state machine that decides, from the visible AirMesh peers and
 * our group role, whether to search, join an owner, create a group, or wait.
 * Pure Kotlin: no Android imports, injectable clock and Random so every rule is
 * unit-testable on the JVM. The service executes the returned actions against
 * WifiDirectConnector / PeerDirectory and logs each decision with an "auto:" prefix.
 */
class AutoMeshController(
    private val myNodeId: Int,
    /** Our process-lifetime election token; advertised in our DNS-SD ad. */
    private val tieBreakToken: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random(),
    autoEnabled: Boolean = true,
) {

    var state: AutoMeshState = AutoMeshState.Idle
        private set

    /** Why [state] is Idle even though auto-mesh is enabled (e.g. missing permission). */
    var idleReason: String? = null
        private set

    var autoEnabled: Boolean = autoEnabled
        private set

    /** Consecutive failed attempts; drives the escalating backoff schedule. */
    private var consecutiveFailures = 0
    /** BUSY already retried once for the current join target. */
    private var busyRetryUsed = false
    /** Deadline after which we take over and create when peers are visible but no owner is. */
    private var takeoverDeadlineMs: Long? = null
    /** Latest peer list, so a Tick can re-decide even if no PeersChanged arrived yet. */
    private var lastPeers: List<AirMeshPeer> = emptyList()

    fun onEvent(event: AutoMeshEvent): List<AutoMeshAction> {
        val now = clock()
        return when (event) {
            is AutoMeshEvent.PermissionsChanged -> onPermissionsChanged(event, now)
            is AutoMeshEvent.PeersChanged -> {
                lastPeers = event.peers // kept even while paused so resume starts fresh
                if (!autoEnabled) emptyList() else decide(event.peers, now)
            }
            // Group reality is tracked even while paused: a manual connect that
            // succeeds must still land us in Owner/Client.
            is AutoMeshEvent.GroupFormed -> onGroupFormed(event.role)
            is AutoMeshEvent.GroupGone -> onGroupGone(now)
            is AutoMeshEvent.ConnectFailed -> if (autoEnabled) onConnectFailed(event.reason, now) else emptyList()
            AutoMeshEvent.Tick -> if (autoEnabled) onTick(now) else emptyList()
        }
    }

    /** Pause/resume. Pausing stops all decisions; group events are still processed. */
    fun setAutoEnabled(enabled: Boolean): List<AutoMeshAction> {
        if (autoEnabled == enabled) return emptyList()
        autoEnabled = enabled
        val now = clock()
        if (!enabled) {
            return when (state) {
                is AutoMeshState.Searching, is AutoMeshState.Creating, is AutoMeshState.Joining -> {
                    enterIdle()
                    listOf(AutoMeshAction.StopDiscovery)
                }
                is AutoMeshState.Backoff -> {
                    enterIdle()
                    emptyList()
                }
                else -> emptyList() // Idle / Owner / Client: nothing to unwind
            }
        }
        return when (state) {
            is AutoMeshState.Idle, is AutoMeshState.Backoff -> {
                enterSearching(now)
                listOf(AutoMeshAction.StartDiscovery)
            }
            else -> emptyList() // Owner / Client: in a group we do nothing
        }
    }

    private fun onPermissionsChanged(event: AutoMeshEvent.PermissionsChanged, now: Long): List<AutoMeshAction> {
        if (!event.granted) {
            idleReason = event.missingReason ?: "Wi-Fi Direct permission missing"
            return when (state) {
                is AutoMeshState.Searching, is AutoMeshState.Creating, is AutoMeshState.Joining -> {
                    enterIdle()
                    listOf(AutoMeshAction.StopDiscovery)
                }
                is AutoMeshState.Backoff -> {
                    enterIdle()
                    emptyList()
                }
                else -> emptyList() // Idle / Owner / Client: group reality wins over the banner reason
            }
        }
        idleReason = null
        return when (state) {
            is AutoMeshState.Idle, is AutoMeshState.Backoff -> {
                enterSearching(now)
                listOf(AutoMeshAction.StartDiscovery)
            }
            else -> emptyList()
        }
    }

    /** The Searching decision: join an owner, create if elected, or wait for takeover. */
    private fun decide(peers: List<AirMeshPeer>, now: Long): List<AutoMeshAction> {
        if (state !is AutoMeshState.Searching) return emptyList()
        val owner = chooseAutoMeshOwner(peers)
        if (owner != null) {
            // Rule (b): a visible owner is joined; the target stays fixed until it
            // fails, times out, or vanishes — no mid-join switching.
            state = AutoMeshState.Joining(owner.deviceAddress, owner.nodeId, now)
            busyRetryUsed = false
            return listOf(AutoMeshAction.ConnectTo(owner.deviceAddress))
        }
        if (peers.isEmpty()) {
            // Rule (d): no AirMesh peer at all — keep searching, never create.
            takeoverDeadlineMs = null
            return emptyList()
        }
        // Rule (c): peers visible but no owner. The highest node id among
        // {me ∪ peers} creates immediately; everyone else waits for the takeover
        // deadline in case the elected creator never shows up.
        if (iAmAutoMeshCreator(myNodeId, tieBreakToken, peers)) {
            state = AutoMeshState.Creating
            return listOf(AutoMeshAction.CreateGroup)
        }
        val deadline = takeoverDeadlineMs
        if (deadline == null) {
            takeoverDeadlineMs = now + AUTO_TAKEOVER_BASE_MS + random.nextInt(AUTO_JITTER_MAX_MS.toInt() + 1)
        } else if (now >= deadline) {
            state = AutoMeshState.Creating
            return listOf(AutoMeshAction.CreateGroup) // takeover: no owner appeared in time
        }
        return emptyList()
    }

    private fun onTick(now: Long): List<AutoMeshAction> {
        return when (val s = state) {
            is AutoMeshState.Backoff -> if (now >= s.untilMs) {
                enterSearching(now)
                listOf(AutoMeshAction.StartDiscovery)
            } else emptyList()
            // Rule (g): a join that has not formed a group in time is cancelled.
            is AutoMeshState.Joining -> if (now - s.sinceMs > AUTO_JOIN_TIMEOUT_MS) {
                failAttempt("join timed out after ${AUTO_JOIN_TIMEOUT_MS / 1000}s", now)
            } else emptyList()
            // Re-decide with the latest peers: covers peers seen before we started
            // searching and the takeover deadline.
            is AutoMeshState.Searching -> decide(lastPeers, now)
            else -> emptyList() // Idle / Creating / Owner / Client
        }
    }

    private fun onGroupFormed(role: AutoMeshRole): List<AutoMeshAction> {
        consecutiveFailures = 0
        busyRetryUsed = false
        takeoverDeadlineMs = null
        // Peer updates are ignored while in a group (rule a), so the stored list
        // would go stale; drop it so a later re-search starts from fresh data.
        lastPeers = emptyList()
        state = if (role == AutoMeshRole.OWNER) AutoMeshState.Owner else AutoMeshState.Client
        // Rule (a): in a group we do nothing; stop discovering to save battery —
        // other phones find us through our advertisement, not our discovery.
        return listOf(AutoMeshAction.StopDiscovery)
    }

    private fun onGroupGone(now: Long): List<AutoMeshAction> {
        return when (state) {
            is AutoMeshState.Owner, is AutoMeshState.Client -> {
                // Rule (f): wait 1–3 s with jitter, then search again.
                val until = now + AUTO_GROUP_GONE_WAIT_BASE_MS + random.nextInt(AUTO_GROUP_GONE_WAIT_JITTER_MS.toInt() + 1)
                state = AutoMeshState.Backoff(until, "group gone")
                listOf(AutoMeshAction.StopDiscovery)
            }
            // Join target vanished (or our own BUSY cleanup ran): re-search without
            // backoff — discovery was still running and peers will tell us the rest.
            is AutoMeshState.Joining, is AutoMeshState.Creating -> {
                enterSearching(now)
                emptyList()
            }
            else -> emptyList() // Idle / Searching / Backoff: already out of a group
        }
    }

    private fun onConnectFailed(reason: AutoFailureReason, now: Long): List<AutoMeshAction> {
        return when (val s = state) {
            is AutoMeshState.Joining -> if (reason == AutoFailureReason.BUSY && !busyRetryUsed) {
                // BUSY means the framework is mid-operation; clear it and retry once.
                busyRetryUsed = true
                listOf(AutoMeshAction.RemoveGroup, AutoMeshAction.ConnectTo(s.targetAddress))
            } else failAttempt("connect failed (${reason.name.lowercase()})", now)
            is AutoMeshState.Creating -> failAttempt("create group failed (${reason.name.lowercase()})", now)
            // Idle / Searching / Backoff / Owner / Client: stale or manual failure; ignore.
            else -> emptyList()
        }
    }

    private fun failAttempt(reason: String, now: Long): List<AutoMeshAction> {
        consecutiveFailures += 1
        state = AutoMeshState.Backoff(now + autoMeshFailureDelayMs(consecutiveFailures, random), reason)
        return listOf(AutoMeshAction.StopDiscovery)
    }

    private fun enterSearching(now: Long) {
        state = AutoMeshState.Searching(now)
        takeoverDeadlineMs = null
    }

    private fun enterIdle() {
        state = AutoMeshState.Idle
        takeoverDeadlineMs = null
    }
}
