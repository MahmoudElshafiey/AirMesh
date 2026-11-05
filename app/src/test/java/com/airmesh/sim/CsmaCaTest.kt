package com.airmesh.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsmaCaTest {

    /**
     * With CSMA/CA off the simulator keeps its original immediate-handoff
     * behavior: two simultaneous senders both succeed and collisions are not
     * modeled at all.
     */
    @Test
    fun csmaOffDeliversBothSimultaneousSendersWithNoCollisions() {
        val net = SimNetwork(seed = 30)
        net.addNode(1)
        net.addNode(2)
        net.connect(1, 2)

        net.originate(from = 1, payload = "one".toByteArray(), at = 0)
        net.originate(from = 2, payload = "two".toByteArray(), at = 0)
        net.runUntil(100)

        val metrics = net.metrics()
        assertEquals(false, net.csmaCaEnabled)
        assertEquals(2, metrics.messages.size)
        assertEquals(2, metrics.deliveredCopies)
        assertEquals(2, metrics.totalFramesOnAir)
        assertEquals(0, metrics.collisions)
        assertEquals(0.0, metrics.averageBackoffWaitMs, 0.0)
        assertEquals(0.0, metrics.channelBusyRatio, 0.0)
        assertEquals(0, metrics.framesDroppedAfterMaxRetries)
    }

    /**
     * A fully connected five-node clique gives every transmitted frame several
     * possible common receivers, so simultaneous transmissions collide. Random
     * backoff must reduce that compared with a window of one slot, where every
     * node always starts in the same slot.
     */
    @Test
    fun backoffReducesCollisionsInDenseClique() {
        val withBackoff = cliqueMetrics(
            seed = 42,
            config = CsmaCaConfig(cwMin = 16, cwMax = 64, slotTimeMs = 1, bitrate = 1_000_000),
        )
        val noBackoff = cliqueMetrics(
            seed = 42,
            config = CsmaCaConfig(cwMin = 1, cwMax = 1, slotTimeMs = 1, bitrate = 1_000_000),
        )

        assertTrue(
            "expected collisions with backoff (${withBackoff.collisions}) " +
                "to be lower than without (${noBackoff.collisions})",
            withBackoff.collisions < noBackoff.collisions,
        )
        assertTrue("no-backoff run must collide", noBackoff.collisions > 0)
        assertTrue("channel must be busy at some point", withBackoff.channelBusyRatio > 0.0)
        assertTrue(
            "backoff wait must be recorded",
            withBackoff.averageBackoffWaitMs > 0.0,
        )
    }

    /**
     * Hidden terminals: A and C can reach only B, so neither senses the other.
     * Both transmit to B at the same time, which must be detected as a collision
     * at their common receiver.
     */
    @Test
    fun hiddenTerminalsCollideAtCommonReceiver() {
        val net = hiddenTerminalNetwork(
            seed = 7,
            config = CsmaCaConfig(cwMin = 1, cwMax = 1, slotTimeMs = 1, bitrate = 1_000_000),
        )

        net.originate(from = 1, payload = "from-a".toByteArray(), at = 0)
        net.originate(from = 3, payload = "from-c".toByteArray(), at = 0)
        net.runUntil(1_000)

        assertTrue(
            "hidden terminals must collide at B",
            net.metrics().collisions > 0,
        )
    }

    /**
     * Same hidden-terminal topology with the default contention window. Backoff
     * is now the only thing separating the two senders, so whether their frames
     * overlap at B depends on the seed: some runs draw the same slot and collide,
     * others spread out and deliver.
     */
    @Test
    fun defaultContentionWindowCollisionsVaryAcrossSeeds() {
        val collisionsPerSeed = (0L until 50L).map { seed ->
            val net = hiddenTerminalNetwork(seed = seed, config = CsmaCaConfig())
            net.originate(from = 1, payload = ByteArray(64), at = 0)
            net.originate(from = 3, payload = ByteArray(64), at = 0)
            net.runUntil(20_000)
            net.metrics().collisions
        }

        val total = collisionsPerSeed.sum()
        assertTrue(
            "expected at least one collision across seeds 0..49, got $total",
            total > 0,
        )
        assertTrue(
            "expected the collision count to differ between seeds, got $collisionsPerSeed",
            collisionsPerSeed.distinct().size > 1,
        )
    }

    /**
     * Hidden terminals with the default contention window: backoff must not
     * eliminate collisions in every run, and the seeded randomness must make
     * the per-seed collision count vary instead of being constant.
     */
    @Test
    fun hiddenTerminalCollisionsVaryAcrossSeedsWithDefaultWindow() {
        val collisionsBySeed = (0L..49L).map { seed ->
            val net = hiddenTerminalNetwork(seed = seed, config = CsmaCaConfig())
            net.originate(from = 1, payload = "from-a".toByteArray(), at = 0)
            net.originate(from = 3, payload = "from-c".toByteArray(), at = 0)
            net.runUntil(60_000)
            net.metrics().collisions
        }

        val total = collisionsBySeed.sum()
        assertTrue("collisions must occur in some seed, got $collisionsBySeed", total > 0)
        assertTrue(
            "collision count must vary across seeds, got $collisionsBySeed",
            collisionsBySeed.distinct().size > 1,
        )
    }

    /** Collision doubles the window, capping at cwMax; success resets it. */
    @Test
    fun contentionWindowDoublesCapsAndResets() {
        val window = ContentionWindow(cwMin = 4, cwMax = 16)

        assertEquals(4, window.current)
        window.onCollision()
        assertEquals(8, window.current)
        window.onCollision()
        assertEquals(16, window.current)
        window.onCollision()
        assertEquals("window must cap at cwMax", 16, window.current)

        window.onSuccess()
        assertEquals("success resets to cwMin", 4, window.current)
    }

    /** Backoff is always drawn from [0, CW), so cwMin = 1 means no jitter. */
    @Test
    fun backoffStaysInsideContentionWindow() {
        val random = kotlin.random.Random(5)
        val window = ContentionWindow(cwMin = 8, cwMax = 8)

        repeat(200) {
            val backoff = window.nextBackoff(random)
            assertTrue("backoff $backoff outside [0, 8)", backoff in 0 until 8)
        }
    }

    /**
     * With a window of one slot the two hidden senders collide on every
     * attempt. After maxRetries they are dropped and counted.
     */
    @Test
    fun framesAreDroppedAfterMaxRetries() {
        val net = hiddenTerminalNetwork(
            seed = 11,
            config = CsmaCaConfig(
                cwMin = 1,
                cwMax = 1,
                slotTimeMs = 1,
                bitrate = 1_000_000,
                maxRetries = 2,
            ),
        )

        net.originate(from = 1, payload = "a".toByteArray(), at = 0)
        net.originate(from = 3, payload = "c".toByteArray(), at = 0)
        net.runUntil(1_000)

        val metrics = net.metrics()
        assertEquals(2, metrics.framesDroppedAfterMaxRetries)
        assertTrue("retries must have collided", metrics.collisions > 0)
        assertTrue("no frame may be delivered", metrics.deliveredCopies == 0)
    }

    /**
     * maxRetries is the number of retries *after* the first attempt, so a frame
     * is put on air at most maxRetries + 1 times and is then dropped once.
     *
     * A short frame from C collides with the long frame from A for as long as A
     * stays on air: with cwMin = cwMax = 1 the backoff is always zero, so every
     * retry starts in the next slot and overlaps A again. C therefore uses all
     * of its attempts and is dropped exactly once, while A only collides during
     * its one long attempt.
     */
    @Test
    fun frameIsRetriedExactlyMaxRetriesTimesBeforeBeingDropped() {
        val maxRetries = 3
        val net = hiddenTerminalNetwork(
            seed = 3,
            config = CsmaCaConfig(
                cwMin = 1,
                cwMax = 1,
                slotTimeMs = 1,
                bitrate = 1_000_000,
                maxRetries = maxRetries,
            ),
        )

        // 1000 bytes = 8 slots at 1 Mbit/s with 1 ms slots, so node 1 is on air
        // for slots 1..9 and overlaps the whole retry sequence of node 3's
        // one-slot frame. The run stops at 8, when node 3 gives up: node 1 is
        // still transmitting, so no further attempt is counted.
        net.originate(from = 1, payload = ByteArray(1_000), at = 0)
        net.originate(from = 3, payload = "c".toByteArray(), at = 0)
        net.runUntil(8)

        val metrics = net.metrics()
        assertEquals(
            "dropped frame is put on air 1 + maxRetries times, plus node 1's still " +
                "running attempt",
            (1 + maxRetries) + 1,
            metrics.totalFramesOnAir,
        )
        assertEquals("the exhausted frame is counted exactly once", 1, metrics.framesDroppedAfterMaxRetries)
    }

    @Test
    fun sameSeedProducesIdenticalCsmaMetrics() {
        val first = cliqueMetrics(seed = 42, config = CsmaCaConfig(cwMin = 16, cwMax = 64))
        val second = cliqueMetrics(seed = 42, config = CsmaCaConfig(cwMin = 16, cwMax = 64))

        assertEquals(first, second)
        assertTrue("run must produce traffic", first.totalFramesOnAir > 0)
    }

    @Test
    fun differentSeedsProduceDifferentCsmaMetrics() {
        val first = cliqueMetrics(seed = 42, config = CsmaCaConfig(cwMin = 16, cwMax = 64))
        val second = cliqueMetrics(seed = 7, config = CsmaCaConfig(cwMin = 16, cwMax = 64))

        assertNotEquals(first, second)
    }

    private fun hiddenTerminalNetwork(seed: Long, config: CsmaCaConfig): SimNetwork {
        val net = SimNetwork(seed = seed)
        net.addNode(1)
        net.addNode(2)
        net.addNode(3)
        // A-B and B-C only: A and C are hidden from each other.
        net.connect(1, 2)
        net.connect(2, 3)
        net.enableCsmaCa(config)
        return net
    }

    private fun cliqueMetrics(seed: Long, config: CsmaCaConfig): SimMetrics {
        val net = SimNetwork(seed = seed)
        (1..5).forEach { net.addNode(it) }
        for (a in 1..4) {
            for (b in a + 1..5) {
                net.connect(a, b)
            }
        }
        net.enableCsmaCa(config)

        (1..5).forEach { id ->
            net.originate(from = id, payload = "node-$id".toByteArray(), at = 0)
        }
        net.runUntil(60_000)
        return net.metrics()
    }
}