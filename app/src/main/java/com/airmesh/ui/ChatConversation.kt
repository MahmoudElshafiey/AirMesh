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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airmesh.chat.ChatDirection
import com.airmesh.chat.ChatKind
import com.airmesh.chat.ChatMessage
import com.airmesh.chat.Contact
import com.airmesh.node.LOCATION_STALE_AFTER_MS
import com.airmesh.node.LocationFix
import com.airmesh.protocol.LocationPayload
import com.airmesh.protocol.haversineMeters
import com.airmesh.profile.ProfileInfo
import com.airmesh.presence.PresenceEvent
import com.airmesh.presence.PresenceEventLog
import com.airmesh.presence.PresenceTransition
import com.airmesh.ui.components.Avatar
import com.airmesh.ui.theme.LocalAirMeshColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/** Debounce for marking read (TASK R1 part 1): coalesces bursts of IN arrivals into one update. */
private const val MARK_READ_DEBOUNCE_MS = 300L

/** One item in the chat column: a timeline row, or the "N unread" divider (TASK R1 part 2). */
private sealed interface TimelineDisplayItem {
    data class Row(val row: ChatTimelineRow) : TimelineDisplayItem
    data class UnreadDivider(val count: Int) : TimelineDisplayItem
}

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
    // Confirmed presence transitions of the peer for direct chats; the room passes none (TASK U5 part 3).
    presenceEvents: List<PresenceEvent> = emptyList(),
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

    // Timeline rows: messages merged with the peer's confirmed presence transitions; day
    // separators and grouping are decided purely (JVM-tested, TASK U5 part 3).
    val peerDisplayName = senderNameFor(conversationId, contactById[conversationId], profiles)
    val rows = remember(messages, presenceEvents, nowMs) {
        buildChatTimelineRows(messages, presenceEvents, peerDisplayName, nowMs)
    }

    // "N unread messages" divider (TASK R1 part 2): the unread set is captured ONCE when this
    // chat's rows first load — it neither grows with arrivals while open nor shrinks when
    // markRead flips them, so the divider stays until the user leaves (a new composition for the
    // conversation re-captures). The guard makes the write-during-composition a one-shot.
    var unreadAtOpen by remember(conversationId) { mutableStateOf<Set<Long>?>(null) }
    if (unreadAtOpen == null && messages.isNotEmpty()) {
        unreadAtOpen = messages.filter { it.direction == ChatDirection.IN && !it.isRead }.mapTo(mutableSetOf()) { it.id }
    }
    val unreadDividerInfo = remember(rows, unreadAtOpen) {
        unreadDivider(rows, unreadAtOpen ?: emptySet())
    }

    // One flat list for the column: the timeline rows plus — when there were unreads at open
    // time — the divider inserted right before the first of them. Its index in this list is
    // exactly [unreadDividerInfo.position], which is what the initial scroll targets.
    val displayItems = remember(rows, unreadDividerInfo) {
        val cut = unreadDividerInfo.position
        if (cut == null) rows.map { TimelineDisplayItem.Row(it) }
        else rows.take(cut).map { TimelineDisplayItem.Row(it) } +
            TimelineDisplayItem.UnreadDivider(unreadDividerInfo.count) +
            rows.drop(cut).map { TimelineDisplayItem.Row(it) }
    }

    // Transient "Back online" chip (TASK U5 part 3): shown for BACK_ONLINE_CHIP_MS after a
    // confirmed BACK_ONLINE arrives while this chat is open. The event's atMs is when they came
    // back, which precedes the debounce confirmation, so "just arrived" means atMs is within
    // debounce + slack of now; older events (e.g. opening a stale chat) stay quiet.
    var backOnlineChip by remember { mutableStateOf(false) }
    LaunchedEffect(presenceEvents) {
        val latest = presenceEvents.lastOrNull()
        if (latest != null &&
            latest.transition == PresenceTransition.BACK_ONLINE &&
            nowMs - latest.atMs <= PresenceEventLog.DEBOUNCE_MS + 5_000L
        ) {
            backOnlineChip = true
            delay(BACK_ONLINE_CHIP_MS)
            backOnlineChip = false
        } else {
            // A newer non-back-online event cancels a still-visible chip.
            backOnlineChip = false
        }
    }

    var didInitialScroll by remember(conversationId) { mutableStateOf(false) }
    LaunchedEffect(rows.size, follow) {
        val delta = rows.size - lastRowsSize
        if (follow && displayItems.isNotEmpty()) {
            if (!didInitialScroll) {
                // First load of this chat: land on the unread divider when there is one, else at
                // the bottom exactly as before (TASK R1 part 2).
                listState.animateScrollToItem(unreadDividerInfo.position ?: displayItems.lastIndex)
                didInitialScroll = true
            } else {
                listState.animateScrollToItem(displayItems.lastIndex)
            }
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
                follow = displayItems.isNotEmpty() && first >= displayItems.size - 2
            }
    }

    // Mark incoming messages read ONLY while this screen is RESUMED (TASK R1 part 1): once after
    // load, then debounced [MARK_READ_DEBOUNCE_MS] per new IN arrival so bursts coalesce into one
    // update; re-runs when the app returns to foreground with unread rows. Arrivals while the
    // screen is backgrounded (or another chat is open) stay unread until visible again. The store
    // re-emits with isRead=true, so the effect settles after one round trip.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> resumed = event == Lifecycle.Event.ON_RESUME }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed, messages) {
        if (!resumed) return@LaunchedEffect
        if (messages.any { it.direction == ChatDirection.IN && !it.isRead }) {
            delay(MARK_READ_DEBOUNCE_MS)
            onMarkRead(conversationId)
        }
    }

    var notDeliveredMessage by remember { mutableStateOf<ChatMessage?>(null) }

    // Renders one timeline row. A composable-lambda val (Compose forbids local @Composable
    // functions inside lambdas) so the LazyColumn's items() run below can call it inline.
    val renderTimelineRow: @Composable (ChatTimelineRow) -> Unit = { row ->
        when (row) {
            is ChatTimelineRow.Day -> DaySeparator(row.label)
            is ChatTimelineRow.Message -> {
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
            // Centered system row for a confirmed presence transition (TASK U5 part 3).
            is ChatTimelineRow.PresenceNote -> PresenceSystemRow(row.text)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        bannerText?.let { ConversationBanner(it) }
        if (showEncryptionNote) EncryptionNote(onDismiss = onDismissEncryptionNote)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (displayItems.isEmpty()) {
                item(key = "empty") { EmptyConversation(isMeshRoom) }
            } else {
                // Stable keys (TASK U4 part 4): messages carry the store-assigned id, and a day
                // separator's label is unique within one conversation; the divider has its own.
                // Content types keep the light separators out of the heavy message-row reuse pool.
                items(
                    displayItems,
                    key = { item -> when (item) {
                        is TimelineDisplayItem.Row -> when (val row = item.row) {
                            is ChatTimelineRow.Day -> "day:${row.label}"
                            is ChatTimelineRow.Message -> row.message.id
                            is ChatTimelineRow.PresenceNote -> "presence:${row.event.atMs}:${row.event.transition}"
                        }
                        is TimelineDisplayItem.UnreadDivider -> "unread-divider"
                    } },
                    contentType = { item -> when (item) {
                        is TimelineDisplayItem.Row -> when (val row = item.row) {
                            is ChatTimelineRow.Day -> "day"
                            is ChatTimelineRow.PresenceNote -> "presence"
                            else -> "message"
                        }
                        is TimelineDisplayItem.UnreadDivider -> "unread-divider"
                    } },
                ) { item ->
                    when (item) {
                        is TimelineDisplayItem.Row -> renderTimelineRow(item.row)
                        // The "N unread messages" divider (TASK R1 part 2): it stays until the
                        // user leaves this chat because its position comes from the open-time set.
                        is TimelineDisplayItem.UnreadDivider -> UnreadDividerRow(item.count)
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
        // Transient "Back online" chip: shown for a few seconds after a confirmed BACK_ONLINE
        // while this chat is open (TASK U5 part 3). Not clickable — it just fades away.
        if (backOnlineChip) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(50))
                    .background(LocalAirMeshColors.current.presenceOnline)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Back online", style = MaterialTheme.typography.labelMedium, color = Color.White)
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

/** Small centered system row for a confirmed presence transition (TASK U5 part 3). */
@Composable
private fun PresenceSystemRow(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Centered "N unread messages" divider shown before the first row that was unread when this chat
 * was opened (TASK R1 part 2); styled like [DaySeparator] but in the accent color so it reads as
 * an action marker, not a date.
 */
@Composable
private fun UnreadDividerRow(count: Int) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                "$count unread message" + if (count > 1) "s" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
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
