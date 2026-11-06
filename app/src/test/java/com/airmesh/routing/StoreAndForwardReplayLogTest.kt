package com.airmesh.routing

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * JVM tests for the replay summary logging rules: a summary line appears when
 * the neighbor just appeared, while frames stay pending for it, or when the
 * queue size moved since its last summary. Quiet periodic ticks log nothing;
 * per-frame "replay TX" lines are always kept.
 */
class StoreAndForwardReplayLogTest {

    /** Wires a fake store, a recording send callback, and a line-recording log. */
    private class Harness {
        var now = 1_000_000L
        val store = InMemoryPacketStore()
        val sent = ConcurrentLinkedQueue<Pair<Int, Frame>>()
        val failedNeighbors: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        val logLines = mutableListOf<String>()

        val sf = StoreAndForward(
            store = store,
            send = { neighborId, frame ->
                sent += neighborId to frame
                neighborId !in failedNeighbors
            },
            clock = { now },
            log = { line -> logLines += line },
        )

        fun frame(seq: Int): Frame = Frame(
            version = 1,
            type = FrameType.MSG,
            flags = 0,
            nodeId = 1,
            seq = seq,
            ttl = 4,
            payload = "x".toByteArray(),
        )

        fun save(frame: Frame, deliveredToNeighbors: Set<Int> = emptySet()) = runBlocking {
            sf.save(frame, deliveredToNeighbors, createdAt = now)
        }

        fun appeared(neighborId: Int) = runBlocking {
            sf.onNeighborAppeared(neighborId, ReplayTrigger.APPEARED)
        }

        fun periodic(neighborId: Int) = runBlocking {
            sf.onNeighborAppeared(neighborId, ReplayTrigger.PERIODIC)
        }

        val summaries get() = logLines.filter { it.startsWith("replay: neighbor") }
    }

    @Test
    fun appearanceAlwaysLogsTheSummary() {
        val harness = Harness()
        harness.save(harness.frame(seq = 1))

        harness.appeared(7)

        assertEquals(
            listOf("replay: neighbor 7: 1 stored, 1 pending for this neighbor"),
            harness.summaries,
        )
    }

    @Test
    fun quietPeriodicTickLogsNothing() {
        val harness = Harness()
        harness.save(harness.frame(seq = 1))
        harness.appeared(7) // sends the frame; it is now delivered to 7
        assertEquals(2, harness.logLines.size) // summary + one "replay TX ... ok"

        harness.periodic(7)

        // Nothing pending and the queue size did not move: no new line at all.
        assertEquals(1, harness.summaries.size)
        assertEquals(2, harness.logLines.size)
    }

    @Test
    fun pendingFramesKeepTheSummaryVisibleOnEveryTick() {
        val harness = Harness()
        harness.failedNeighbors += 7 // every send to 7 fails; the frame stays queued
        harness.save(harness.frame(seq = 1))

        harness.appeared(7)
        harness.periodic(7)
        harness.periodic(7)

        assertEquals(3, harness.summaries.size)
        harness.summaries.forEach { line ->
            assertEquals("replay: neighbor 7: 1 stored, 1 pending for this neighbor", line)
        }
    }

    @Test
    fun queueSizeChangeIsLoggedOnceEvenWithNoPendingFrames() {
        val harness = Harness()
        harness.save(harness.frame(seq = 1))
        harness.appeared(7) // delivered; last summary said "1 stored"

        // A new frame that is already marked delivered to 7: the queue grows,
        // but nothing becomes pending for it.
        harness.save(harness.frame(seq = 2), deliveredToNeighbors = setOf(7))

        harness.periodic(7)
        assertEquals(
            listOf("replay: neighbor 7: 2 stored, 0 pending for this neighbor"),
            harness.summaries.drop(1),
        )

        // The anchor moved with the log; an unchanged tick stays quiet.
        harness.periodic(7)
        assertEquals(2, harness.summaries.size)
    }

    @Test
    fun perFrameLinesAreKeptForAppearanceAndPeriodicTicks() {
        val harness = Harness()
        harness.failedNeighbors += 7
        harness.save(harness.frame(seq = 5))

        harness.appeared(7)
        harness.periodic(7)

        assertEquals(2, harness.logLines.count { it == "replay TX seq=5 -> 7 failed" })
    }

    @Test
    fun defaultTriggerIsAppearance() {
        val harness = Harness()
        harness.save(harness.frame(seq = 1))
        harness.appeared(7) // delivered; a periodic call here would be quiet

        runBlocking { harness.sf.onNeighborAppeared(7) } // no explicit trigger

        assertEquals(2, harness.summaries.size)
    }
}
