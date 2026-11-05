package com.airmesh.sim

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import kotlin.math.ceil
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact-value checks for the scaled CSMA/CA model.
 *
 * These tests hand-compute the expected numbers from the documented rules
 * (frame bits, bitrate, slot length, and the contention window) instead of
 * asserting whatever the implementation happens to produce.
 */
class CsmaCaExactValueTest {

    /**
     * Transmission duration is the encoded frame length in bits divided by the
     * bitrate, rounded up to whole slots with a minimum of one slot.
     */
    @Test
    fun transmissionDurationIsFrameBitsOverBitrateRoundedUpToSlots() {
        // 9-byte header + 1-byte payload + 2-byte CRC = 12 bytes = 96 bits.
        val small = encodedFrame(payloadSize = 1)
        assertEquals(12, small.size)
        assertEquals(96L, small.size.toLong() * 8L)

        // 9 + 21 + 2 = 32 bytes = 256 bits.
        val large = encodedFrame(payloadSize = 21)
        assertEquals(32, large.size)
        assertEquals(256L, large.size.toLong() * 8L)

        // 1000 bits fit in one 1 ms slot: a 96-bit frame needs one slot.
        assertEquals(
            1L,
            medium(bitrate = 1_000_000, slotTimeMs = 1).transmissionDurationMs(small),
        )
        // 100 bits per slot: 96 bits still need exactly one slot.
        assertEquals(
            1L,
            medium(bitrate = 100_000, slotTimeMs = 1).transmissionDurationMs(small),
        )
        // 10 bits per slot: 96 bits need 9.6 slots, rounded up to 10.
        assertEquals(
            10L,
            medium(bitrate = 10_000, slotTimeMs = 1).transmissionDurationMs(small),
        )
        // 256 bits at 100 bits per slot is 2.56 slots, rounded up to 3.
        assertEquals(
            3L,
            medium(bitrate = 100_000, slotTimeMs = 1).transmissionDurationMs(large),
        )
        // A slot lasts 10 ms here, so 1000 bits fit per slot and 96 bits take
        // one slot = 10 ms.
        assertEquals(
            10L,
            medium(bitrate = 100_000, slotTimeMs = 10).transmissionDurationMs(small),
        )
        // The result is never less than one slot: 88 bits at 128 bits per slot.
        val minimal = encodedFrame(payloadSize = 0)
        assertEquals(FrameCodec.MIN_FRAME_SIZE, minimal.size)
        assertEquals(
            1L,
            medium(bitrate = 128_000, slotTimeMs = 1).transmissionDurationMs(minimal),
        )
    }

    /**
     * With the same seed the random draws are identical, so doubling the slot
     * length must double the time spent in backoff. A single idle sender makes
     * the wait a pure random backoff with no busy-channel time mixed in.
     */
    @Test
    fun doublingSlotTimeDoublesPureBackoffWaitingForTheSameSeed() {
        val fast = singleIdleFrameRun(seed = 3, slotTimeMs = 1)
        val slow = singleIdleFrameRun(seed = 3, slotTimeMs = 2)

        assertTrue("the run must actually back off", fast.averageBackoffWaitMs > 0.0)
        assertEquals(0, fast.collisions)
        assertEquals(0, slow.collisions)
        assertEquals(
            "same draw, twice the slot length must mean twice the wait",
            2.0,
            slow.averageBackoffWaitMs / fast.averageBackoffWaitMs,
            0.0,
        )
    }

    /**
     * One sender, ten known frames, no contention: the busy ratio is the union
     * of the ten transmission intervals divided by the elapsed virtual time.
     */
    @Test
    fun channelBusyRatioIsHandComputedBusyTimeOverRunTime() {
        val bitrate = 1_000_000L
        val slotTimeMs = 1L
        val runMs = 10_000L
        val frameCount = 10

        val net = SimNetwork(seed = 21)
        net.addNode(1)
        net.addNode(2)
        net.connect(1, 2)
        net.enableCsmaCa(
            CsmaCaConfig(
                cwMin = 1,
                cwMax = 1,
                slotTimeMs = slotTimeMs,
                bitrate = bitrate,
            ),
        )

        // Frames are 100 ms apart, so no two transmissions can overlap.
        repeat(frameCount) { index ->
            net.originate(from = 1, payload = ByteArray(8) { index.toByte() }, at = index * 100L)
        }
        net.runUntil(runMs)

        // 9 + 8 + 2 = 19 bytes = 152 bits. At 1000 bits per slot that is one
        // 1 ms slot per frame.
        val frameBytes = encodedFrame(payloadSize = 8)
        assertEquals(19, frameBytes.size)
        val perFrameMs = expectedDurationMs(frameBytes.size, bitrate, slotTimeMs)
        assertEquals(1L, perFrameMs)

        val metrics = net.metrics()
        assertEquals(frameCount, metrics.totalFramesOnAir)
        assertEquals(0, metrics.collisions)
        assertEquals(
            "busy time ${frameCount * perFrameMs} ms over $runMs ms",
            (frameCount * perFrameMs).toDouble() / runMs.toDouble(),
            metrics.channelBusyRatio,
            1e-12,
        )
    }

    /**
     * Hand-computed waiting with cwMin = cwMax = 1, so the random backoff is
     * always zero and every millisecond of wait comes from sensing a busy
     * channel.
     *
     * Two nodes share one link. Node 1 starts a 40-byte payload at t = 0:
     * 9 + 40 + 2 = 51 bytes = 408 bits, and at 100 kbit/s with 1 ms slots
     * (100 bits per slot) that needs ceil(4.08) = 5 slots, so it occupies
     * [1, 6). Node 3 originates a 1-byte payload at t = 2, after node 1 is
     * already on the air. It senses the channel busy in slots 2, 3, 4 and 5,
     * waits 4 ms, and sends its 1 ms frame in [6, 7).
     *
     * Completed frames and waits: node 1: 0 ms, node 3: 4 ms. Mean 4 / 2 = 2.0.
     */
    @Test
    fun averageBackoffWaitMatchesHandComputedUnitWindowScenario() {
        // TTL 1 keeps the run to the two originated transmissions: the receiver
        // delivers the frame but does not forward it.
        val net = SimNetwork(seed = 22, initialTtl = 1)
        net.addNode(1)
        net.addNode(3)
        net.connect(1, 3)
        net.enableCsmaCa(
            CsmaCaConfig(
                cwMin = 1,
                cwMax = 1,
                slotTimeMs = 1,
                bitrate = 100_000,
            ),
        )

        net.originate(from = 1, payload = ByteArray(40), at = 0)
        net.originate(from = 3, payload = ByteArray(1), at = 2)
        net.runUntil(100)

        val metrics = net.metrics()
        assertEquals(0, metrics.collisions)
        assertEquals(0, metrics.framesDroppedAfterMaxRetries)
        assertEquals(2, metrics.totalFramesOnAir)
        assertEquals(
            "two frames waited 0 + 4 ms",
            2.0,
            metrics.averageBackoffWaitMs,
            0.0,
        )
        // The two attempts tile [1, 7), so 6 of the 100 ms run are busy.
        assertEquals(0.06, metrics.channelBusyRatio, 0.0)
    }

    private fun medium(bitrate: Long, slotTimeMs: Long): SimMediumAccess {
        val config = CsmaCaConfig(
            cwMin = 1,
            cwMax = 1,
            slotTimeMs = slotTimeMs,
            bitrate = bitrate,
        )
        return SimMediumAccess(
            clock = SimClock(),
            events = SimEventQueue(),
            random = Random(1),
            config = config,
            neighborsOf = { _ -> emptySet() },
            recordAttempt = {},
            deliver = { _, _, _ -> },
        )
    }

    private fun singleIdleFrameRun(seed: Long, slotTimeMs: Long): SimMetrics {
        val net = SimNetwork(seed = seed)
        net.addNode(1)
        net.addNode(2)
        net.connect(1, 2)
        net.enableCsmaCa(
            CsmaCaConfig(
                cwMin = 16,
                cwMax = 16,
                slotTimeMs = slotTimeMs,
                bitrate = 1_000_000,
            ),
        )
        net.originate(from = 1, payload = ByteArray(8), at = 0)
        net.runUntil(1_000)
        return net.metrics()
    }

    private fun encodedFrame(payloadSize: Int): ByteArray = FrameCodec.encode(
        Frame(
            version = 1,
            type = FrameType.MSG,
            flags = 0,
            nodeId = 1,
            seq = 1,
            ttl = 8,
            payload = ByteArray(payloadSize),
        ),
    )

    /** Hand-computed duration: ceil(bits / bits-per-slot), at least one slot. */
    private fun expectedDurationMs(frameBytes: Int, bitrate: Long, slotTimeMs: Long): Long {
        val bits = frameBytes.toLong() * 8L
        val bitsPerSlot = bitrate.toDouble() * slotTimeMs.toDouble() / 1_000.0
        val slots = maxOf(1L, ceil(bits / bitsPerSlot).toLong())
        return slots * slotTimeMs
    }
}
