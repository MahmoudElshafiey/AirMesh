package com.airmesh.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [ProfileDirectory]: update rules, eviction and persistence. */
class ProfileDirectoryTest {

    private var now = 0L
    private val clock: () -> Long = { now }

    private fun directory(nodeId: Int = 1, store: ProfileStorage? = InMemoryProfileStorage()) =
        ProfileDirectory(localNodeId = nodeId, clock = clock, storage = store)

    @Test
    fun storesAcceptedProfilesAndLabelsThem() {
        val dir = directory()
        now = 1_000L
        assertTrue(dir.acceptFromNetwork(7, "Alice", 3))

        assertEquals("Alice", dir.label(7))
        val info = dir.snapshot()[7]!!
        assertEquals(3, info.colorIndex)
        assertEquals(1_000L, info.updatedAt) // timestamp comes from the injected clock
    }

    @Test
    fun unknownNodeFallsBackToUserLabel() {
        val dir = directory()
        assertEquals("User #42", dir.label(42))
    }

    @Test
    fun neverAcceptsOwnNodeIdFromNetwork() {
        val dir = directory(nodeId = 1)
        now = 5L
        assertFalse(dir.acceptFromNetwork(1, "Impostor", 0))
        assertEquals("User #1", dir.label(1))
        assertTrue(dir.snapshot().isEmpty())
    }

    @Test
    fun updateWithinTenSecondsIsIgnored() {
        val dir = directory()
        now = 0L
        assertTrue(dir.acceptFromNetwork(7, "Alice", 3))

        now = 9_999L
        assertFalse(dir.acceptFromNetwork(7, "Mallory", 4))
        assertEquals("Alice", dir.label(7))
    }

    @Test
    fun updateExactlyTenSecondsLaterIsStillTooSoon() {
        // The rule is strictly "older than 10s": exactly 10s does not qualify.
        val dir = directory()
        now = 0L
        assertTrue(dir.acceptFromNetwork(7, "Alice", 3))

        now = ProfileDirectory.MIN_UPDATE_INTERVAL_MS
        assertFalse(dir.acceptFromNetwork(7, "Mallory", 4))
        assertEquals("Alice", dir.label(7))
    }

    @Test
    fun updateAfterTenSecondsReplacesTheOldOne() {
        val dir = directory()
        now = 0L
        assertTrue(dir.acceptFromNetwork(7, "Alice", 3))

        now = ProfileDirectory.MIN_UPDATE_INTERVAL_MS + 1
        assertTrue(dir.acceptFromNetwork(7, "Alicia", 5))
        assertEquals("Alicia", dir.label(7))
        assertEquals(5, dir.snapshot()[7]!!.colorIndex)
    }

    @Test
    fun rejectedUpdateDoesNotExtendTheWindow() {
        val dir = directory()
        now = 0L
        assertTrue(dir.acceptFromNetwork(7, "Alice", 3))

        // Rejected: the window is measured from the last ACCEPTED update (t=0).
        now = 5_000L
        assertFalse(dir.acceptFromNetwork(7, "Mallory", 4))

        now = ProfileDirectory.MIN_UPDATE_INTERVAL_MS + 1
        assertTrue(dir.acceptFromNetwork(7, "Alicia", 5))
    }

    @Test
    fun evictsLeastRecentlyUpdatedAtFiveHundredEntries() {
        val dir = directory()
        repeat(ProfileDirectory.MAX_ENTRIES) { i ->
            now = i.toLong() * 1_000L
            assertTrue(dir.acceptFromNetwork(100 + i, "Node $i", 0))
        }
        assertEquals(ProfileDirectory.MAX_ENTRIES, dir.snapshot().size)

        // A new node pushes out the least recently updated one (node 100).
        now = 9_999_999L
        assertTrue(dir.acceptFromNetwork(999, "New", 1))
        assertEquals(ProfileDirectory.MAX_ENTRIES, dir.snapshot().size)
        assertFalse(dir.snapshot().containsKey(100))
        assertEquals("User #100", dir.label(100))
        assertEquals("Node 499", dir.label(599)) // newest of the old batch survives
    }

    @Test
    fun reUpdateRefreshesEvictionOrder() {
        // localNodeId must differ from the node ids below (own id is never accepted).
        val dir = directory(nodeId = 9999)
        now = 0L
        assertTrue(dir.acceptFromNetwork(1, "A", 0))
        now = 20_000L
        assertTrue(dir.acceptFromNetwork(2, "B", 0))

        // Touch node 1 again: node 2 becomes the least recently updated.
        now = 30_000L
        assertTrue(dir.acceptFromNetwork(1, "A2", 0))
        var t = 40_000L
        for (id in 3..ProfileDirectory.MAX_ENTRIES) {
            now = t
            assertTrue(dir.acceptFromNetwork(id, "N$id", 0))
            t += 20_000L
        }

        now = t + 20_000L
        assertTrue(dir.acceptFromNetwork(501, "New", 0))
        assertFalse("node 2 should have been evicted", dir.snapshot().containsKey(2))
        assertTrue("re-updated node 1 must survive", dir.snapshot().containsKey(1))
    }

    @Test
    fun persistsAcrossDirectoryInstances() {
        val store = InMemoryProfileStorage()
        val first = directory(store = store)
        now = 1_000L
        assertTrue(first.acceptFromNetwork(7, "Alice", 3))
        now = 2_000L
        assertTrue(first.acceptFromNetwork(8, "Bob", 6))

        // A fresh node (e.g. after an app restart) reloads the same storage.
        val second = directory(store = store)
        assertEquals("Alice", second.label(7))
        assertEquals("Bob", second.label(8))
        assertEquals(first.snapshot(), second.snapshot())
    }

    @Test
    fun storedJsonSurvivesQuotesAndBackslashesInNames() {
        val store = InMemoryProfileStorage()
        val first = directory(store = store)
        now = 1L
        assertTrue(first.acceptFromNetwork(7, "O\"Brien \\ back", 2))

        val second = directory(store = store)
        assertEquals("O\"Brien \\ back", second.label(7))
    }

    @Test
    fun corruptedStoredJsonIsIgnored() {
        val garbage = listOf(
            "",
            "not json at all",
            "{\"7\":",
            "[1,2,3]",
            "\"just a string\"",
            // Valid JSON but missing the required c/t fields.
            "{\"7\":{\"n\":\"Alice\"}}",
            // Unknown field: not produced by this app's encoder.
            "{\"7\":{\"n\":\"A\",\"c\":0,\"t\":1,\"x\":2}}",
        )
        for (text in garbage) {
            val store = InMemoryProfileStorage()
            store.putString(ProfileDirectory.STORAGE_KEY, text)
            val dir = directory(store = store)
            assertTrue("expected empty directory for $text", dir.snapshot().isEmpty())
        }
    }

    @Test
    fun absentStoredDataStartsEmpty() {
        val dir = directory(store = InMemoryProfileStorage())
        assertTrue(dir.snapshot().isEmpty())
        assertEquals("User #9", dir.label(9))
    }
}
