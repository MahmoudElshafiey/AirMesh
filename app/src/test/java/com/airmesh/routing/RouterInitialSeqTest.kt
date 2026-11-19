package com.airmesh.routing

import com.airmesh.protocol.FrameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TASK T1 part B1: the Router may resume its origin sequence from a stored value and report
 * every allocation. Defaults reproduce the historical start-at-1 behavior exactly.
 */
class RouterInitialSeqTest {

    private class Harness(
        initialSeq: Int = 0,
        onSeqAllocated: (Int) -> Unit = {},
    ) {
        val allocated = CopyOnWriteArrayList<Int>()
        val router = Router(
            localNodeId = 10,
            dedup = DedupCache(64),
            neighbors = { emptySet() }, // no flooding; we only inspect the created frame
            send = { _, _ -> true },
            initialSeq = initialSeq,
            onSeqAllocated = { seq -> allocated += seq; onSeqAllocated(seq) },
        )

        fun originate(): Int = runBlocking { router.originate(FrameType.MSG, ByteArray(0)).seq }
    }

    @Test
    fun defaultStartsAtOneAsBefore() {
        val h = Harness()
        assertEquals("first frame is seq 1 with the default", 1, h.originate())
        assertEquals("second frame is seq 2 with the default", 2, h.originate())
    }

    @Test
    fun initialSeqResumesAfterTheStoredValue() {
        val h = Harness(initialSeq = 100)
        assertEquals("first frame after a restart at 100 is 101", 101, h.originate())
        assertEquals("the following frame is 102", 102, h.originate())
    }

    @Test
    fun wrapAroundAt65535StillWorks() {
        val h = Harness(initialSeq = 65_534)
        assertEquals("65534 resumes at 65535", 65_535, h.originate())
        assertEquals("then wraps to 0", 0, h.originate())
        assertEquals("and continues at 1", 1, h.originate())

        // The historical default path still wraps too.
        val plain = Harness()
        repeat(65_534) { plain.originate() }
        assertEquals(65_535, plain.originate())
        assertEquals("default wrap 65535 -> 0", 0, plain.originate())
    }

    @Test
    fun callbackFiresForEveryAllocationInOrder() {
        val extra = CopyOnWriteArrayList<Int>()
        val h = Harness(initialSeq = 10, onSeqAllocated = { seq -> extra += seq })
        repeat(5) { h.originate() }
        assertEquals("one callback per allocation", listOf(11, 12, 13, 14, 15), extra.toList())
        assertEquals("the harness sees the same sequence", listOf(11, 12, 13, 14, 15), h.allocated)
    }

    @Test
    fun initialSeqMustFitInSixteenBits() {
        assertThrows("negative initialSeq is rejected", IllegalArgumentException::class.java) { Harness(initialSeq = -1) }
        assertThrows("initialSeq above 65535 is rejected", IllegalArgumentException::class.java) { Harness(initialSeq = 65_536) }
    }
}
