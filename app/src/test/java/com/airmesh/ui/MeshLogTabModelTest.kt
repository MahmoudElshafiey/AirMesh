package com.airmesh.ui

import com.airmesh.node.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** TASK T1 part A: the log screen renders at most the last 500 matching lines (pure model). */
class MeshLogTabModelTest {

    private fun entries(count: Int): List<LogEntry> =
        (0 until count).map { LogEntry(timeMs = 1_700_000_000_000L + it, text = "line-$it", seq = it.toLong()) }

    @Test
    fun renderedCountIsMinOfCapAndMatches() {
        assertEquals("below the cap every matched line renders", 37, renderedLogLines(entries(37)).size)
        assertEquals(
            "exactly at the cap every matched line renders",
            MAX_RENDERED_LOG_LINES,
            renderedLogLines(entries(MAX_RENDERED_LOG_LINES)).size,
        )
        assertEquals(
            "above the cap only 500 lines render (the evidence case: 613 lines)",
            MAX_RENDERED_LOG_LINES,
            renderedLogLines(entries(613)).size,
        )
    }

    @Test
    fun renderedKeepsTheNewestMatchingLines() {
        val rendered = renderedLogLines(entries(600))
        assertEquals("the newest line survives the cap", "line-599", rendered.last().text)
        assertEquals("the oldest surviving line is matches - 500 + 1", "line-100", rendered.first().text)
    }

    @Test
    fun emptyMatchListRendersNothing() {
        assertEquals(0, renderedLogLines(emptyList<LogEntry>()).size)
    }
}
