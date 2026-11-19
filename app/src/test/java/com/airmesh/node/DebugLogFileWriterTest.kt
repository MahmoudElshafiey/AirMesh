package com.airmesh.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * JVM tests for [DebugLogFileWriter] (TASK S2 item 4): the header line on every process start,
 * rotation at the cap with newest-first ordering, flush batching on a fake clock, and a failing
 * disk that must never crash. The in-memory [LogFileSystem] fake records content per file name.
 */
class DebugLogFileWriterTest {

    /** In-memory [LogFileSystem]; [failWrites] makes every mutating call throw like a dead disk. */
    private class FakeFs : LogFileSystem {
        val contents = HashMap<String, StringBuilder>()
        var failWrites = false

        override fun exists(name: String): Boolean = contents.containsKey(name)

        override fun sizeBytes(name: String): Long = (contents[name]?.length ?: 0).toLong()

        override fun appendLines(name: String, lines: List<String>) {
            if (failWrites) throw IOException("disk full")
            contents.getOrPut(name) { StringBuilder() }.append(lines.joinToString("\n")).append('\n')
        }

        override fun rename(from: String, to: String): Boolean {
            if (failWrites) throw IOException("disk full")
            val moved = contents.remove(from) ?: return false
            contents[to] = moved
            return true
        }

        override fun delete(name: String): Boolean {
            if (failWrites) throw IOException("disk full")
            return contents.remove(name) != null
        }

        override fun readAllText(name: String): String? = contents[name]?.toString()

        /** Lines of [name] without the trailing newline, in file order. */
        fun linesOf(name: String): List<String> =
            (contents[name]?.toString()?.removeSuffix("\n")?.lineSequence()?.toList()) ?: emptyList()
    }

    private class Setup(capBytes: Long = 1_048_576L) {
        var now = 0L
        val fs = FakeFs()
        val writer = DebugLogFileWriter(
            fs = fs,
            clock = { now },
            files = listOf(DebugLogFileWriter.DEBUG_LOG_FILE, DebugLogFileWriter.PREVIOUS_DEBUG_LOG_FILE),
            maxBytesPerFile = capBytes,
        )
    }

    @Test
    fun `start writes the header line into the live file`() {
        val s = Setup()
        s.writer.start("=== process start 2026-11-19 10:00:00 build 1.0 node #42 device Pixel SDK 37 ===")

        assertEquals(
            listOf("=== process start 2026-11-19 10:00:00 build 1.0 node #42 device Pixel SDK 37 ==="),
            s.fs.linesOf(DebugLogFileWriter.DEBUG_LOG_FILE),
        )
        assertNull("no previous file on a fresh start", s.fs.readAllText(DebugLogFileWriter.PREVIOUS_DEBUG_LOG_FILE))
    }

    @Test
    fun `a new process rotates the old live file into the previous slot`() {
        val s = Setup()
        s.writer.start("=== process start A ===")
        s.now += 5_000L
        s.writer.append("line from session A")
        s.writer.flush(force = true)

        // The process restarts: a fresh writer on the same folder.
        val second = Setup()
        second.fs.contents.putAll(s.fs.contents)
        second.now = 10_000L
        second.writer.start("=== process start B ===")

        assertEquals(
            "the old session is now the previous file",
            listOf("=== process start A ===", "line from session A"),
            second.fs.linesOf(DebugLogFileWriter.PREVIOUS_DEBUG_LOG_FILE),
        )
        assertEquals(
            "the live file holds only the new header",
            listOf("=== process start B ==="),
            second.fs.linesOf(DebugLogFileWriter.DEBUG_LOG_FILE),
        )
    }

    @Test
    fun `rotation happens at the cap and keeps newest first`() {
        // 20-byte lines ("line-NN\n" = 8 bytes); a 40-byte cap holds five lines per file.
        val s = Setup(capBytes = 40L)
        s.writer.start("H") // header counts toward the size too
        for (i in 1..20) {
            s.writer.append("line-$i")
            s.writer.flush(force = true)
        }

        val live = s.fs.linesOf(DebugLogFileWriter.DEBUG_LOG_FILE)
        val previous = s.fs.linesOf(DebugLogFileWriter.PREVIOUS_DEBUG_LOG_FILE)
        assertTrue("the live file stayed under the cap", s.fs.sizeBytes(DebugLogFileWriter.DEBUG_LOG_FILE) <= 40L)
        assertTrue("the newest lines are in the live file", "line-20" in live)
        assertFalse("the oldest lines rotated out of both files", "line-1" in live || "line-1" in previous)
        // Newest first: every line in the live file is newer than every line in the previous one.
        val newestPrevious = previous.maxOf { it }
        val oldestLive = live.minOf { it }
        assertTrue("previous file holds older lines", newestPrevious < oldestLive)
    }

    @Test
    fun `ordering is preserved across rotation`() {
        val s = Setup(capBytes = 40L)
        s.writer.start("H")
        for (i in 1..25) {
            s.writer.append("line-$i")
            s.writer.flush(force = true)
        }

        // Merged previous + current is the time-ordered tail of everything written.
        val merged = s.fs.linesOf(DebugLogFileWriter.PREVIOUS_DEBUG_LOG_FILE) +
            s.fs.linesOf(DebugLogFileWriter.DEBUG_LOG_FILE)
        assertTrue("the newest line survived", "line-25" in merged)
        assertEquals("no duplicates across the two files", merged.size, merged.toSet().size)
        // Only the numbered lines are compared; a surviving header would not parse and is skipped.
        val numbers = merged.map { it.removePrefix("line-").toIntOrNull() ?: -1 }.filter { it >= 0 }
        assertTrue("numbered lines survived", numbers.isNotEmpty())
        assertEquals("strictly increasing in time order", numbers, numbers.sorted())
    }

    @Test
    fun `flush batches on the two second interval with a fake clock`() {
        val s = Setup()
        s.writer.start("H") // forced write at t=0
        val afterHeader = s.fs.sizeBytes(DebugLogFileWriter.DEBUG_LOG_FILE)

        s.now = 1_000L
        s.writer.append("early line")
        s.writer.flush()
        assertEquals("not due yet: nothing new written", afterHeader, s.fs.sizeBytes(DebugLogFileWriter.DEBUG_LOG_FILE))

        s.now = DebugLogFileWriter.FLUSH_INTERVAL_MS // boundary: exactly 2 s is due
        s.writer.flush()
        assertTrue("due at the interval: line written", "early line" in (s.fs.readAllText(DebugLogFileWriter.DEBUG_LOG_FILE) ?: ""))

        s.now += 1_000L
        s.writer.append("second line")
        s.writer.flush()
        assertFalse("still inside the window", "second line" in (s.fs.readAllText(DebugLogFileWriter.DEBUG_LOG_FILE) ?: ""))
    }

    @Test
    fun `a failing disk does not crash and later lines still flush`() {
        val s = Setup()
        s.writer.start("H")

        s.now = 3_000L
        s.fs.failWrites = true
        s.writer.append("lost line")
        s.writer.flush(force = true) // must not throw

        s.now += DebugLogFileWriter.FLUSH_INTERVAL_MS
        s.fs.failWrites = false
        s.writer.append("surviving line")
        s.writer.flush()

        val live = s.fs.readAllText(DebugLogFileWriter.DEBUG_LOG_FILE).orEmpty()
        assertTrue("the writer recovered", "surviving line" in live)
        assertFalse("the failed batch was dropped, not retried forever", "lost line" in live)
    }
}
