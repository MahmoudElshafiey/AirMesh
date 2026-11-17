package com.airmesh.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ProfileDirectory.upsertIfChanged] (TASK R2 part 2): a changed
 * name or color is accepted immediately without waiting out the old 10 s window,
 * an unchanged duplicate only refreshes the timestamp, and more than three
 * changes from one node inside a 10 s window are ignored by the flood guard. A
 * fake clock makes every boundary exact.
 */
class ProfileDirectoryUpsertTest {

    private var now = 0L
    private val directory = ProfileDirectory(localNodeId = 1, clock = { now })

    @Test
    fun aChangedNameIsAcceptedImmediatelyWithoutWaitingTenSeconds() {
        assertTrue(directory.upsertIfChanged(7, "Alice", 3, now))
        now += 9_999L // still inside the old 10 s window
        assertTrue("a rename must not wait out the window", directory.upsertIfChanged(7, "Alicia", 5, now))
        assertEquals("Alicia", directory.label(7))
    }

    @Test
    fun aColorOnlyChangeCountsAsAChange() {
        assertTrue(directory.upsertIfChanged(7, "Alice", 3, now))
        now += 1_000L
        assertTrue(directory.upsertIfChanged(7, "Alice", 4, now))
        assertEquals(4, directory.snapshot()[7]!!.colorIndex)
    }

    @Test
    fun anUnchangedDuplicateOnlyRefreshesTheTimestamp() {
        assertTrue(directory.upsertIfChanged(7, "Alice", 3, now))
        now += 5_000L
        assertFalse(directory.upsertIfChanged(7, "Alice", 3, now))
        val entry = directory.snapshot()[7]!!
        assertEquals("Alice", entry.name)
        assertEquals(now, entry.updatedAt) // recency refreshed, profile untouched
    }

    @Test
    fun theFloodGuardIgnoresTheFourthChangeWithinTenSeconds() {
        assertTrue(directory.upsertIfChanged(7, "A", 0, now))
        now += 1_000L
        assertTrue(directory.upsertIfChanged(7, "B", 0, now))
        now += 1_000L
        assertTrue(directory.upsertIfChanged(7, "C", 0, now))
        now += 1_000L
        assertFalse("the fourth change inside the window must be ignored", directory.upsertIfChanged(7, "D", 0, now))
        assertEquals("C", directory.label(7))

        // The window slides: once the earliest accepted change is 10 s old, a new
        // change fits again.
        now += 9_001L // t=12_001: all three earlier entries leave the window
        assertTrue(directory.upsertIfChanged(7, "E", 0, now))
        assertEquals("E", directory.label(7))
    }

    @Test
    fun changesBeyondTheThirdStayIgnoredUntilTheWindowSlides() {
        assertTrue(directory.upsertIfChanged(7, "A", 0, now))
        now += 1_000L
        assertTrue(directory.upsertIfChanged(7, "B", 0, now))
        now += 1_000L
        assertTrue(directory.upsertIfChanged(7, "C", 0, now))
        now += 1_000L
        assertFalse(directory.upsertIfChanged(7, "D", 0, now))
        now += 1_000L
        assertFalse("an ignored change must not open a slot", directory.upsertIfChanged(7, "E", 0, now))
        assertEquals("C", directory.label(7))
    }

    @Test
    fun theGuardIsPerNode() {
        assertTrue(directory.upsertIfChanged(7, "A", 0, now))
        now += 1_000L
        assertTrue(directory.upsertIfChanged(7, "B", 0, now))
        now += 1_000L
        assertTrue(directory.upsertIfChanged(7, "C", 0, now))
        // Node 8 is unaffected by node 7's spent budget.
        assertTrue(directory.upsertIfChanged(8, "D", 0, now))
    }

    @Test
    fun ownNodeIdIsRejected() {
        assertFalse(directory.upsertIfChanged(1, "Impostor", 0, now))
        assertEquals("User #1", directory.label(1))
        assertTrue(directory.snapshot().isEmpty())
    }
}
