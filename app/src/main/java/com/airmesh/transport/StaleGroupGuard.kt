package com.airmesh.transport

/** States of [StaleGroupGuard]. */
sealed interface StaleGroupGuardState {
    /** Not removing a startup stale group; connection-info replies are processed normally. */
    data object Inactive : StaleGroupGuardState

    /**
     * Removing a startup stale group since [sinceMs]. Every `groupFormed=true`
     * connection-info reply is ignored while in this state.
     */
    data class Removing(
        val sinceMs: Long,
        val removeSucceededAtMs: Long? = null,
    ) : StaleGroupGuardState

    /** Removal confirmed; transitions immediately to [Inactive]. */
    data object Confirmed : StaleGroupGuardState
}

/** How the caller should handle a `requestConnectionInfo` reply after consulting [StaleGroupGuard]. */
sealed interface StaleGroupDecision {
    /** Guard is inactive: handle `groupFormed` normally. */
    data object ProcessNormally : StaleGroupDecision

    /**
     * Guard is in [StaleGroupGuardState.Removing] and `groupFormed=true` arrived:
     * ignore completely (no controller event, no TCP listener start, no DNS-SD
     * owner=1 advertising, no timeline groupFormed).
     */
    data object IgnoreStaleGroupFormed : StaleGroupDecision

    /**
     * Guard left [StaleGroupGuardState.Removing] because `groupFormed=false`
     * arrived: treat as intentional group removal (no 2 s debounce, no group-gone
     * backoff, stay/return to Searching).
     */
    data class ConfirmStaleGroupRemoved(val elapsedMs: Long) : StaleGroupDecision
}

/**
 * Guards startup against stale Wi-Fi Direct groups left over from a previous
 * session (Part 1).
 *
 * When startup cleanup calls `removeGroup` for a group that this process did not
 * create, the guard enters [StaleGroupGuardState.Removing]. While in `Removing`,
 * every `requestConnectionInfo` reply with `groupFormed=true` is ignored. The
 * guard leaves `Removing` -> [StaleGroupGuardState.Confirmed] ->
 * [StaleGroupGuardState.Inactive] on the first of:
 * 1. a connection-info reply with `groupFormed=false`,
 * 2. `removeGroup` onSuccess followed by [REMOVE_SUCCESS_SETTLE_MS] (1,500 ms),
 * 3. a [HARD_TIMEOUT_MS] (4,000 ms) hard timeout from the `removeGroup` call.
 *
 * Groups created by this process ([onProcessGroupCreated]) are never affected.
 */
class StaleGroupGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    var state: StaleGroupGuardState = StaleGroupGuardState.Inactive
        private set

    /** History of states visited (for verifying Removing -> Confirmed -> Inactive transitions). */
    private val _visitedStates = mutableListOf<StaleGroupGuardState>(StaleGroupGuardState.Inactive)
    val visitedStates: List<StaleGroupGuardState> get() = _visitedStates.toList()

    /** True once this process has created (or initiated) its own group. */
    var processCreatedGroup: Boolean = false
        private set

    /** Duration of the most recently completed stale-group removal, if any. */
    var lastCleanupDurationMs: Long? = null
        private set

    /** Mark that this process initiated its own group creation; the guard will never suppress it. */
    fun onProcessGroupCreated() {
        processCreatedGroup = true
        if (state is StaleGroupGuardState.Removing) {
            transitionTo(StaleGroupGuardState.Inactive)
        }
    }

    /**
     * Called when startup cleanup calls `removeGroup` for a pre-existing group.
     * Enters [StaleGroupGuardState.Removing] unless this process created the group itself.
     */
    fun onStaleGroupRemovalStarted(nowMs: Long = clock()): Boolean {
        if (processCreatedGroup) return false
        if (state is StaleGroupGuardState.Removing) return true
        transitionTo(StaleGroupGuardState.Removing(sinceMs = nowMs))
        log(START_LOG_LINE)
        return true
    }

    /** Called when the startup `removeGroup` ActionListener reports `onSuccess`. */
    fun onRemoveGroupSuccess(nowMs: Long = clock()) {
        val current = state as? StaleGroupGuardState.Removing ?: return
        if (current.removeSucceededAtMs == null) {
            transitionTo(current.copy(removeSucceededAtMs = nowMs))
        }
    }

    /** Called when the startup `removeGroup` ActionListener reports `onFailure`. */
    fun onRemoveGroupFailure(reason: Int, nowMs: Long = clock()) {
        val current = state as? StaleGroupGuardState.Removing ?: return
        log("P2P: stale group removeGroup failed reason=$reason (waiting for groupFormed=false or ${HARD_TIMEOUT_MS}ms timeout)")
        tick(nowMs)
    }

    /**
     * Evaluate a `requestConnectionInfo` reply at [nowMs]. Advances any expired
     * timers first so a reply arriving at or after the 1.5 s / 4 s deadline sees
     * the updated state.
     */
    fun onConnectionInfo(groupFormed: Boolean, nowMs: Long = clock()): StaleGroupDecision {
        tick(nowMs)
        val current = state as? StaleGroupGuardState.Removing ?: return StaleGroupDecision.ProcessNormally
        if (groupFormed) {
            log("P2P: ignoring stale groupFormed=true reply while removing startup stale group")
            return StaleGroupDecision.IgnoreStaleGroupFormed
        }
        val elapsedMs = (nowMs - current.sinceMs).coerceAtLeast(0L)
        completeRemoval(elapsedMs)
        return StaleGroupDecision.ConfirmStaleGroupRemoved(elapsedMs)
    }

    /**
     * Check whether the 1,500 ms post-success settle window or the 4,000 ms hard
     * timeout has elapsed at [nowMs]. Returns true when the guard left `Removing`.
     */
    fun tick(nowMs: Long = clock()): Boolean {
        val current = state as? StaleGroupGuardState.Removing ?: return false
        val succeededAt = current.removeSucceededAtMs
        if (succeededAt != null && nowMs - succeededAt >= REMOVE_SUCCESS_SETTLE_MS) {
            val elapsedMs = (nowMs - current.sinceMs).coerceAtLeast(0L)
            completeRemoval(elapsedMs)
            return true
        }
        if (nowMs - current.sinceMs >= HARD_TIMEOUT_MS) {
            val elapsedMs = (nowMs - current.sinceMs).coerceAtLeast(0L)
            log("P2P: warning — stale group removal timed out after ${elapsedMs}ms; continuing normally")
            completeRemoval(elapsedMs)
            return true
        }
        return false
    }

    private fun completeRemoval(elapsedMs: Long) {
        lastCleanupDurationMs = elapsedMs
        transitionTo(StaleGroupGuardState.Confirmed)
        log(doneLogLine(elapsedMs))
        transitionTo(StaleGroupGuardState.Inactive)
    }

    private fun transitionTo(next: StaleGroupGuardState) {
        state = next
        _visitedStates.add(next)
    }

    companion object {
        /** Time after `removeGroup` onSuccess before leaving `Removing` if no reply arrived. */
        const val REMOVE_SUCCESS_SETTLE_MS = 1_500L

        /** Hard timeout from `removeGroup` call after which the guard logs a warning and continues. */
        const val HARD_TIMEOUT_MS = 4_000L

        /** Logged when the guard enters `Removing`. */
        const val START_LOG_LINE = "auto: stale group cleanup started"

        /** Logged when the guard leaves `Removing`. */
        fun doneLogLine(elapsedMs: Long): String = "auto: stale group cleanup done after ${elapsedMs}ms"
    }
}

/**
 * Pure JVM-testable coordinator that wires [StaleGroupGuard], [GroupPresenceTracker],
 * [AutoMeshController], [DiscoveryCoordinator], [TimelineRecorder], TCP listener/client
 * start, and DNS-SD `owner=0/1` advertising using the same rules as [WifiDirectConnector]
 * and [com.airmesh.service.MeshService].
 */
class StaleGroupStartupHarness(
    private val clock: () -> Long,
    val controller: AutoMeshController,
    val coordinator: DiscoveryCoordinator,
    val timelineRecorder: TimelineRecorder = TimelineRecorder(clock),
    private val log: (String) -> Unit = {},
    val guard: StaleGroupGuard = StaleGroupGuard(clock = clock, log = log),
    val presenceTracker: GroupPresenceTracker = GroupPresenceTracker(),
) {

    private var staleGroupCheckPending = true
    private var lastRole: P2pRole = P2pRole.NONE

    var tcpListenerStarts: Int = 0
        private set

    var tcpClientStarts: Int = 0
        private set

    var removeGroupCalls: Int = 0
        private set

    val advertisedOwnerHistory = mutableListOf<Boolean>()
    val controllerStateHistory = mutableListOf<AutoMeshState>()

    init {
        // Initial advertisement at service creation is always owner=false (o0).
        advertisedOwnerHistory.add(false)
    }

    /** Grant runtime permissions so the controller enters Searching and arms the burst. */
    fun grantPermissions() {
        val prev = controller.state
        val actions = controller.onEvent(AutoMeshEvent.PermissionsChanged(true))
        recordStateAndExecute(prev, actions)
    }

    /** Mark that this process initiated group creation itself. */
    fun createGroupFromThisProcess() {
        guard.onProcessGroupCreated()
    }

    /** Feed a `requestConnectionInfo` reply into the connector/service pipeline. */
    fun onConnectionInfo(
        groupFormed: Boolean,
        isGroupOwner: Boolean,
        hasLiveLink: Boolean = false,
        ownerAddress: String? = "192.168.49.1",
    ) {
        val nowMs = clock()
        if (staleGroupCheckPending) {
            staleGroupCheckPending = false
            if (groupFormed && !hasLiveLink && !guard.processCreatedGroup) {
                log("P2P: group already formed at startup with no live link — removing the stale group")
                guard.onStaleGroupRemovalStarted(nowMs)
                removeGroupCalls++
                presenceTracker.markIntentionalRemoval()
                lastRole = P2pRole.NONE
                updateAdvertising(false)
                val prev = controller.state
                val actions = controller.onEvent(AutoMeshEvent.IntentionalGroupGone)
                recordStateAndExecute(prev, actions)
                return
            }
        }

        when (guard.onConnectionInfo(groupFormed, nowMs)) {
            StaleGroupDecision.IgnoreStaleGroupFormed -> return
            is StaleGroupDecision.ConfirmStaleGroupRemoved -> {
                presenceTracker.reset()
                lastRole = P2pRole.NONE
                updateAdvertising(false)
                val prev = controller.state
                val actions = controller.onEvent(AutoMeshEvent.IntentionalGroupGone)
                recordStateAndExecute(prev, actions)
                return
            }
            StaleGroupDecision.ProcessNormally -> Unit
        }

        if (!groupFormed) {
            if (hasLiveLink && !presenceTracker.onReport(false, nowMs)) {
                log("transient groupFormed=false; keeping the live transport for up to ${GroupPresenceTracker.DEFAULT_GRACE_MS}ms")
                return
            }
            val wasInGroup = lastRole != P2pRole.NONE
            lastRole = P2pRole.NONE
            updateAdvertising(false)
            if (wasInGroup) {
                val prev = controller.state
                val actions = controller.onEvent(AutoMeshEvent.GroupGone)
                recordStateAndExecute(prev, actions)
            }
            return
        }

        presenceTracker.onReport(true, nowMs)
        val newRole = if (isGroupOwner) P2pRole.GROUP_OWNER else P2pRole.CLIENT
        if (isGroupOwner) {
            tcpListenerStarts++
        } else if (ownerAddress != null) {
            tcpClientStarts++
        }
        if (newRole != lastRole) {
            lastRole = newRole
            val autoRole = if (isGroupOwner) AutoMeshRole.OWNER else AutoMeshRole.CLIENT
            val prev = controller.state
            val actions = controller.onEvent(AutoMeshEvent.GroupFormed(autoRole))
            recordStateAndExecute(prev, actions)
        }
        updateAdvertising(isGroupOwner)
    }

    /** Advance timers on the guard, coordinator, and controller at the current clock time. */
    fun tick() {
        val nowMs = clock()
        guard.tick(nowMs)
        val prev = controller.state
        val actions = controller.onEvent(AutoMeshEvent.Tick)
        recordStateAndExecute(prev, actions)
        coordinator.tick(nowMs, serviceWanted = controller.state is AutoMeshState.Searching)
    }

    private fun updateAdvertising(isOwner: Boolean) {
        if (advertisedOwnerHistory.lastOrNull() != isOwner) {
            advertisedOwnerHistory.add(isOwner)
        }
    }

    private fun recordStateAndExecute(previous: AutoMeshState, actions: List<AutoMeshAction>) {
        val current = controller.state
        if (controllerStateHistory.lastOrNull() != current) {
            controllerStateHistory.add(current)
        }
        timelineRecorder.onAutoStateChanged(previous, current, clock())?.let(log)
        for (action in actions) {
            when (action) {
                is AutoMeshAction.StartDiscovery -> coordinator.onEnterSearching(clock())
                is AutoMeshAction.StopDiscovery -> coordinator.stopService()
                is AutoMeshAction.CreateGroup -> guard.onProcessGroupCreated()
                is AutoMeshAction.ConnectTo -> guard.onProcessGroupCreated()
                is AutoMeshAction.RemoveGroup -> {
                    controller.markIntentionalGroupRemoval()
                    removeGroupCalls++
                }
            }
        }
    }
}
