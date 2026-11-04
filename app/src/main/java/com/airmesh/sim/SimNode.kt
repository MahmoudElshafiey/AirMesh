package com.airmesh.sim

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import com.airmesh.routing.DedupCache
import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.PacketStore
import com.airmesh.routing.Router
import com.airmesh.routing.StoreAndForward

/**
 * One simulated mesh node.
 *
 * The node owns the same routing components as the real app: a [Router], a
 * [StoreAndForward] facade, an [InMemoryPacketStore], and a [DedupCache]. The
 * only simulator-specific pieces are the virtual clock and [SimTransport].
 */
class SimNode internal constructor(
    val id: Int,
    internal val network: SimNetwork,
    private val metrics: SimMetricsCollector,
    initialTtl: Int,
    maxAgeMs: Long,
    maxStoreSize: Int,
) {

    val transport = SimTransport(nodeId = id, network = network)
    val dedup = DedupCache()
    val packetStore = InMemoryPacketStore()

    val storeAndForward: StoreAndForward
    val router: Router

    init {
        val replaySend: suspend (Int, Frame) -> Boolean = { neighborId, frame ->
            val handedOff = sendFrame(neighborId, frame)
            if (handedOff) metrics.recordReplayed()
            handedOff
        }

        storeAndForward = StoreAndForward(
            store = packetStore,
            send = replaySend,
            clock = { network.clock.now },
            maxAgeMs = maxAgeMs,
            maxStoreSize = maxStoreSize,
        )

        // Router saves through this thin wrapper only so the metrics collector
        // can count store events; all behavior is delegated to the real facade.
        val countingStore = CountingPacketStore(storeAndForward) { metrics.recordStored() }

        router = Router(
            localNodeId = id,
            dedup = dedup,
            neighbors = { transport.neighbors.value },
            send = { neighborId, frame -> sendFrame(neighborId, frame) },
            deliver = { frame -> metrics.onDelivered(id, frame, network.clock.now) },
            initialTtl = initialTtl,
            packetStore = countingStore,
            clock = { network.clock.now },
        )

        transport.setPacketHandler { packet ->
            // Invalid bytes are ignored here; the real transport would also
            // hand a corrupt frame to the decoder and drop it on failure.
            FrameCodec.decode(packet.bytes).onSuccess { frame ->
                router.onFrameReceived(frame, packet.senderId)
            }
        }
        transport.start()
    }

    internal suspend fun originate(type: FrameType, payload: ByteArray): Frame =
        router.originate(type, payload)

    internal suspend fun onNeighborAppeared(neighborId: Int) {
        storeAndForward.onNeighborAppeared(neighborId)
    }

    internal suspend fun receive(senderId: Int, bytes: ByteArray) {
        transport.deliver(senderId, bytes)
    }

    private suspend fun sendFrame(neighborId: Int, frame: Frame): Boolean =
        transport.trySend(neighborId, FrameCodec.encode(frame))
}

/** Counts Router store decisions without changing PacketStore semantics. */
private class CountingPacketStore(
    private val delegate: PacketStore,
    private val onSave: () -> Unit,
) : PacketStore by delegate {

    override suspend fun save(
        frame: Frame,
        deliveredToNeighbors: Set<Int>,
        createdAt: Long,
    ) {
        onSave()
        delegate.save(frame, deliveredToNeighbors, createdAt)
    }
}
