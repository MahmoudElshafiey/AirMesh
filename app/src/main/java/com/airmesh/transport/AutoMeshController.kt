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

    /**
     * Yielding my group to a stronger owner (TASK M1 part 4): our own removal was
     * issued and we are joining [targetAddress]. We never act as an owner again in
     * this cycle — the join either lands us in [Client] or fails into [Backoff].
     */
    data class Yielding(
        val targetAddress: String,
        val targetNodeId: Int,
        /** Client count the target advertised when we decided to yield. */
        val targetPhones: Int,
        val sinceMs: Long,
    ) : AutoMeshState

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

    /**
     * A group removal that WE initiated (stale group cleanup at startup, or our
     * own removeGroup): skips the group-gone backoff and enters Searching within
     * at most 500 ms.
     */
    data object IntentionalGroupGone : AutoMeshEvent

    /** Our live client count changed (TASK M1 part 4); feeds the merge decision. */
    data class MyClientsChanged(val clients: Int) : AutoMeshEvent

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

/** Searching this long with classic devices visible but no AirMesh ad shows a hint banner. */
const val NO_AIRMESH_PEERS_AFTER_MS = 45_000L

/**
 * After an UNEXPECTED group loss, the following Searching never creates for this
 * long — it only joins visible owners (TASK M1 part 4). This keeps former clients
 * from fragmenting the mesh into new groups while the old owner is still around.
 * Fresh startups are unaffected: they never lost a group.
 */
const val POST_LOSS_CREATION_GRACE_MS = 10_000L

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
    is AutoMeshState.Yielding -> "Merging with a nearby network (#${s.targetNodeId}, ${s.targetPhones} phones)…"
    is AutoMeshState.Owner, is AutoMeshState.Client -> "Connected · $neighborCount neighbors"
    is AutoMeshState.Backoff -> {
        val secondsLeft = ((s.untilMs - nowMs) / 1000 + 1).coerceAtLeast(1)
        "Retrying in ${secondsLeft}s — ${s.reason}"
    }
}

/**
 * Hint for the case where DNS-SD finds nothing but the radio clearly sees other
 * Wi-Fi Direct devices (wrong app, old version, or auto-mesh off there). Shown
 * only while Searching after [NO_AIRMESH_PEERS_AFTER_MS]; null otherwise so
 * [autoMeshBannerText] keeps applying. We never connect to such a device on our own.
 *
 * [strategy] and [rawCallbacks] add a second, smaller banner line explaining what
 * the discovery layer is currently doing; null (the default) keeps the old text.
 */
fun noAirMeshPeersBannerText(
    state: AutoMeshState,
    nowMs: Long,
    airMeshPeerCount: Int,
    classicDeviceCount: Int,
    strategy: Int? = null,
    rawCallbacks: Int? = null,
): String? {
    val s = state as? AutoMeshState.Searching ?: return null
    if (nowMs - s.sinceMs < NO_AIRMESH_PEERS_AFTER_MS) return null
    if (airMeshPeerCount != 0 || classicDeviceCount == 0) return null
    val noun = if (classicDeviceCount == 1) "device" else "devices"
    val hint = "Found $classicDeviceCount Wi-Fi Direct $noun but none advertises AirMesh. Open Advanced to connect manually."
    if (strategy == null || rawCallbacks == null) return hint
    return "$hint\n${discoveryStatusLine(strategy, rawCallbacks)}"
}

/**
 * Second banner line: which discovery strategy is active and how many raw DNS-SD
 * callbacks have arrived. Pure text, so the exact wording is unit-tested.
 */
fun discoveryStatusLine(strategy: Int, rawCallbacks: Int): String =
    "Discovery strategy $strategy, raw callbacks $rawCallbacks"

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
    /** Optional delay (<= 500 ms) before an intentional removal enters Searching; 0 ms by default. */
    private val intentionalRemovalDelayMs: Long = 0L,
    /** Whether a group owner keeps scanning for other groups to merge with (TASK M1). */
    mergeEnabled: Boolean = false,
) {

    var state: AutoMeshState = AutoMeshState.Idle
        private set

    /** Why [state] is Idle even though auto-mesh is enabled (e.g. missing permission). */
    var idleReason: String? = null
        private set

    var autoEnabled: Boolean = autoEnabled
        private set

    /** Owners keep scanning for other groups to merge with when this is on (TASK M1). */
    var mergeEnabled: Boolean = mergeEnabled
        private set

    /** Consecutive failed attempts; drives the escalating backoff schedule. */
    private var consecutiveFailures = 0
    /** BUSY already retried once for the current join target. */
    private var busyRetryUsed = false
    /** Deadline after which we take over and create when peers are visible but no owner is. */
    private var takeoverDeadlineMs: Long? = null
    /** Latest peer list, so a Tick can re-decide even if no PeersChanged arrived yet. */
    private var lastPeers: List<AirMeshPeer> = emptyList()
    /** Set when we initiated a group removal so a subsequent GroupGone skips backoff. */
    private var intentionalRemovalPending = false

    // --- owner merge (TASK M1 part 4) ------------------------------------------

    /** Owns the yield preconditions; shares our seeded Random for reproducibility. */
    private val mergeDecider = MergeDecider(myNodeId, tieBreakToken, random)
    /** Our live client count (phones in our group), reported by the service. */
    private var myClients = 0
    /** When [myClients] last changed; null = never (stable since birth). */
    private var myClientsChangedAtMs: Long? = null
    /** Last merge outcome we queued for logging, so a steady state is not re-logged. */
    private var lastLoggedMergeOutcome: String? = null
    /** One-shot "merge ..." line the service prints via [takeMergeLogLine]. */
    private var pendingMergeLogLine: String? = null
    /** Set by an unexpected group loss; consumed by the next [enterSearching]. */
    private var lostGroupUnexpectedly = false
    /** While now < this, [decide] joins visible owners but never creates. */
    private var creationGraceUntilMs = 0L

    /** The service calls this after each event to drain a queued merge log line. */
    fun takeMergeLogLine(): String? {
        val line = pendingMergeLogLine
        pendingMergeLogLine = null
        return line
    }

    /** Marks the next [AutoMeshEvent.GroupGone] as intentional (skips group-gone backoff). */
    fun markIntentionalGroupRemoval() {
        intentionalRemovalPending = true
    }

    fun onEvent(event: AutoMeshEvent): List<AutoMeshAction> {
        val now = clock()
        return when (event) {
            is AutoMeshEvent.PermissionsChanged -> onPermissionsChanged(event, now)
            is AutoMeshEvent.PeersChanged -> {
                lastPeers = event.peers // kept even while paused so resume starts fresh
                if (!autoEnabled) emptyList() else decide(event.peers, now) + runMergeDecision(now)
            }
            // Group reality is tracked even while paused: a manual connect that
            // succeeds must still land us in Owner/Client.
            is AutoMeshEvent.GroupFormed -> onGroupFormed(event.role, now)
            is AutoMeshEvent.MyClientsChanged -> {
                if (event.clients != myClients) {
                    myClients = event.clients
                    myClientsChangedAtMs = now
                }
                emptyList()
            }
            is AutoMeshEvent.GroupGone -> if (intentionalRemovalPending) {
                intentionalRemovalPending = false
                // Our own merge removal: the join toward the target keeps running.
                if (state is AutoMeshState.Yielding) emptyList() else onIntentionalGroupGone(now)
            } else {
                onGroupGone(now)
            }
            is AutoMeshEvent.IntentionalGroupGone -> {
                intentionalRemovalPending = false
                // Our own merge removal: the join toward the target keeps running.
                if (state is AutoMeshState.Yielding) emptyList() else onIntentionalGroupGone(now)
            }
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
                // A merge join in flight is abandoned like any other join; a later
                // GroupFormed still lands us in Client ("accepted even while paused").
                is AutoMeshState.Searching, is AutoMeshState.Creating,
                is AutoMeshState.Joining, is AutoMeshState.Yielding -> {
                    enterIdle()
                    listOf(AutoMeshAction.StopDiscovery)
                }
                is AutoMeshState.Backoff -> {
                    enterIdle()
                    emptyList()
                }
                // An owner with merge on may be scanning for other groups; stop it.
                is AutoMeshState.Owner -> if (mergeEnabled) listOf(AutoMeshAction.StopDiscovery) else emptyList()
                else -> emptyList() // Idle / Client: nothing to unwind
            }
        }
        return when (state) {
            is AutoMeshState.Idle, is AutoMeshState.Backoff -> {
                enterSearching(now)
                listOf(AutoMeshAction.StartDiscovery)
            }
            // Resuming while owning a group restarts merge scanning if it is on.
            is AutoMeshState.Owner -> if (mergeEnabled) listOf(AutoMeshAction.StartDiscovery) else emptyList()
            else -> emptyList() // Client: in a group we do nothing
        }
    }

    /** Toggle owner merge-scanning at runtime; only an active, unpaused owner changes discovery. */
    fun setMergeEnabled(enabled: Boolean): List<AutoMeshAction> {
        if (mergeEnabled == enabled) return emptyList()
        mergeEnabled = enabled
        // A paused device never scans, even with the flag on: pausing wins.
        return if (state is AutoMeshState.Owner && autoEnabled) {
            if (enabled) listOf(AutoMeshAction.StartDiscovery) else listOf(AutoMeshAction.StopDiscovery)
        } else emptyList()
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
        // deadline in case the elected creator never shows up. During the post-loss
        // grace (TASK M1 part 4) BOTH create paths are suppressed: we only join
        // visible owners, so former clients do not fragment the mesh.
        val creationGrace = now < creationGraceUntilMs
        if (!creationGrace && iAmAutoMeshCreator(myNodeId, tieBreakToken, peers)) {
            state = AutoMeshState.Creating
            return listOf(AutoMeshAction.CreateGroup)
        }
        val deadline = takeoverDeadlineMs
        if (deadline == null) {
            if (!creationGrace) {
                // Armed only outside the grace window; by the time it fires the
                // 10 s grace has long expired, so "existing election applies" after.
                takeoverDeadlineMs = now + AUTO_TAKEOVER_BASE_MS + random.nextInt(AUTO_JITTER_MAX_MS.toInt() + 1)
            }
        } else if (now >= deadline && !creationGrace) {
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
            // A merge join that has not formed a group in time is cancelled the same way.
            is AutoMeshState.Yielding -> if (now - s.sinceMs > AUTO_JOIN_TIMEOUT_MS) {
                failAttempt("merge join timed out after ${AUTO_JOIN_TIMEOUT_MS / 1000}s", now)
            } else emptyList()
            // Re-decide with the latest peers: covers peers seen before we started
            // searching and the takeover deadline.
            is AutoMeshState.Searching -> decide(lastPeers, now)
            // An owner re-runs the merge decision each tick (TASK M1 part 4).
            is AutoMeshState.Owner -> runMergeDecision(now)
            else -> emptyList() // Idle / Creating / Client
        }
    }

    private fun onGroupFormed(role: AutoMeshRole, now: Long): List<AutoMeshAction> {
        consecutiveFailures = 0
        busyRetryUsed = false
        takeoverDeadlineMs = null
        // Peer updates are ignored while in a group (rule a), so the stored list
        // would go stale; drop it so a later re-search starts from fresh data.
        lastPeers = emptyList()
        // A freshly formed group has no clients yet; the service reports growth via
        // MyClientsChanged (TASK M1 part 4). The merge log context resets too, and a
        // stale "lost unexpectedly" flag must not arm grace for a later removal.
        myClients = 0
        myClientsChangedAtMs = now
        lastLoggedMergeOutcome = null
        lostGroupUnexpectedly = false
        state = if (role == AutoMeshRole.OWNER) AutoMeshState.Owner else AutoMeshState.Client
        // Rule (a): in a group we do nothing; stop discovering to save battery —
        // other phones find us through our advertisement, not our discovery. The
        // one exception is an unpaused owner with merge enabled: it keeps scanning
        // for other groups to join (TASK M1).
        return if (state is AutoMeshState.Owner && autoEnabled && mergeEnabled) {
            listOf(AutoMeshAction.StartDiscovery)
        } else {
            listOf(AutoMeshAction.StopDiscovery)
        }
    }

    private fun onGroupGone(now: Long): List<AutoMeshAction> {
        return when (state) {
            is AutoMeshState.Owner, is AutoMeshState.Client -> {
                // Rule (f): wait 1–3 s with jitter, then search again. An unexpected
                // loss also arms the post-loss creation grace (TASK M1 part 4).
                lostGroupUnexpectedly = true
                val until = now + AUTO_GROUP_GONE_WAIT_BASE_MS + random.nextInt(AUTO_GROUP_GONE_WAIT_JITTER_MS.toInt() + 1)
                state = AutoMeshState.Backoff(until, "group gone")
                listOf(AutoMeshAction.StopDiscovery)
            }
            // A plain GroupGone while yielding means the group vanished on its own
            // (our own removal arrives as IntentionalGroupGone): note it for the
            // post-loss grace, but keep the join toward the target running.
            is AutoMeshState.Yielding -> {
                lostGroupUnexpectedly = true
                emptyList()
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

    /**
     * Our own group removal (stale group at startup, or our own removeGroup):
     * skip the 1–3 s group-gone backoff and enter Searching within at most 500 ms.
     */
    private fun onIntentionalGroupGone(now: Long): List<AutoMeshAction> {
        if (!autoEnabled || idleReason != null) {
            if (state is AutoMeshState.Owner || state is AutoMeshState.Client) {
                enterIdle()
            }
            return emptyList()
        }
        val delayMs = intentionalRemovalDelayMs.coerceIn(0L, 500L)
        if (delayMs > 0L) {
            state = AutoMeshState.Backoff(now + delayMs, "intentional group removal")
            return listOf(AutoMeshAction.StopDiscovery)
        }
        val wasSearching = state is AutoMeshState.Searching
        if (!wasSearching) {
            enterSearching(now)
        }
        return if (wasSearching) emptyList() else listOf(AutoMeshAction.StartDiscovery)
    }

    private fun onConnectFailed(reason: AutoFailureReason, now: Long): List<AutoMeshAction> {
        return when (val s = state) {
            is AutoMeshState.Joining -> if (reason == AutoFailureReason.BUSY && !busyRetryUsed) {
                // BUSY means the framework is mid-operation; clear it and retry once.
                busyRetryUsed = true
                listOf(AutoMeshAction.RemoveGroup, AutoMeshAction.ConnectTo(s.targetAddress))
            } else failAttempt("connect failed (${reason.name.lowercase()})", now)
            is AutoMeshState.Creating -> failAttempt("create group failed (${reason.name.lowercase()})", now)
            // A merge join fails straight to backoff — no BUSY retry mid-merge: the
            // 120 s yield cooldown keeps us from bouncing between the two groups.
            is AutoMeshState.Yielding -> failAttempt("merge connect failed (${reason.name.lowercase()})", now)
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
        if (lostGroupUnexpectedly) {
            // Post-loss grace (TASK M1 part 4): for a short while after an
            // unexpected loss we only join visible owners and never create.
            creationGraceUntilMs = now + POST_LOSS_CREATION_GRACE_MS
            lostGroupUnexpectedly = false
        }
    }

    /**
     * Re-runs the merge decision (TASK M1 part 4). Only an unpaused owner with
     * merge enabled acts; every outcome change is queued for a single "auto: merge"
     * log line. On [MergeDecision.Yield] we remove our own group and join the
     * target — the service marks that removal intentional when it executes it.
     */
    private fun runMergeDecision(now: Long): List<AutoMeshAction> {
        if (state !is AutoMeshState.Owner || !autoEnabled || !mergeEnabled) return emptyList()
        val other = strongestForeignOwner(lastPeers, myNodeId)?.let {
            MergeOwnerSighting(it.deviceAddress, it.nodeId, it.tieBreakToken, it.clients, it.lastSeenMs)
        }
        val decision = mergeDecider.decide(now, myClients, myClientsChangedAtMs, other)
        queueMergeLogLine(decision, other, now)
        return if (decision is MergeDecision.Yield) {
            state = AutoMeshState.Yielding(
                targetAddress = decision.targetAddress,
                targetNodeId = decision.targetNodeId,
                targetPhones = other?.clients ?: 0,
                sinceMs = now,
            )
            listOf(AutoMeshAction.RemoveGroup, AutoMeshAction.ConnectTo(decision.targetAddress))
        } else emptyList()
    }

    /** Queue "merge ..." with all inputs, but only when the outcome changes. */
    private fun queueMergeLogLine(decision: MergeDecision, other: MergeOwnerSighting?, now: Long) {
        val outcome = when (decision) {
            is MergeDecision.Stay -> "stay"
            is MergeDecision.Wait -> "wait (${decision.reason})"
            is MergeDecision.Yield -> "yield to #${decision.targetNodeId} at ${decision.targetAddress}"
        }
        if (outcome == lastLoggedMergeOutcome) return
        lastLoggedMergeOutcome = outcome
        val otherDesc = other?.let { "#${it.nodeId} clients=${it.clients} seen ${now - it.lastSeenMs}ms ago" }
            ?: "none visible"
        val changedAt = myClientsChangedAtMs?.let { "changed ${now - it}ms ago" } ?: "never changed"
        pendingMergeLogLine = "merge me=#$myNodeId clients=$myClients ($changedAt) other=$otherDesc -> $outcome"
    }

    private fun enterIdle() {
        state = AutoMeshState.Idle
        takeoverDeadlineMs = null
    }
}
