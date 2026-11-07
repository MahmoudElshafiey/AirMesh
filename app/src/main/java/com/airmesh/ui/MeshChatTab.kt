package com.airmesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airmesh.node.LOCATION_STALE_AFTER_MS
import com.airmesh.node.LocationFix
import com.airmesh.node.MeshLocation
import com.airmesh.node.MeshMessage
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.LocationPayload
import com.airmesh.protocol.haversineMeters
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * One row in the chat list. New message kinds add a subtype plus one `when`
 * branch in [MeshChatTab] — no changes to scrolling, keys or layout are
 * needed for that. Rows share one timeline sorted by [timestamp].
 */
sealed interface ChatRow {
    val timestamp: Long

    data class TextMessage(val message: MeshMessage) : ChatRow {
        override val timestamp: Long get() = message.timestamp
    }

    data class LocationItem(val location: MeshLocation) : ChatRow {
        override val timestamp: Long get() = location.timestamp
    }
}

/**
 * Chat tab: message bubbles and location cards (mine right / received left),
 * auto-scroll while following the newest item, and a pinned compose bar with
 * a payload-limit indicator and a "Share my location" button. The keyboard is
 * handled by imePadding in [MeshScreen].
 */
@Composable
fun MeshChatTab(
    nodeId: Int,
    messages: List<MeshMessage>,
    locations: List<MeshLocation>,
    myLastFix: LocationFix?,
    shareStatus: LocationShareStatus,
    onSendText: (String) -> Unit,
    onShareLocation: () -> Unit,
    // Debug builds only: a clearly-labeled toggle that shares a fixed point.
    showTestLocationToggle: Boolean = false,
    useTestLocation: Boolean = false,
    onSetUseTestLocation: (Boolean) -> Unit = {},
) {
    val listState = rememberLazyListState()
    // Follow the newest item by default. Scrolling up pauses it so old items
    // stay readable; scrolling back to the bottom resumes it.
    var follow by rememberSaveable { mutableStateOf(true) }

    // Text messages and location items share one timeline, sorted by timestamp.
    val rows: List<ChatRow> = remember(messages, locations) {
        (messages.map { ChatRow.TextMessage(it) } + locations.map { ChatRow.LocationItem(it) })
            .sortedBy { it.timestamp }
    }

    LaunchedEffect(rows.size, follow) {
        if (follow && rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }
    // rows.size is a key so the closure below always sees the current list —
    // an effect only restarts when its keys change.
    LaunchedEffect(listState, rows.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { first ->
                // Within two items of the end counts as "at the bottom".
                follow = rows.isNotEmpty() && first >= rows.size - 2
            }
    }

    /** Haversine distance to my own last fix; null when I have no recent one. */
    fun distanceFromMyLastFix(location: MeshLocation): Double? {
        val fix = myLastFix ?: return null
        if (System.currentTimeMillis() - fix.timeMs > LOCATION_STALE_AFTER_MS) return null
        return haversineMeters(
            LocationPayload(location.latitudeE6, location.longitudeE6, location.accuracyMeters),
            fix.toPayload(),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (rows.isEmpty()) {
                item(key = "empty") {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)) {
                        Text(
                            "No messages yet.\nYou are node #$nodeId — type below to broadcast.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                // Index keys: items have no id field and the list is append-only.
                items(count = rows.size, key = { it }) { index ->
                    when (val row = rows[index]) {
                        is ChatRow.TextMessage -> MessageBubble(row.message)
                        is ChatRow.LocationItem -> LocationCard(
                            location = row.location,
                            isMine = row.location.senderId == nodeId,
                            distanceMeters = distanceFromMyLastFix(row.location),
                        )
                    }
                }
            }
        }
        ComposeBar(
            onSendText = onSendText,
            onShareLocation = onShareLocation,
            shareStatus = shareStatus,
            showTestLocationToggle = showTestLocationToggle,
            useTestLocation = useTestLocation,
            onSetUseTestLocation = onSetUseTestLocation,
        )
    }
}

@Composable
private fun MessageBubble(message: MeshMessage) {
    val colors = MaterialTheme.colorScheme
    val mine = message.outgoing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                // The corner opposite the "tail" is squared off.
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (mine) 4.dp else 16.dp,
                        bottomEnd = if (mine) 16.dp else 4.dp,
                    ),
                )
                .background(if (mine) colors.primaryContainer else colors.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val metaColor = if (mine) colors.onPrimaryContainer else colors.onSurfaceVariant
                Text("#${message.senderId}", style = MaterialTheme.typography.labelSmall, color = metaColor)
                Text(formatChatTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = metaColor)
                // Relay count only makes sense for received messages: ours leave at full TTL.
                if (!mine) {
                    Text(relayCountText(message.ttl), style = MaterialTheme.typography.labelSmall, color = metaColor)
                }
            }
        }
    }
}

/**
 * Card for a delivered LOCATION item: sender id, coordinates with 5 decimals,
 * accuracy, distance from my own last fresh fix when I have one, and a copy
 * action. [distanceMeters] is precomputed by the tab so this stays previewable.
 */
@Composable
fun LocationCard(location: MeshLocation, isMine: Boolean = false, distanceMeters: Double? = null) {
    val colors = MaterialTheme.colorScheme
    val metaColor = if (isMine) colors.onPrimaryContainer else colors.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isMine) colors.primaryContainer else colors.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                "Location from #${location.senderId}" + if (isMine) " (you)" else "",
                style = MaterialTheme.typography.labelMedium,
                color = metaColor,
            )
            // Five decimals: the sixth micro-degree digit is below GPS accuracy.
            Text(
                formatCoordinates(location.latitudeE6, location.longitudeE6),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text("±${location.accuracyMeters} m", style = MaterialTheme.typography.labelSmall, color = metaColor)
            if (distanceMeters != null) {
                // Precomputed by the tab from my own last fresh fix.
                Text("${formatDistanceMeters(distanceMeters)} from you", style = MaterialTheme.typography.labelSmall, color = metaColor)
            }
            // LocalClipboardManager is deprecated in favor of LocalClipboard, but the new API
            // needs platform ClipData/ClipEntry construction for a one-line text write.
            @Suppress("DEPRECATION")
            val clipboardManager = LocalClipboardManager.current
            TextButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(formatCoordinates(location.latitudeE6, location.longitudeE6)))
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("Copy coordinates", color = metaColor)
            }
        }
    }
}

@Composable
private fun ComposeBar(
    onSendText: (String) -> Unit,
    onShareLocation: () -> Unit,
    shareStatus: LocationShareStatus,
    showTestLocationToggle: Boolean = false,
    useTestLocation: Boolean = false,
    onSetUseTestLocation: (Boolean) -> Unit = {},
) {
    var text by rememberSaveable { mutableStateOf("") }
    // One-time confirmation before the first share in this session. Cancelling
    // keeps it armed, so the next tap asks again; once a share is confirmed it
    // never asks again until the process restarts (privacy-safe direction).
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    var hasConfirmedShare by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    // The frame codec caps the payload; warn before the user hits it.
    val nearLimit = text.length > FrameCodec.MAX_PAYLOAD - 128

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (nearLimit) {
            Text(
                "${text.length} / ${FrameCodec.MAX_PAYLOAD} — near the frame payload limit",
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        when (shareStatus) {
            LocationShareStatus.IDLE -> Unit
            LocationShareStatus.WAITING_FOR_FIX -> Row(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(modifier = Modifier.width(24.dp).height(3.dp))
                Text("Getting your location…", style = MaterialTheme.typography.labelSmall)
            }
            LocationShareStatus.FAILED -> Text(
                "Could not get a fresh location fix — try again, preferably outdoors.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (showTestLocationToggle) {
            // DEBUG builds only: GPS may never fix indoors, so offer a fixed point.
            Row(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = useTestLocation, onCheckedChange = onSetUseTestLocation)
                Text(
                    "Debug: share a fixed test location (30.00000 N, 31.00000 E)",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                // Clamp at the codec limit so an overlong draft can never be sent.
                onValueChange = { if (it.length <= FrameCodec.MAX_PAYLOAD) text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Message to broadcast") },
            )
            TextButton(
                onClick = {
                    if (hasConfirmedShare) onShareLocation() else confirmShare = true
                },
                enabled = shareStatus != LocationShareStatus.WAITING_FOR_FIX,
            ) {
                Text("Share my location")
            }
            Button(onClick = { onSendText(text); text = "" }, enabled = text.isNotBlank()) {
                Text("Send")
            }
        }
    }

    if (confirmShare) {
        AlertDialog(
            onDismissRequest = { confirmShare = false },
            title = { Text("Share your location?") },
            text = { Text("Your position will be sent to every phone in the mesh.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmShare = false
                    hasConfirmedShare = true
                    onShareLocation()
                }) {
                    Text("Share")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmShare = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
