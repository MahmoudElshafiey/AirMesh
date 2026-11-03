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
 * caller supplies the current neighbor set and a send callback. That callback
 * is where framing/encoding and the actual transport belong, which keeps this
 * class unit-testable on the JVM.
 *
 * Callbacks are synchronous and must not block; the transport adapter is
 * responsible for queueing sends on its own coroutine. They must not throw.
 *
 * @property localNodeId this node's 16-bit address, used as the origin of
 *   frames created by [originate].
 * @property dedup duplicate-suppression cache shared by all received frames.
 * @property neighbors returns the node ids that are directly reachable now.
 * @property send hands one frame to one neighbor; best-effort, no ACK.
 * @property deliver delivers a locally addressed/visible frame to the app.
 * @property initialTtl hop limit for newly originated frames.
 */
class Router(
    private val localNodeId: Int,
    private val dedup: DedupCache,
    private val neighbors: () -> Set<Int>,
    private val send: (neighborId: Int, frame: Frame) -> Unit,
    private val deliver: (Frame) -> Unit = {},
    private val initialTtl: Int = DEFAULT_TTL,
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
    fun onFrameReceived(frame: Frame, fromNeighbor: Int) {
        // Seen before? Nothing to deliver and nothing to forward.
        if (dedup.seen(frame.nodeId, frame.seq)) return

        if (frame.type == FrameType.MSG || frame.type == FrameType.LOCATION) {
            deliver(frame)
        }

        // TTL counts remaining hops. At 1 (or an invalid 0) this node is the
        // last hop, so the frame is consumed locally.
        if (frame.ttl <= 1) return

        val forwarded = frame.copy(ttl = frame.ttl - 1)
        neighbors().forEach { neighbor ->
            // Do not echo a flooded frame back to the neighbor it came from.
            if (neighbor != fromNeighbor) {
                send(neighbor, forwarded)
            }
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
    fun originate(type: FrameType, payload: ByteArray): Frame {
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

        neighbors().forEach { neighbor -> send(neighbor, frame) }
        return frame
    }

    /** Allocates the next 16-bit sequence number, wrapping 65535 -> 0. */
    @Synchronized
    private fun nextSeq(): Int {
        lastSeq = (lastSeq + 1) and MAX_SEQ
        return lastSeq
    }
}
