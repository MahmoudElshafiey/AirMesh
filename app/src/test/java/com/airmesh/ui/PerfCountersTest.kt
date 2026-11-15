package com.airmesh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the debug perf counters and line format (TASK U4 part 3). */
class PerfCountersTest {

    @Test
    fun aFreshCounterDrainsZero() {
        assertEquals(0L, RecompositionCounter().drain())
    }

    @Test
    fun drainReturnsTheRecordedEventsAndResets() {
        val counter = RecompositionCounter()
        repeat(5) { counter.record() }
        assertEquals(5L, counter.drain())
        assertEquals("second drain is empty", 0L, counter.drain())
        counter.record()
        assertEquals(1L, counter.drain())
    }

    @Test
    fun theFiveCountersAreIndependent() {
        val perf = PerfCounters()
        repeat(3) { perf.chats.record() }
        perf.uiEmissions.record()
        assertEquals(3L, perf.chats.drain())
        assertEquals(1L, perf.uiEmissions.drain())
        assertEquals(0L, perf.chat.drain())
        assertEquals(0L, perf.network.drain())
        assertEquals(0L, perf.logTab.drain())
    }

    @Test
    fun thePerfLineHasTheExactExpectedFormat() {
        assertEquals(
            "perf: Chats=3 Chat=0 Network=12 Log=5 recompositions per 30s, uiEmissions=47",
            perfLine(chats = 3, chat = 0, network = 12, logTab = 5, uiEmissions = 47),
        )
    }

    @Test
    fun thePerfLineKeepsItsProtectedPrefix() {
        // TASK U4 part 4's verbose filter must never drop this line.
        assertEquals("perf:", perfLine(0, 0, 0, 0, 0).substringBefore(' '))
    }

    @Test
    fun theReportIntervalIsThirtySeconds() {
        assertEquals(30_000L, PERF_REPORT_INTERVAL_MS)
    }
}
