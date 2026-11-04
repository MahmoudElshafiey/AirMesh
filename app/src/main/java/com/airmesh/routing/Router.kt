package com.airmesh.routing

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameType

/**
 * Flooding router for the AirMesh overlay.
 *
 * Every frame is flooded to all neighbors with a decrementing TTL. The
 * [DedupCache] stops a frame from being processed twice, and the sender of a
 * received frame is skipped when forwarding so the frame does not bounce
 * straight back.
 *
 * The router is pure Kotlin and knows nothing about sockets or Android: the
 * caller supplies the current neighbor set and a suspend send callback. That
 * callback is where encoding and the actual transport belong, which keeps this
 * class unit-testable on the JVM.
 *
 * Callbacks may suspend and may therefore wait for I/O. They must not throw;
 * [send] reports failure by returning false. Callbacks can run concurrently,
 * so the caller must make them thread-safe.
 *
 * When a frame cannot be handed to every eligible neighbor (because there is
 * none, or because a send returned false), the already-decremented outgoing
 * copy is written to [packetStore] for store-and-forward replay. Neighbors that
 * did receive it are recorded in the entry so they are never sent it again.
 *
 * @property localNodeId this node's 16-bit address, used as the origin of
 *   frames created by [originate].
 * @property dedup duplicate-suppression cache shared by all received frames.
 * @property neighbors returns the node ids that are directly reachable now.
 * @property send hands one frame to one neighbor; true means handed off.
 * @property deliver delivers a locally addressed/visible frame to the app.
 * @property initialTtl hop limit for newly originated frames.
 * @property packetStore optional store-and-forward queue. Without one, frames
 *   that cannot be forwarded are simply dropped, as before.
 */
class Router(
    private val localNodeId: Int,
    private val dedup: DedupCache,
    private val neighbors: () -> Set<Int>,
    private val send: suspend (neighborId: Int, frame: Frame) -> Boolean,
    private val deliver: (Frame) -> Unit = {},
    private val initialTtl: Int = DEFAULT_TTL,
    private val packetStore: PacketStore? = null,
) {

    companion object {
        /** Default hop limit for frames created by [originate]. */
        const val DEFAULT_TTL = 8

        /**
         * Wire-format version written into originated frames. The codec does
         * not own a version constant, so the routing layer states it here.
         */
        const val PROTOCOL_VERSION = 1

        private const val MAX_SEQ = 0xFFFF
    }

    /** Sequence number of the last frame this node originated. */
    private var lastSeq = 0

    init {
        require(localNodeId in 0..MAX_SEQ) {
            "localNodeId must fit in 16 bits: $localNodeId"
        }
        require(initialTtl in 1..0xFF) { "initialTtl must be in 1..255: $initialTtl" }
    }

    /**
     * Handles one frame received from [fromNeighbor].
     *
     * Order matters:
     * 1. Drop duplicates before doing any work (a flood can arrive many times).
     * 2. Deliver MSG/LOCATION to the local app; ACK/HELLO are protocol traffic.
     * 3. If TTL allows another hop, forward a copy with `ttl - 1` to every
     *    neighbor except the one it came from. `ttl <= 1` stops here.
     *
     * The delivered frame keeps its original TTL; only the forwarded copy is
     * decremented. Payload bytes are not copied and are treated as immutable.
     */
    suspend fun onFrameReceived(frame: Frame, fromNeighbor: Int) {
        // Seen before? Nothing to deliver and nothing to forward.
        if (dedup.seen(frame.nodeId, frame.seq)) return

        if (frame.type == FrameType.MSG || frame.type == FrameType.LOCATION) {
            deliver(frame)
        }

        // TTL counts remaining hops. At 1 (or an invalid 0) this node is the
        // last hop, so the frame is consumed locally.
        if (frame.ttl <= 1) return

        val forwarded = frame.copy(ttl = frame.ttl - 1)
        val targets = neighbors() - fromNeighbor

        var failed = false
        val deliveredTo = mutableSetOf<Int>()
        for (neighbor in targets) {
            if (send(neighbor, forwarded)) {
                deliveredTo += neighbor
            } else {
                failed = true
            }
        }

        // A hop-1 copy cannot be forwarded by anyone, so it is not worth
        // storing. The source already has the frame and is marked delivered.
        if (forwarded.ttl > 1 && (targets.isEmpty() || failed)) {
            packetStore?.save(forwarded, deliveredTo + fromNeighbor)
        }
    }

    /**
     * Creates a new frame authored by this node and floods it to every current
     * neighbor at [initialTtl].
     *
     * Sequence numbers are per originator and wrap at 16 bits: the first frame
     * is 1, then 2, ... 65535, 0, 1, ... The frame is marked as seen before it
     * is sent, so if the mesh floods a copy back to us it is dropped.
     *
     * @return the created frame, useful for callers that also store it.
     */
    suspend fun originate(type: FrameType, payload: ByteArray): Frame {
        val seq = nextSeq()
        val frame = Frame(
            version = PROTOCOL_VERSION,
            type = type,
            flags = 0,
            nodeId = localNodeId,
            seq = seq,
            ttl = initialTtl,
            payload = payload,
        )

        // Our own frames must not be delivered again when they loop back.
        dedup.seen(localNodeId, seq)

        var failed = false
        val deliveredTo = mutableSetOf<Int>()
        val targets = neighbors()
        for (neighbor in targets) {
            if (send(neighbor, frame)) {
                deliveredTo += neighbor
            } else {
                failed = true
            }
        }

        if (frame.ttl > 1 && (targets.isEmpty() || failed)) {
            packetStore?.save(frame, deliveredTo)
        }
        return frame
    }

    /**
     * Allocates the next 16-bit sequence number, wrapping 65535 -> 0.
     * Synchronized so concurrent [originate] calls cannot share a sequence.
     */
    @Synchronized
    private fun nextSeq(): Int {
        lastSeq = (lastSeq + 1) and MAX_SEQ
        return lastSeq
    }
}
