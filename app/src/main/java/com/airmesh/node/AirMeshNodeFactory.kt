package com.airmesh.node

import android.content.Context
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
): AirMeshNode {
    val transport = TcpMeshTransport(localNodeId = nodeId, dispatcher = dispatcher)
    return AirMeshNode(
        localNodeId = nodeId,
        transport = transport,
        eventLog = eventLog,
        storeFactory = { send -> createRoomBackedStore(context, send) },
        dispatcher = dispatcher,
    )
}
