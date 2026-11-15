package com.airmesh.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the bounded event log and its verbose filter (TASK U4 part 4). */
class EventLogTest {

    @Test
    fun theDefaultCapacityIsTwoThousandLines() {
        assertEquals(2000, EventLog.DEFAULT_MAX_ENTRIES)
    }

    @Test
    fun theOldestLinesAreDroppedWhenTheCapIsReached() {
        var now = 0L
        val log = EventLog(maxEntries = 10, clock = { now })
        repeat(25) { i ->
            now += 1
            log.log("line $i")
        }
        val entries = log.entries.value
        assertEquals(10, entries.size)
        // The fifteen oldest lines are gone; the newest ten keep their order.
        assertEquals("line 15", entries.first().text)
        assertEquals("line 24", entries.last().text)
    }

    @Test
    fun theDefaultCapKeepsTheNewestTwoThousandLines() {
        val log = EventLog(clock = { 0L })
        repeat(2100) { i -> log.log("line $i") }
        val entries = log.entries.value
        assertEquals(EventLog.DEFAULT_MAX_ENTRIES, entries.size)
        // 2100 logged - 2000 kept = the first hundred lines (0..99) are dropped.
        assertEquals("line 100", entries.first().text)
        assertEquals("line 2099", entries.last().text)
    }

    @Test
    fun linesKeepTheirInsertionOrder() {
        var now = 0L
        val log = EventLog(maxEntries = 100, clock = { now })
        repeat(50) { i ->
            now += 7
            log.log("line $i")
        }
        val entries = log.entries.value
        assertEquals(50, entries.size)
        assertTrue(entries.map { it.timeMs }.zipWithNext().all { (a, b) -> a <= b })
        assertEquals((0 until 50).map { "line $it" }, entries.map { it.text })
    }

    @Test
    fun everyLineGetsAUniqueIncreasingSeq() {
        val log = EventLog(clock = { 0L })
        repeat(50) { i -> log.log("line $i") }
        val seqs = log.entries.value.map { it.seq }
        assertEquals(seqs.distinct().size, seqs.size)
        assertTrue(seqs.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun clearEmptiesTheLog() {
        val log = EventLog(clock = { 0L })
        log.log("line")
        log.clear()
        assertTrue(log.entries.value.isEmpty())
    }

    // --- Verbose filter (TASK U4 part 4) ------------------------------------

    @Test
    fun theDefaultIsVerboseEnabled() {
        val log = EventLog(clock = { 0L })
        log.log("P2P: onPeersAvailable: 3 peers")
        assertEquals(1, log.entries.value.size)
    }

    @Test
    fun verboseLinesAreKeptWhenVerboseIsEnabled() {
        val log = EventLog(clock = { 0L }, verboseEnabled = true)
        log.log("P2P: onPeersAvailable: 3 peers")
        log.log("P2P: interfaces (startup):")
        assertEquals(2, log.entries.value.size)
    }

    @Test
    fun verboseLinesAreDroppedWhenVerboseIsDisabled() {
        val log = EventLog(clock = { 0L }, verboseEnabled = false)
        log.log("P2P: onPeersAvailable: 3 peers")
        log.log("P2P: interfaces (startup):")
        log.log("P2P:   wlan0 UP 192.168.49.1/24")
        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun protectedPrefixesSurviveTheVerboseFilter() {
        val log = EventLog(clock = { 0L }, verboseEnabled = false)
        for (prefix in EventLog.PROTECTED_PREFIXES) {
            log.log("$prefix something happened")
        }
        assertEquals(EventLog.PROTECTED_PREFIXES.size, log.entries.value.size)
    }

    @Test
    fun nonVerboseLinesAreKeptWhenVerboseIsDisabled() {
        val log = EventLog(clock = { 0L }, verboseEnabled = false)
        log.log("service: mesh started (node #7)")
        assertEquals(1, log.entries.value.size)
    }

    @Test
    fun droppedLinesDoNotConsumeSeqNumbers() {
        // A dropped line must not create a gap that later lines could collide with.
        val log = EventLog(clock = { 0L }, verboseEnabled = false)
        log.log("P2P: raw callback")
        log.log("service: kept")
        assertEquals(0L, log.entries.value.single().seq)
    }
}
