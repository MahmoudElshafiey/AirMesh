package com.airmesh.ui

/**
 * Debug-only performance measurement aid (TASK U4 part 3).
 *
 * [RecompositionCounter] counts screen re-executions or UI state changes between periodic
 * drains; [perfLine] formats the line that lands in the event log every 30 s while the UI is
 * visible. Everything here is pure Kotlin so it is unit-testable on the JVM, and the counters
 * use plain fields on purpose: a count must stay invisible to Compose's snapshot system,
 * otherwise recording an event would invalidate the very screens being measured.
 */

/** Counts events (recompositions or UI state changes) until [drain]ed. */
class RecompositionCounter {

    private var pending = 0L

    /** Records one event. Not observable by Compose — see class KDoc. */
    fun record() {
        pending++
    }

    /** Returns the events recorded since the last drain and resets to zero. */
    fun drain(): Long {
        val out = pending
        pending = 0L
        return out
    }
}

/** The five counters behind one perf line: four top-level screens plus UI state emissions. */
class PerfCounters(
    val chats: RecompositionCounter = RecompositionCounter(),
    val chat: RecompositionCounter = RecompositionCounter(),
    val network: RecompositionCounter = RecompositionCounter(),
    val logTab: RecompositionCounter = RecompositionCounter(),
    val uiEmissions: RecompositionCounter = RecompositionCounter(),
)

/** How often the perf line is reported while the UI is visible. */
const val PERF_REPORT_INTERVAL_MS = 30_000L

/**
 * Formats the periodic perf line. The "perf:" prefix matters: verbose-log filtering (TASK U4
 * part 4) never drops lines starting with it, so the measurement survives in quiet logs too.
 */
fun perfLine(chats: Long, chat: Long, network: Long, logTab: Long, uiEmissions: Long): String =
    "perf: Chats=$chats Chat=$chat Network=$network Log=$logTab recompositions per ${PERF_REPORT_INTERVAL_MS / 1000}s, uiEmissions=$uiEmissions"
