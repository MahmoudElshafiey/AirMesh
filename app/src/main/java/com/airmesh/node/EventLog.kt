package com.airmesh.node

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One timestamped line in the shared diagnostic log. */
data class LogEntry(
    val timeMs: Long,
    val text: String,
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
) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 1000
    }

    init {
        require(maxEntries > 0) { "maxEntries must be positive: $maxEntries" }
    }

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun log(message: String) {
        val entry = LogEntry(timeMs = clock(), text = message)
        _entries.update { current -> (current + entry).takeLast(maxEntries) }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
