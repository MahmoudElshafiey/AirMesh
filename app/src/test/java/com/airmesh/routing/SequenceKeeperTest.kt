package com.airmesh.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK T1 part B2: the high-water-mark arithmetic of [SequenceKeeper] — restarts never reuse a
 * number, crashes lose at most PERSIST_EVERY seqs of progress, and 16-bit wrap-around keeps the
 * resumed start strictly ahead (modulo 65536) of everything ever sent.
 */
class SequenceKeeperTest {

    private class InMemoryStorage(initialRaw: String? = null) : SequenceStorage {
        @Volatile var raw: String? = initialRaw
            private set
        val writes = mutableListOf<Int>()

        override fun readRaw(): String? = raw
        override fun write(value: Int) {
            raw = value.toString()
            writes += value
        }
    }

    /**
     * Simulates the Router feeding allocations back. [after] is how many allocations this keeper
     * already had, so consecutive calls continue the sequence instead of restarting at seq 1.
     */
    private fun allocate(keeper: SequenceKeeper, count: Int, after: Int = 0): List<Int> =
        (0 until count).map { i ->
            val seq = (keeper.initialSeq + 1 + after + i) and SequenceKeeper.MAX_SEQ
            keeper.onAllocated(seq)
            seq
        }

    @Test
    fun missingStorageStartsAtOne() {
        val storage = InMemoryStorage()
        val keeper = SequenceKeeper(storage)
        assertEquals("no stored mark means the historical start", 0, keeper.initialSeq)
        assertEquals("the first frame is seq 1", listOf(1), allocate(keeper, 1))
    }

    @Test
    fun corruptOrOutOfRangeStorageStartsAtOne() {
        for (raw in listOf("garbage", "", "99999", "-5", "65536")) {
            val keeper = SequenceKeeper(InMemoryStorage(raw))
            assertEquals("stored value $raw is treated as missing", 0, keeper.initialSeq)
        }
    }

    @Test
    fun restartAfterCleanStopNeverReusesANumber() {
        val storage = InMemoryStorage()
        val firstRun = SequenceKeeper(storage)
        allocate(firstRun, 50) // seqs 1..50
        firstRun.stop()
        assertEquals("clean stop persists last used + margin", "82", storage.raw)

        val secondRun = SequenceKeeper(storage)
        assertEquals("second run resumes at stored + gap", 146, secondRun.initialSeq)
        val next = allocate(secondRun, 3)
        assertEquals("the first post-restart seq is beyond everything used before", listOf(147, 148, 149), next)
    }

    @Test
    fun crashWithoutCleanStopStillStartsAboveTheLastUsed() {
        val storage = InMemoryStorage()
        val crashedRun = SequenceKeeper(storage)
        allocate(crashedRun, 30) // seqs 1..30; the periodic persist fired at #16 (mark 48), no stop()
        assertEquals("the crash leaves only the periodic mark", "48", storage.raw)

        val restarted = SequenceKeeper(storage)
        assertEquals(112, restarted.initialSeq)
        val next = allocate(restarted, 1)
        assertTrue(
            "first post-crash seq (113) is above the last used (30)",
            next.single() > 30 && !next.containsAll((1..30).toList()),
        )
    }

    @Test
    fun wrapAroundKeepsTheStartAheadOfTheHighWaterMark() {
        // Process 1 had climbed to the top of the 16-bit space: mark 65520 (last used ~65488).
        val storage = InMemoryStorage("65520")
        val keeper = SequenceKeeper(storage)
        assertEquals("the resume point wraps instead of overflowing", 48, keeper.initialSeq)

        // Run across the wrap: seqs 49..65535 then 0..10.
        allocate(keeper, (65_535 - 49 + 1) + 11)
        assertTrue("a persisted mark wrapped below the start", storage.writes.any { it < 48 })

        keeper.stop() // last used was seq 10 -> mark 42
        assertEquals("the post-wrap stop persists a wrapped mark", "42", storage.raw)

        val restarted = SequenceKeeper(storage)
        assertEquals(106, restarted.initialSeq)
        val firstAfterRestart = (restarted.initialSeq + 1) and 0xFFFF
        // The peer's dedup window holds the newest 1024 seqs: 64523..65535 then 0..10.
        val window = ((64_523..65_535).toList() + (0..10)).toSet()
        assertFalse(
            "the first post-restart seq must not be inside the dedup window",
            firstAfterRestart in window,
        )
    }

    @Test
    fun persistsEverySixteenAllocationsWithTheMarginAhead() {
        var nowMs = 1_000L
        val storage = InMemoryStorage()
        val keeper = SequenceKeeper(storage) { nowMs }

        allocate(keeper, 15)
        assertTrue("no persist before the 16th allocation", storage.writes.isEmpty())
        assertNull("the clock is untouched while nothing persists", keeper.lastPersistedAtMs)

        nowMs += 50
        allocate(keeper, 1, after = 15) // the 16th allocation: seq 16 -> mark 48
        assertEquals(listOf(48), storage.writes)
        assertEquals("the persist is stamped with the fake clock", 1_050L, keeper.lastPersistedAtMs)

        nowMs += 50
        allocate(keeper, 16, after = 16) // seqs 17..32 -> mark 64
        assertEquals(listOf(48, 64), storage.writes)
        assertEquals(1_100L, keeper.lastPersistedAtMs)
    }

    @Test
    fun cleanStopPersistsEvenWithoutNewAllocations() {
        val storage = InMemoryStorage("200") // resumed at 264; nothing allocated yet
        val keeper = SequenceKeeper(storage)
        assertEquals(264, keeper.initialSeq)
        keeper.stop()
        assertEquals("stop persists current + margin", "296", storage.raw)

        // A fresh node with no traffic still gets a mark on its first clean stop.
        val emptyStorage = InMemoryStorage()
        SequenceKeeper(emptyStorage).stop()
        assertEquals("32", emptyStorage.raw)
    }
}
