package com.airmesh.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray

class DedupCacheTest {

    @Test
    fun firstSightingIsNewAndRepeatIsDuplicate() {
        val cache = DedupCache(capacity = 8)

        assertFalse(cache.seen(nodeId = 7, seq = 1))
        assertTrue(cache.seen(nodeId = 7, seq = 1))
    }

    @Test
    fun nodeIdAndSeqAreBothPartOfTheKey() {
        val cache = DedupCache(capacity = 8)

        assertFalse(cache.seen(nodeId = 1, seq = 100))
        assertFalse(cache.seen(nodeId = 2, seq = 100)) // same seq, other origin
        assertFalse(cache.seen(nodeId = 1, seq = 101)) // same origin, other seq

        assertTrue(cache.seen(nodeId = 1, seq = 100))
        assertTrue(cache.seen(nodeId = 2, seq = 100))
        assertTrue(cache.seen(nodeId = 1, seq = 101))
    }

    @Test
    fun leastRecentlyUsedEntryIsEvicted() {
        val cache = DedupCache(capacity = 2)

        assertFalse(cache.seen(nodeId = 1, seq = 1)) // A
        assertFalse(cache.seen(nodeId = 1, seq = 2)) // B

        // A hit refreshes A, so B becomes the least recently used entry.
        assertTrue(cache.seen(nodeId = 1, seq = 1))

        // C must evict B, not A.
        assertFalse(cache.seen(nodeId = 1, seq = 3))

        assertTrue(cache.seen(nodeId = 1, seq = 1))
        assertTrue(cache.seen(nodeId = 1, seq = 3))

        // B was evicted, so it is reported as new again.
        assertFalse(cache.seen(nodeId = 1, seq = 2))
    }

    @Test
    fun defaultCapacityIs1024() {
        assertEquals(1024, DedupCache.DEFAULT_CAPACITY)

        val cache = DedupCache()

        // Fill exactly the documented default capacity.
        repeat(DedupCache.DEFAULT_CAPACITY) { seq ->
            assertFalse(cache.seen(nodeId = 1, seq = seq))
        }

        // Nothing has been evicted yet.
        assertTrue(cache.seen(nodeId = 1, seq = 0))

        // The next distinct frame evicts the least recently used entry (seq 1,
        // because seq 0 was just refreshed). That proves the bound is 1024.
        assertFalse(cache.seen(nodeId = 1, seq = DedupCache.DEFAULT_CAPACITY))
        assertTrue(cache.seen(nodeId = 1, seq = 0))
        assertFalse(cache.seen(nodeId = 1, seq = 1))
    }

    @Test
    fun concurrentSeenReportsExactlyOneNewResultPerKey() {
        val cache = DedupCache(capacity = 64)
        val keys = listOf(1 to 1, 1 to 2, 2 to 1, 2 to 2)
        val threadCount = 8
        val iterationsPerThread = 1_000
        val newCounts = AtomicIntegerArray(keys.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            val futures = (0 until threadCount).map {
                executor.submit {
                    // Keep every thread blocked until all are ready, so the
                    // seen() calls really overlap.
                    start.await()
                    repeat(iterationsPerThread) {
                        keys.forEachIndexed { index, (nodeId, seq) ->
                            if (!cache.seen(nodeId, seq)) {
                                newCounts.incrementAndGet(index)
                            }
                        }
                    }
                }
            }

            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        keys.indices.forEach { index ->
            assertEquals(
                "key ${keys[index]} must be new exactly once",
                1,
                newCounts.get(index),
            )
        }
    }

    @Test
    fun capacityMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { DedupCache(capacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { DedupCache(capacity = -1) }
    }
}
