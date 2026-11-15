package com.airmesh.node

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** One timestamped line in the shared diagnostic log. */
@Immutable
data class LogEntry(
    val timeMs: Long,
    val text: String,
    /** Monotonic sequence assigned by [EventLog]; stable key for the log list (TASK U4 part 4). */
    val seq: Long = 0L,
)

/**
 * Bounded, thread-safe event log shared by the node and the Wi-Fi Direct
 * connector.
 *
 * Keeping the log here (instead of inside the UI) means errors that happen
 * before the screen is composed are still available when it opens.
 */
class EventLog(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
    /** When false, verbose lines (raw P2P callbacks / interface dumps) are not stored at all (TASK U4 part 4). */
    private val verboseEnabled: Boolean = true,
) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 2000

        /** Verbose family: raw Wi-Fi Direct callback lines and interface dumps all start with this. */
        private const val VERBOSE_PREFIX = "P2P:"

        /** Lines starting with any of these are never dropped by the verbose filter (TASK U4 part 4). */
        val PROTECTED_PREFIXES: List<String> = listOf(
            "auto:", "dnssd:", "TCP:", "TX", "RX", "delivered", "originated",
            "replay:", "timeline", "location:", "ui:", "perf:", "neighbor", "service:",
        )
    }

    init {
        require(maxEntries > 0) { "maxEntries must be positive: $maxEntries" }
    }

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** Atomic so concurrent log calls (transport callbacks, service scope) get unique seqs. */
    private val nextSeq = AtomicLong(0)

    fun log(message: String) {
        if (!verboseEnabled && isVerboseLine(message)) return
        val entry = LogEntry(timeMs = clock(), text = message, seq = nextSeq.getAndIncrement())
        _entries.update { current -> (current + entry).takeLast(maxEntries) }
    }

    /** Verbose lines are the raw P2P callback / interface-dump family; protected prefixes always win. */
    private fun isVerboseLine(message: String): Boolean =
        message.startsWith(VERBOSE_PREFIX) && PROTECTED_PREFIXES.none { message.startsWith(it) }

    fun clear() {
        _entries.value = emptyList()
    }
}
