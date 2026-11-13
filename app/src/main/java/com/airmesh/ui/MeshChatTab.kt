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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airmesh.node.LOCATION_STALE_AFTER_MS
import com.airmesh.node.LocationFix
import com.airmesh.node.MeshLocation
import com.airmesh.node.MeshMessage
import com.airmesh.node.formatAgeMs
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.LocationPayload
import com.airmesh.protocol.haversineMeters
import com.airmesh.profile.ProfileInfo
import com.airmesh.service.IncomingKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull

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
 * auto-scroll while following the newest item, and a pinned compose bar — one
 * row with a location-pin icon, the text field, and a send icon. The keyboard
 * is handled by imePadding in [MeshScreen].
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
    // Debug builds only: when the fixed test point is armed (toggled in the
    // Network tab), a chip next to the pin icon reminds that it is on.
    useTestLocation: Boolean = false,
    showTestLocationToggle: Boolean = false,
    // Countdown deadline of the in-flight real-fix wait; null while none runs.
    shareDeadlineMs: Long? = null,
    onCancelShare: () -> Unit = {},
    // Specific reason of a failed attempt; null falls back to generic text.
    shareFailureMessage: String? = null,
    // My latest outgoing card gets a "cached X ago" label when its fix was last-known.
    myLatestShareWasCached: Boolean = false,
    onShareTestLocation: () -> Unit = {},
    // A notification tap asked to scroll to this item (null when none); the tab
    // consumes it via [onScrollConsumed] once the jump is done.
    scrollToKey: MessageKey? = null,
    onScrollConsumed: () -> Unit = {},
    // Live clock from MeshScreen (ticks every second) so a queued message flips
    // to "not sent" once the store-and-forward max age passes.
    nowMs: Long = System.currentTimeMillis(),
    // Display names learned from the mesh; unknown senders fall back to "User #id".
    profiles: Map<Int, ProfileInfo> = emptyMap(),
    // My own display identity, used by my location cards. My bubbles stay unnamed.
    myName: String = "User #$nodeId",
    myColorIndex: Int = 0,
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

    // A notification tap asked to scroll to a specific item; jump there once and
    // hand the request back so it is not re-applied on later recompositions.
    val rowsRef = rememberUpdatedState(rows)
    LaunchedEffect(scrollToKey) {
        val key = scrollToKey ?: return@LaunchedEffect
        follow = false // do not yank back to the newest item right after the jump
        var index = -1
        // Cold start: the tapped item may still be loading from Room; give it a
        // moment before giving up and landing at the bottom instead.
        withTimeoutOrNull(3_000) {
            while (true) {
                val current = rowsRef.value
                index = current.indexOfFirst { it.matchesChatRow(key) }
                if (index >= 0 || current.isNotEmpty()) break
                delay(250)
            }
        }
        listState.animateScrollToItem(if (index >= 0) index else rowsRef.value.lastIndex.coerceAtLeast(0))
        onScrollConsumed()
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

    // Timestamp of my most recent outgoing location card (null when I never shared).
    val myLatestLocationTimestamp = remember(locations, nodeId) {
        locations.filter { it.senderId == nodeId }.maxByOrNull { it.timestamp }?.timestamp
    }
    // Age of the underlying fix behind that card at display time; null when the
    // share used a fresh fix (or I never shared). Only my latest card knows.
    val myLatestCachedAgeMs = if (myLatestShareWasCached) {
        myLastFix?.let { System.currentTimeMillis() - it.timeMs }
    } else null

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
                        is ChatRow.TextMessage -> MessageBubble(
                            message = row.message,
                            nowMs = nowMs,
                            senderName = displayName(row.message.senderId, profiles),
                            senderColorIndex = avatarColorIndex(row.message.senderId, profiles),
                        )
                        is ChatRow.LocationItem -> LocationCard(
                            location = row.location,
                            isMine = row.location.senderId == nodeId,
                            distanceMeters = distanceFromMyLastFix(row.location),
                            cachedAgeMs = if (row.location.timestamp == myLatestLocationTimestamp) {
                                myLatestCachedAgeMs
                            } else null,
                            senderName = if (row.location.senderId == nodeId) {
                                myName
                            } else {
                                displayName(row.location.senderId, profiles)
                            },
                            senderColorIndex = if (row.location.senderId == nodeId) {
                                myColorIndex
                            } else {
                                avatarColorIndex(row.location.senderId, profiles)
                            },
                        )
                    }
                }
            }
        }
        ComposeBar(
            onSendText = onSendText,
            onShareLocation = onShareLocation,
            shareStatus = shareStatus,
            useTestLocation = useTestLocation,
            showTestLocationToggle = showTestLocationToggle,
            shareDeadlineMs = shareDeadlineMs,
            onCancelShare = onCancelShare,
            shareFailureMessage = shareFailureMessage,
            onShareTestLocation = onShareTestLocation,
        )
    }
}

@Composable
private fun MessageBubble(
    message: MeshMessage,
    nowMs: Long,
    senderName: String,
    senderColorIndex: Int,
) {
    val colors = MaterialTheme.colorScheme
    val mine = message.outgoing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Received bubbles carry a small avatar; our own bubbles stay clean.
        if (!mine) {
            AvatarCircle(
                initials = avatarInitials(senderName),
                colorIndex = senderColorIndex,
                size = 28.dp,
            )
        }
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
            if (!mine) {
                // Bold display name plus the small gray node id, so two phones
                // with the same name stay distinguishable.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 2.dp),
                ) {
                    Text(
                        senderName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("#${message.senderId}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val metaColor = if (mine) colors.onPrimaryContainer else colors.onSurfaceVariant
                // Received bubbles already show the id in the header line.
                if (mine) {
                    Text("#${message.senderId}", style = MaterialTheme.typography.labelSmall, color = metaColor)
                }
                Text(formatChatTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = metaColor)
                // Relay count only makes sense for received messages: ours leave at full TTL.
                if (!mine) {
                    Text(relayCountText(message.ttl), style = MaterialTheme.typography.labelSmall, color = metaColor)
                } else {
                    // Ours show how far they got instead: sent / queued / expired.
                    val ageMs = nowMs - message.timestamp
                    val expired = deliveryStatus(message.handedTo.size, ageMs) == DeliveryStatus.EXPIRED
                    Text(
                        deliveryStatusText(message.handedTo.size, ageMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (expired) colors.error else metaColor,
                    )
                }
            }
        }
    }
}

/**
 * Card for a delivered LOCATION item: sender id, coordinates with 5 decimals,
 * accuracy, distance from my own last fresh fix when I have one, and a copy
 * action. [distanceMeters] is precomputed by the tab so this stays previewable.
 * [cachedAgeMs] labels a card whose fix was a last-known (not live) one.
 */
@Composable
fun LocationCard(
    location: MeshLocation,
    isMine: Boolean = false,
    distanceMeters: Double? = null,
    cachedAgeMs: Long? = null,
    senderName: String? = null,
    senderColorIndex: Int = 0,
) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (senderName != null) {
                    AvatarCircle(
                        initials = avatarInitials(senderName),
                        colorIndex = senderColorIndex,
                        size = 22.dp,
                    )
                }
                Text(
                    // Name when the mesh knows it; otherwise the id, as before.
                    "Location from ${senderName ?: "#${location.senderId}"}" +
                        if (isMine) " (you)" else "",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (senderName != null) {
                    Text("#${location.senderId}", style = MaterialTheme.typography.labelSmall, color = metaColor)
                }
            }
            // Five decimals: the sixth micro-degree digit is below GPS accuracy.
            Text(
                formatCoordinates(location.latitudeE6, location.longitudeE6),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text("±${location.accuracyMeters} m", style = MaterialTheme.typography.labelSmall, color = metaColor)
            if (cachedAgeMs != null) {
                // This card's fix was a last-known one; say how old it is.
                Text("cached ${formatAgeMs(cachedAgeMs)} ago", style = MaterialTheme.typography.labelSmall, color = metaColor)
            }
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

/** Material's minimum touch target; the spec calls for 48dp on both icon buttons. */
private val IconTouchTarget = 48.dp

@Composable
private fun ComposeBar(
    onSendText: (String) -> Unit,
    onShareLocation: () -> Unit,
    shareStatus: LocationShareStatus,
    useTestLocation: Boolean = false,
    showTestLocationToggle: Boolean = false,
    // Countdown deadline of the in-flight real-fix wait; null while none runs.
    shareDeadlineMs: Long? = null,
    onCancelShare: () -> Unit = {},
    // Specific reason of a failed attempt; null falls back to generic text.
    shareFailureMessage: String? = null,
    onShareTestLocation: () -> Unit = {},
) {
    var text by rememberSaveable { mutableStateOf("") }
    // One-time confirmation before the first share in this session. Cancelling
    // keeps it armed, so the next tap asks again; once a share is confirmed it
    // never asks again until the process restarts (privacy-safe direction).
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    var hasConfirmedShare by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        when (shareStatus) {
            LocationShareStatus.IDLE -> Unit
            LocationShareStatus.WAITING_FOR_FIX -> ShareWaitingRow(shareDeadlineMs, onCancelShare)
            LocationShareStatus.FAILED -> Column(modifier = Modifier.padding(bottom = 4.dp)) {
                Text(
                    shareFailureMessage ?: "Could not get a fresh location fix — try again, preferably outdoors.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.error,
                )
                // Debug builds only: offer the fixed test point when real sharing failed.
                if (showTestLocationToggle && !useTestLocation) {
                    TextButton(
                        onClick = onShareTestLocation,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("Share the test location instead", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        // One row: pin icon, field, send. Icon triggers keep the hint on one line.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = { if (hasConfirmedShare) onShareLocation() else confirmShare = true },
                enabled = shareStatus != LocationShareStatus.WAITING_FOR_FIX,
                modifier = Modifier.size(IconTouchTarget),
            ) {
                Icon(imageVector = MeshLocationPinIcon, contentDescription = "Share my location")
            }
            if (useTestLocation) TestLocationChip()
            OutlinedTextField(
                value = text,
                // Clamp at the codec limit so an overlong draft can never be sent.
                onValueChange = { if (it.length <= FrameCodec.MAX_PAYLOAD) text = it },
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 4,
                // Pill shape to match the round Send button.
                shape = RoundedCornerShape(50),
                placeholder = { Text("Message", maxLines = 1) },
            )
            FilledIconButton(
                onClick = { onSendText(text); text = "" },
                enabled = text.isNotBlank(),
                modifier = Modifier.size(IconTouchTarget),
            ) {
                Icon(imageVector = MeshSendIcon, contentDescription = "Send")
            }
        }
        // The frame codec caps the payload; warn only while a draft nears it.
        if (isNearPayloadLimit(text.length)) {
            Text(
                payloadLimitText(text.length),
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
                modifier = Modifier.padding(top = 4.dp),
            )
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

/** Waiting row above the compose bar: progress, countdown to the fix deadline, Cancel. */
@Composable
private fun ShareWaitingRow(deadlineMs: Long?, onCancel: () -> Unit) {
    // Tick 4x/s while waiting; [deadlineMs] is null for the instant test point.
    var remainingSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(deadlineMs) {
        if (deadlineMs == null) return@LaunchedEffect
        while (true) {
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) break
            // Ceiling so the last partial second still reads "1s", never "0s".
            remainingSeconds = ((remaining + 999) / 1000).toInt()
            delay(250)
        }
    }
    Row(
        modifier = Modifier.padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(modifier = Modifier.width(24.dp).height(3.dp))
        Text(
            if (deadlineMs == null) "Getting your location…" else "Getting your location… ${remainingSeconds}s",
            style = MaterialTheme.typography.labelSmall,
        )
        TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            Text("Cancel")
        }
    }
}

/** Whether a chat row is the item a notification tap asked to scroll to. */
private fun ChatRow.matchesChatRow(key: MessageKey): Boolean = when (key.kind) {
    IncomingKind.TEXT -> this is ChatRow.TextMessage &&
        message.senderId == key.senderId && message.timestamp == key.timestampMs
    IncomingKind.LOCATION -> this is ChatRow.LocationItem &&
        location.senderId == key.senderId && location.timestamp == key.timestampMs
}

/** Small reminder that the debug fixed test point is armed (debug builds only). */
@Composable
private fun TestLocationChip() {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .background(colors.tertiaryContainer, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text("TEST LOCATION", style = MaterialTheme.typography.labelSmall, color = colors.onTertiaryContainer)
    }
}
