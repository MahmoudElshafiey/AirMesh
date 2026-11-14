package com.airmesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airmesh.chat.ChatDirection
import com.airmesh.chat.ChatKind
import com.airmesh.chat.ChatMessage
import com.airmesh.chat.Contact
import com.airmesh.node.LOCATION_STALE_AFTER_MS
import com.airmesh.node.LocationFix
import com.airmesh.protocol.LocationPayload
import com.airmesh.protocol.haversineMeters
import com.airmesh.profile.ProfileInfo
import com.airmesh.ui.components.Avatar
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Redesigned conversation screen (TASK U2 part 1): WhatsApp-style bubbles with tails,
 * sender names in the avatar color inside the mesh room, sticky date separators, delivery
 * glyphs on outgoing direct messages, location cards with an "Open in maps" action, warning
 * banners and a one-time "not encrypted" note. Every decision (grouping, day labels, banner
 * text, glyph choice) lives in the pure [ChatScreenUiHelpers] functions; this file only
 * renders them. The top bar (avatar/name/subtitle/overflow menu) is owned by [MeshScreen]'s
 * shared Scaffold so it stays consistent with the other routes.
 */
@Composable
fun ChatConversationContent(
    conversationId: Int,
    isMeshRoom: Boolean,
    messages: List<ChatMessage>,
    nowMs: Long,
    myLastFix: LocationFix?,
    contactById: Map<Int, Contact> = emptyMap(),
    profiles: Map<Int, ProfileInfo> = emptyMap(),
    bannerText: String? = null,
    showEncryptionNote: Boolean = false,
    onDismissEncryptionNote: () -> Unit = {},
    // Already routed by the caller: broadcast in the room, direct to the peer otherwise.
    onSendText: (String) -> Unit,
    onShareLocation: () -> Unit = {},
    shareStatus: LocationShareStatus = LocationShareStatus.IDLE,
    shareDeadlineMs: Long? = null,
    onCancelShare: () -> Unit = {},
    shareFailureMessage: String? = null,
    myLatestShareWasCached: Boolean = false,
    useTestLocation: Boolean = false,
    showTestLocationToggle: Boolean = false,
    onShareTestLocation: () -> Unit = {},
    onMarkRead: (Int) -> Unit = {},
    // Resending a failed outgoing message sends its text again as a NEW message.
    onResendDirect: (String) -> Unit = {},
    onOpenInMaps: (latitudeE6: Int, longitudeE6: Int) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    // Follow the newest item by default; scrolling up pauses it so history stays readable.
    var follow by rememberSaveable { mutableStateOf(true) }
    var unseenCount by rememberSaveable { mutableIntStateOf(0) }
    var lastRowsSize by remember { mutableIntStateOf(0) }

    // Sticky date separators and grouping flags, decided purely (JVM-tested).
    val rows = remember(messages, nowMs) { buildChatUiRows(messages, nowMs) }

    LaunchedEffect(rows.size, follow) {
        val delta = rows.size - lastRowsSize
        if (follow && rows.isNotEmpty()) {
            listState.animateScrollToItem(rows.lastIndex)
            unseenCount = 0
        } else if (!follow && delta > 0) {
            // New messages arrived while the user was reading history: count them.
            unseenCount += delta
        }
        lastRowsSize = rows.size
    }
    LaunchedEffect(listState, rows.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { first ->
                // Within two items of the end counts as "at the bottom".
                follow = rows.isNotEmpty() && first >= rows.size - 2
            }
    }

    // Mark incoming messages read while this screen is visible (TASK U2 part 1). The store
    // re-emits with isRead=true, so the effect settles after one round trip.
    LaunchedEffect(messages) {
        if (messages.any { it.direction == ChatDirection.IN && !it.isRead }) onMarkRead(conversationId)
    }

    var notDeliveredMessage by remember { mutableStateOf<ChatMessage?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        bannerText?.let { ConversationBanner(it) }
        if (showEncryptionNote) EncryptionNote(onDismiss = onDismissEncryptionNote)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (rows.isEmpty()) {
                item(key = "empty") { EmptyConversation(isMeshRoom) }
            } else {
                // Index keys: the list is append-only and rows have no stable id of their own.
                items(count = rows.size, key = { it }) { index ->
                    when (val row = rows[index]) {
                        is ChatUiRow.Day -> DaySeparator(row.label)
                        is ChatUiRow.Message -> {
                            val contact = contactById[row.message.originNode]
                            ChatMessageRow(
                                message = row.message,
                                newGroup = row.newGroup,
                                isMeshRoom = isMeshRoom,
                                senderName = senderNameFor(row.message.originNode, contact, profiles),
                                senderColorIndex = senderColorIndexFor(row.message.originNode, contact, profiles),
                                nowMs = nowMs,
                                myLastFix = myLastFix,
                                onOpenInMaps = onOpenInMaps,
                                onNotDeliveredTap = { notDeliveredMessage = it },
                            )
                        }
                    }
                }
            }
        }
        // "New messages" chip: shown while the user reads history and something arrived.
        if (!follow && unseenCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable {
                        // Flipping follow re-runs the scroll effect above, which animates to the end.
                        follow = true
                        unseenCount = 0
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "$unseenCount new message" + if (unseenCount > 1) "s" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        // The same composer as the old chat tab; location shares are broadcast-only by
        // protocol, so direct chats hide the pin.
        ComposeBar(
            onSendText = onSendText,
            showLocationButton = isMeshRoom,
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

    notDeliveredMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { notDeliveredMessage = null },
            title = { Text("Not delivered") },
            text = { Text("This message could not be delivered. Resending sends it again as a new message.") },
            confirmButton = {
                TextButton(onClick = {
                    val text = message.text.orEmpty()
                    notDeliveredMessage = null
                    if (text.isNotBlank()) onResendDirect(text)
                }) {
                    Text("Resend")
                }
            },
            dismissButton = {
                TextButton(onClick = { notDeliveredMessage = null }) { Text("Cancel") }
            },
        )
    }
}

/** One bubble row: optional avatar + sender name (group start, mesh room only), the bubble. */
@Composable
internal fun ChatMessageRow(
    message: ChatMessage,
    newGroup: Boolean,
    isMeshRoom: Boolean,
    senderName: String,
    senderColorIndex: Int,
    nowMs: Long,
    myLastFix: LocationFix?,
    onOpenInMaps: (latitudeE6: Int, longitudeE6: Int) -> Unit,
    onNotDeliveredTap: (ChatMessage) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val mine = message.direction == ChatDirection.OUT
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Avatar only on the first bubble of a group (WhatsApp style).
        if (!mine && newGroup) {
            Avatar(name = senderName, colorIndex = senderColorIndex, size = 28.dp)
        }
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            // Sender name above the first bubble of a group — mesh room only; direct chats
            // already know who they are talking to. Colored with the sender's avatar color.
            if (!mine && newGroup && isMeshRoom) {
                Text(
                    senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(avatarColor(senderColorIndex)),
                    modifier = Modifier.padding(start = 34.dp, bottom = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                modifier = Modifier
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
                when (message.kind) {
                    ChatKind.TEXT -> Text(message.text.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    ChatKind.LOCATION -> LocationBubbleContent(
                        message = message,
                        isMine = mine,
                        distanceMeters = distanceFromMyLastFix(message, myLastFix, nowMs),
                        onOpenInMaps = onOpenInMaps,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val metaColor = if (mine) colors.onPrimaryContainer else colors.onSurfaceVariant
                    Text(formatChatTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = metaColor)
                    // Delivery glyphs only make sense for outgoing direct messages: a
                    // broadcast has no single recipient to acknowledge it.
                    if (mine && !isMeshRoom) {
                        val glyph = deliveryGlyph(message.status, nowMs - message.createdAt)
                        when (glyph) {
                            DeliveryGlyph.CLOCK -> Icon(
                                MeshClockIcon, "Queued", modifier = Modifier.size(12.dp), tint = metaColor,
                            )
                            DeliveryGlyph.ONE_TICK -> Icon(
                                MeshCheckIcon, "Sent", modifier = Modifier.size(12.dp), tint = metaColor,
                            )
                            DeliveryGlyph.TWO_TICKS -> Icon(
                                MeshDoubleCheckIcon, "Delivered", modifier = Modifier.size(12.dp), tint = metaColor,
                            )
                            DeliveryGlyph.EXCLAMATION -> Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable(onClick = { onNotDeliveredTap(message) })
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    MeshAlertIcon, "Not delivered — tap to resend",
                                    modifier = Modifier.size(14.dp), tint = colors.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Location card inside a bubble: coordinates with 5 decimals (the sixth micro-degree digit is
 * below GPS accuracy), accuracy, distance from my own last fresh fix when I have one, and the
 * "Open in maps" / "Copy" actions. [distanceMeters] is precomputed so this stays previewable.
 */
@Composable
internal fun LocationBubbleContent(
    message: ChatMessage,
    isMine: Boolean,
    distanceMeters: Double?,
    onOpenInMaps: (latitudeE6: Int, longitudeE6: Int) -> Unit,
) {
    val lat = message.latitudeE6 ?: return
    val lon = message.longitudeE6 ?: return
    val colors = MaterialTheme.colorScheme
    val metaColor = if (isMine) colors.onPrimaryContainer else colors.onSurfaceVariant
    Text(
        formatCoordinates(lat, lon),
        style = MaterialTheme.typography.bodyLarge,
        fontFamily = FontFamily.Monospace,
    )
    message.accuracyMeters?.let { accuracy ->
        Text("±$accuracy m", style = MaterialTheme.typography.labelSmall, color = metaColor)
    }
    distanceMeters?.let { meters ->
        Text("${formatDistanceMeters(meters)} from you", style = MaterialTheme.typography.labelSmall, color = metaColor)
    }
    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(
            onClick = { onOpenInMaps(lat, lon) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text("Open in maps", color = metaColor, style = MaterialTheme.typography.labelMedium)
        }
        // LocalClipboardManager is deprecated in favor of LocalClipboard, but the new API
        // needs platform ClipData/ClipEntry construction for a one-line text write.
        @Suppress("DEPRECATION")
        val clipboardManager = LocalClipboardManager.current
        TextButton(
            onClick = { clipboardManager.setText(AnnotatedString(formatCoordinates(lat, lon))) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text("Copy", color = metaColor, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Haversine distance from my own last fresh fix; null when I have none or it is stale. */
private fun distanceFromMyLastFix(message: ChatMessage, myLastFix: LocationFix?, nowMs: Long): Double? {
    val lat = message.latitudeE6 ?: return null
    val lon = message.longitudeE6 ?: return null
    val fix = myLastFix ?: return null
    if (nowMs - fix.timeMs > LOCATION_STALE_AFTER_MS) return null
    return haversineMeters(
        LocationPayload(lat, lon, message.accuracyMeters ?: 0),
        fix.toPayload(),
    )
}

/** Sticky date separator pill ("Today", "Yesterday" or a localized date). */
@Composable
private fun DaySeparator(label: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Warning banner above the list: offline contact or not connected at all. */
@Composable
private fun ConversationBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

/** One-time dismissible note: the mesh is not encrypted (TASK U2 part 1). */
@Composable
private fun EncryptionNote(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Messages are not encrypted; phones that relay them can read them.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = MeshCloseIcon,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/** Empty conversation state; the wording differs for room vs. direct chat. */
@Composable
private fun EmptyConversation(isMeshRoom: Boolean) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)) {
        Text(
            text = if (isMeshRoom) {
                "No messages yet.\nType below to broadcast to everyone nearby."
            } else {
                "No messages yet.\nStart the conversation — it will be delivered when you are in range."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Overflow menu's "Contact info" dialog (TASK U2 part 1): the peer's display name and node id
 * with a copy action; in the mesh room it explains what broadcasts reach instead.
 */
@Composable
internal fun ContactInfoDialog(
    conversationId: Int,
    title: String,
    isMeshRoom: Boolean,
    onCopyNodeId: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (isMeshRoom) {
                Text("Broadcasts reach every phone in your Wi-Fi Direct group.")
            } else {
                Text("Node #$conversationId")
            }
        },
        confirmButton = {
            if (isMeshRoom) {
                TextButton(onClick = onDismiss) { Text("OK") }
            } else {
                TextButton(onClick = { onCopyNodeId(); onDismiss() }) { Text("Copy node id") }
            }
        },
        dismissButton = {
            if (!isMeshRoom) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
