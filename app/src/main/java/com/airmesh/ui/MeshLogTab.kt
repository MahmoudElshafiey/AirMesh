package com.airmesh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.airmesh.node.DebugLogFileWriter
import com.airmesh.node.LogEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Filter order for the chips: index 0 is "All" (no filter), then one per [LogCategory]. */
private val FILTER_LABELS = listOf("All", "Messages", "Network", "TCP", "Errors")

private fun filterFromIndex(index: Int): LogCategory? = when (index) {
    1 -> LogCategory.MESSAGES
    2 -> LogCategory.NETWORK
    3 -> LogCategory.TCP
    4 -> LogCategory.ERRORS
    else -> null
}

/**
 * Log tab: the event log in a monospace list, newest at the bottom.
 * Filter chips select which lines are visible; "Follow" keeps the view pinned
 * to the newest line and switches off when the user scrolls up (scrolling back
 * to the bottom resumes it). Copy copies exactly what is currently filtered.
 *
 * TASK T1 part A: the list takes all remaining height (weight 1f), the chips sit in one
 * horizontally scrollable row, the toolbar is a single compact row with an overflow menu,
 * and at most the last [MAX_RENDERED_LOG_LINES] matching lines are composed. The old code
 * only rendered rows when the filtered list was EMPTY — that is why the screen showed
 * "613 lines" but no rows.
 */
@Composable
fun MeshLogTab(
    events: List<LogEntry>,
    onClearLog: () -> Unit,
    // TASK T1 part A: emits the once-per-open debug line "ui: log screen lines=N rendered=M".
    onUiEventLog: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    val selected = filterFromIndex(filterIndex)
    // TASK S2 item 5: "Previous session" swaps the live log for the last process's file.
    var showPrevious by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Filtering runs off the main thread (TASK U4 part 4): with up to 2000 lines a burst of log
    // updates must not block composition. The unfiltered list seeds the first frame so an empty
    // flash never appears; if a filter was restored via rememberSaveable, the producer corrects
    // it within one frame.
    val visible by produceState<List<LogEntry>>(initialValue = events, events, selected) {
        value = withContext(Dispatchers.Default) { events.filter { matchesFilter(it.text, selected) } }
    }

    // TASK T1 part A: only the newest matching lines are composed; count and Copy use [visible].
    val rendered = remember(visible) { renderedLogLines(visible) }

    // The previous process's rolling file is read on demand off the main thread; null while
    // the toggle is off or the read is in flight.
    val previousLines by produceState<List<String>?>(initialValue = null, showPrevious) {
        value = if (showPrevious) withContext(Dispatchers.IO) { readPreviousSession(context) } else null
    }

    val listState = rememberLazyListState()
    var follow by rememberSaveable { mutableStateOf(true) }

    // Debug-only line once per open: what the toolbar reports vs. what is actually composed.
    LaunchedEffect(Unit) {
        onUiEventLog("ui: log screen lines=${events.size} rendered=${rendered.size}")
    }

    LaunchedEffect(rendered.size, follow) {
        if (follow && rendered.isNotEmpty()) listState.animateScrollToItem(rendered.lastIndex)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { first ->
                // Within two items of the end counts as "at the bottom".
                follow = rendered.isNotEmpty() && first >= rendered.size - 2
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // One horizontally scrollable row of chips (TASK T1 part A) instead of a wrapping FlowRow.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FILTER_LABELS.forEachIndexed { index, label ->
                FilterChip(
                    selected = filterIndex == index,
                    onClick = { filterIndex = index },
                    label = { Text(label) },
                )
            }
        }

        // Compact toolbar: Follow switch, line count, Copy, Clear and a three-dot menu.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Follow", style = MaterialTheme.typography.labelMedium)
            Switch(checked = follow, onCheckedChange = { follow = it })
            Spacer(modifier = Modifier.weight(1f))
            Text(
                if (selected == null) "${visible.size} lines" else "${visible.size} / ${events.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { copyVisible(visible, context) }) { Text("Copy") }
            TextButton(onClick = onClearLog, enabled = events.isNotEmpty()) { Text("Clear") }
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More log actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // TASK S2 item 5: share the rolling log files.
                    DropdownMenuItem(
                        text = { Text("Share log") },
                        onClick = {
                            menuOpen = false
                            scope.launch { shareLog(context) }
                        },
                    )
                    // TASK S2 item 5: inspect the previous session's lines (toggle).
                    DropdownMenuItem(
                        text = { Text(if (showPrevious) "Exit previous session" else "Previous session") },
                        onClick = {
                            menuOpen = false
                            showPrevious = !showPrevious
                        },
                    )
                }
            }
        }

        // TASK T1 part A: the list takes all remaining height.
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).testTag("logList"),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            if (showPrevious) {
                // The previous session's file replaces the live log; filters do not apply to it.
                when (val lines = previousLines) {
                    null -> item(key = "previous-loading") {
                        Text(
                            "Loading previous session…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }

                    else -> if (lines.isEmpty()) {
                        item(key = "no-previous") {
                            Text(
                                "No previous session log.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    } else {
                        items(lines) { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            } else if (rendered.isEmpty()) {
                // TASK T1 part A: explicit empty state when the filter hides everything.
                item(key = "empty") {
                    Text(
                        if (events.isEmpty()) "No log lines yet." else "No lines match this filter",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                // Stable keys (TASK U4 part 4): EventLog assigns each line a monotonic seq, so
                // appended lines never shift the identity of the rows already on screen.
                items(rendered, key = { it.seq }) { entry ->
                    Text(
                        "${formatLogTime(entry.timeMs)}  ${entry.text}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 1.dp).testTag("logRow"),
                    )
                }
            }
        }
    }
}

/** Copies the currently filtered lines (time + text) to the clipboard. */
private fun copyVisible(visible: List<LogEntry>, context: Context) {
    val text = visible.joinToString("\n") { "${formatLogTime(it.timeMs)}  ${it.text}" }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("AirMesh log", text))
    Toast.makeText(context, "Copied ${visible.size} lines", Toast.LENGTH_SHORT).show()
}

// TASK S2 item 5: the rolling debug files live in the app's external logs folder.
private const val MAX_PREVIOUS_SESSION_LINES = 2000
private const val SHARE_FILE_NAME = "airmesh-log-share.txt"

private fun logsDir(context: Context): File? = context.getExternalFilesDir("logs")

/** Last [MAX_PREVIOUS_SESSION_LINES] lines of the previous process's log file (empty if none). */
private fun readPreviousSession(context: Context): List<String> {
    val dir = logsDir(context) ?: return emptyList()
    val file = File(dir, DebugLogFileWriter.PREVIOUS_DEBUG_LOG_FILE)
    return runCatching {
        file.readLines().filter { it.isNotBlank() }.takeLast(MAX_PREVIOUS_SESSION_LINES)
    }.getOrDefault(emptyList())
}

/** Merges previous + current log files in time order and shares them via an ACTION_SEND intent. */
private suspend fun shareLog(context: Context) {
    val dir = logsDir(context) ?: run { toast(context, "No log folder available"); return }
    val mergedFile = withContext(Dispatchers.IO) {
        // The previous file holds the older lines, so it comes first in the merge.
        val parts = listOf(DebugLogFileWriter.PREVIOUS_DEBUG_LOG_FILE, DebugLogFileWriter.DEBUG_LOG_FILE)
            .mapNotNull { name -> runCatching { File(dir, name).readText() }.getOrNull()?.trimEnd('\n') }
        if (parts.isEmpty()) null else File(dir, SHARE_FILE_NAME).apply { writeText(parts.joinToString("\n")) }
    } ?: return toast(context, "No log file to share")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logfiles", mergedFile)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share AirMesh log"))
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
