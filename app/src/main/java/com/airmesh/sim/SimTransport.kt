package com.airmesh.sim

import com.airmesh.transport.ReceivedPacket
import com.airmesh.transport.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Transport implementation for one simulated node.
 *
 * Outbound traffic goes through [SimNetwork.transmit], which applies the link
 * loss and latency. Inbound traffic is delivered by the event loop through
 * [deliver]; that invokes the node's suspend packet handler synchronously so
 * routing order is deterministic, and also publishes the packet on [incoming]
 * for observers.
 *
 * Neighbor sets are sorted, so a node with several neighbors always iterates
 * them in the same order.
 */
class SimTransport internal constructor(
    val nodeId: Int,
    private val network: SimNetwork,
) : Transport {

    private val neighborState = MutableStateFlow<Set<Int>>(emptySet())
    override val neighbors: StateFlow<Set<Int>> = neighborState.asStateFlow()

    private val incomingFlow = MutableSharedFlow<ReceivedPacket>(
        replay = 0,
        extraBufferCapacity = 128,
    )
    override val incoming: Flow<ReceivedPacket> = incomingFlow.asSharedFlow()

    private var running = false
    private var packetHandler: (suspend (ReceivedPacket) -> Unit)? = null

    internal fun setPacketHandler(handler: suspend (ReceivedPacket) -> Unit) {
        packetHandler = handler
    }

    internal fun addNeighbor(neighborId: Int) {
        neighborState.value = (neighborState.value + neighborId).toSortedSet()
    }

    internal fun removeNeighbor(neighborId: Int) {
        neighborState.value = (neighborState.value - neighborId).toSortedSet()
    }

    override suspend fun broadcast(bytes: ByteArray) {
        // Snapshot the sorted set so a send cannot change the iteration target.
        neighborState.value.forEach { neighborId -> send(neighborId, bytes) }
    }

    override suspend fun send(neighborId: Int, bytes: ByteArray) {
        trySend(neighborId, bytes)
    }

    /**
     * Returns true when the frame was handed to an existing link. A frame that
     * is later lost on the air still returned true: loss is not an unreachable
     * neighbor, so the routing layer must not queue a duplicate retry for it.
     */
    internal fun trySend(neighborId: Int, bytes: ByteArray): Boolean {
        if (!running) return false
        if (neighborId !in neighborState.value) return false
        return network.transmit(fromNodeId = nodeId, toNodeId = neighborId, bytes = bytes)
    }

    override fun start() {
        neighborState.value = network.neighborsOf(nodeId)
        running = true
    }

    override fun stop() {
        running = false
        neighborState.value = emptySet()
    }

    /** Delivers one packet synchronously from the virtual-time event loop. */
    internal suspend fun deliver(senderId: Int, bytes: ByteArray) {
        if (!running) return
        val packet = ReceivedPacket(senderId = senderId, bytes = bytes)
        packetHandler?.invoke(packet)
        incomingFlow.tryEmit(packet)
    }
}
