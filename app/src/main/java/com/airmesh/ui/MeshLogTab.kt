package com.airmesh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.airmesh.node.LogEntry
import kotlinx.coroutines.flow.distinctUntilChanged

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
 * Log tab: the full event log in a monospace list, newest at the bottom.
 * Filter chips select which lines are visible; "Follow" keeps the view pinned
 * to the newest line and switches off when the user scrolls up (scrolling back
 * to the bottom resumes it). Copy copies exactly what is currently filtered.
 */
@Composable
fun MeshLogTab(events: List<LogEntry>, onClearLog: () -> Unit) {
    val context = LocalContext.current
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    val selected = filterFromIndex(filterIndex)

    // Filtering is pure (see MeshUiHelpers), so it can be memoized on the input.
    val visible = remember(events, selected) { events.filter { matchesFilter(it.text, selected) } }

    val listState = rememberLazyListState()
    var follow by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(visible.size, follow) {
        if (follow && visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { first ->
                // Within two items of the end counts as "at the bottom".
                follow = visible.isNotEmpty() && first >= visible.size - 2
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
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
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            if (visible.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (events.isEmpty()) "Log is empty." else "No lines match this filter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                // Index keys: the log is append-only and LogEntry has no id.
                items(count = visible.size, key = { it }) { index ->
                    val entry = visible[index]
                    Text(
                        "${formatLogTime(entry.timeMs)}  ${entry.text}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
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
