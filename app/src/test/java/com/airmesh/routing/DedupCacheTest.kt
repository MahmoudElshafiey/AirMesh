package com.airmesh.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun capacityMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { DedupCache(capacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { DedupCache(capacity = -1) }
    }
}
