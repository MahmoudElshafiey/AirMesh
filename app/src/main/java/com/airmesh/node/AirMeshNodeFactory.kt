package com.airmesh.node

import android.content.Context
import com.airmesh.profile.SharedPrefsProfileStorage
import com.airmesh.routing.SequenceKeeper
import com.airmesh.storage.createRoomBackedStore
import com.airmesh.transport.TcpMeshTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Android composition root for [AirMeshNode].
 *
 * The transport and the Room-backed store are created here so the node core
 * stays free of Android imports. `createRoomBackedStore` is called without a
 * clock argument on purpose: production uses the default system clock, never a
 * simulator's virtual clock.
 */
fun createAirMeshNode(
    context: Context,
    nodeId: Int,
    eventLog: EventLog,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    // TASK D2 part 4 pass-throughs; the defaults reproduce the pre-D2 behavior.
    profileAnnounceIntervalMs: Long = AirMeshNode.DEFAULT_PROFILE_ANNOUNCE_INTERVAL_MS,
    announceWithoutName: Boolean = false,
    // TASK U5 part 5 pass-throughs; 0/0 keeps today's transport behavior (no keepalive).
    keepAliveIntervalMs: Long = 0L,
    deadAfterMs: Long = 0L,
): AirMeshNode {
    // Share the node's event log with the transport so socket events (connect
    // phases, HELLO exchange, accept loop) show up in the on-device log.
    val transport = TcpMeshTransport(
        localNodeId = nodeId,
        dispatcher = dispatcher,
        eventLog = eventLog,
        keepAliveIntervalMs = keepAliveIntervalMs,
        deadAfterMs = deadAfterMs,
    )
    return AirMeshNode(
        localNodeId = nodeId,
        transport = transport,
        eventLog = eventLog,
        storeFactory = { send -> createRoomBackedStore(context, send, log = eventLog::log) },
        dispatcher = dispatcher,
        // Persist the node's own name/color and the profile directory across restarts.
        profileStorage = SharedPrefsProfileStorage(context),
        profileAnnounceIntervalMs = profileAnnounceIntervalMs,
        announceWithoutName = announceWithoutName,
        // TASK T1 part B2: the origin sequence resumes above the persisted high-water mark,
        // so a restarted node never reuses a seq a peer's dedup window may still hold.
        sequenceKeeper = SequenceKeeper(SharedPreferencesSequenceStorage(context)),
    )
}
