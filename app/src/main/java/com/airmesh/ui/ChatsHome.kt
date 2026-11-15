package com.airmesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airmesh.chat.Contact
import com.airmesh.chat.ConversationItem
import com.airmesh.chat.ConversationListBuilder
import com.airmesh.chat.ConversationSummary
import com.airmesh.chat.MESH_CONVERSATION_ID
import com.airmesh.chat.NodePresence
import com.airmesh.chat.PresenceState
import com.airmesh.ui.components.Avatar
import com.airmesh.ui.components.ConversationRow
import com.airmesh.ui.components.PresenceText
import com.airmesh.ui.components.RadarSearchIndicator
import com.airmesh.ui.components.RadarStaticFrame
import com.airmesh.ui.components.SectionHeader
import com.airmesh.ui.components.UnreadBadge
import com.airmesh.ui.theme.AirMeshSpacing
import com.airmesh.ui.theme.LocalAirMeshColors

/**
 * WhatsApp-style chats home (TASK U1 part 3): the pinned "Everyone nearby" room, then
 * conversations with messages ("Chats"), then live contacts that have no conversation yet
 * ("People in your network"). The rows come from the pure [ConversationListBuilder]; this
 * composable only renders them. When both sections are empty, a kind-driven empty state
 * (only SEARCHING/CONNECTING animate — TASK U3 part 2) sits below the still-tappable room.
 */
@Composable
fun ChatsHomeContent(
    summaries: List<ConversationSummary>,
    contacts: List<Contact>,
    presence: Map<Int, NodePresence>,
    nowMs: Long,
    onOpenConversation: (Int) -> Unit,
    // What the empty state below the pinned room should show (TASK U4 part 1): kind + headline
    // + optional caption + manual-connect button flag. Defaults keep previews compiling;
    // MeshScreen passes the real values from homeEmptyState(...).
    homeEmptyState: HomeEmptyState =
        HomeEmptyState(EmptyStateKind.SEARCHING, HOME_SEARCHING_HEADLINE, null, false),
    onGrantPermission: () -> Unit = {},
    onManualConnect: () -> Unit = {},
    onUiEventLog: (String) -> Unit = {},
) {
    val rows = remember(summaries, contacts, presence, nowMs) {
        ConversationListBuilder.build(summaries, contacts, presence, nowMs)
    }
    // The builder's flat list splits into the three sections by construction: the room is
    // id 0, conversation rows carry a last-message time, people rows do not.
    val roomRow = rows.firstOrNull { it.conversationId == MESH_CONVERSATION_ID }
    val chatRows = rows.filter { it.timeMs > 0L && it.conversationId != MESH_CONVERSATION_ID }
    val peopleRows = rows.filter { it.timeMs == 0L && it.conversationId != MESH_CONVERSATION_ID }

    if (chatRows.isEmpty() && peopleRows.isEmpty()) {
        // Nothing to list yet: keep the pinned room tappable and show the searching state.
        Column(modifier = Modifier.fillMaxSize()) {
            roomRow?.let { row -> MeshRoomRow(row = row, nowMs = nowMs) { onOpenConversation(MESH_CONVERSATION_ID) } }
            // The box must span the full width (weight alone only sizes the height in a
            // Column) or contentAlignment=Center has no room to center anything. The 96dp
            // bottom padding keeps the block clear of the "New message" FAB; at 320dp max
            // width it stays readable on wide/landscape screens too.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ChatsEmptyState(
                    state = homeEmptyState,
                    onGrantPermission = onGrantPermission,
                    onManualConnect = onManualConnect,
                    onUiEventLog = onUiEventLog,
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .padding(bottom = 96.dp)
                        .widthIn(max = 320.dp),
                )
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Distinct content types per row kind (TASK U4 part 4) so the lazy list keeps
            // separate reuse pools instead of recycling one heavy row into another.
            roomRow?.let { row ->
                item(key = "room", contentType = { "room" }) { MeshRoomRow(row = row, nowMs = nowMs) { onOpenConversation(MESH_CONVERSATION_ID) } }
            }
            if (chatRows.isNotEmpty()) {
                item(key = "chatsHeader", contentType = { "header" }) { SectionHeader("Chats") }
                items(chatRows, key = { it.conversationId }, contentType = { "chat" }) { row ->
                    ConversationRow(
                        title = row.title,
                        colorIndex = row.colorIndex,
                        preview = row.preview,
                        timeText = formatListTime(row.timeMs, nowMs),
                        unreadCount = row.unreadCount,
                        // No dot for UNKNOWN peers — a gray dot would read as "offline".
                        presence = row.presence.state.takeIf { it != PresenceState.UNKNOWN },
                        onClick = { onOpenConversation(row.conversationId) },
                    )
                }
            }
            if (peopleRows.isNotEmpty()) {
                item(key = "peopleHeader", contentType = { "header" }) { SectionHeader("People in your network") }
                items(peopleRows, key = { it.conversationId }, contentType = { "person" }) { row ->
                    PersonRow(item = row, nowMs = nowMs) { onOpenConversation(row.conversationId) }
                }
            }
        }
    }
}

/**
 * The pinned "Everyone nearby" room row (TASK U3 part 4). It is not a node, so instead of an
 * initials avatar it shows the mesh glyph in brand color — the same icon the chat header uses
 * for the room. Until the first message arrives the subtitle says "No messages yet"; after
 * that it carries the last-message preview, and time/unread badge appear when present.
 */
@Composable
private fun MeshRoomRow(row: ConversationItem, nowMs: Long, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AirMeshSpacing.md, vertical = AirMeshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = MeshNetworkIcon, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(26.dp))
        }
        Spacer(modifier = Modifier.width(AirMeshSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.title,
                    color = colors.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val timeText = formatListTime(row.timeMs, nowMs)
                if (timeText.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(AirMeshSpacing.sm))
                    Text(
                        text = timeText,
                        color = if (row.unreadCount > 0) colors.primary else colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.preview.ifEmpty { "No messages yet" },
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (row.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(AirMeshSpacing.sm))
                    UnreadBadge(count = row.unreadCount)
                }
            }
        }
    }
}

/**
 * The empty state below the pinned room (TASK U3 part 2, reworked in TASK U4 part 1), driven by
 * [HomeEmptyState]. Only SEARCHING and CONNECTING animate via [RadarSearchIndicator]; every other
 * kind draws the composed static radar frame ([RadarStaticFrame]) so quiet states stay calm but
 * never look like plain dots. The headline is fixed per state; the caption (when present) carries
 * the "other devices are ignored" / long-wait note without repeating any banner text. Internal
 * so previews can render each scenario directly.
 */
@Composable
internal fun ChatsEmptyState(
    state: HomeEmptyState,
    onGrantPermission: () -> Unit = {},
    onManualConnect: () -> Unit = {},
    onUiEventLog: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AirMeshSpacing.sm),
    ) {
        if (state.kind == EmptyStateKind.SEARCHING || state.kind == EmptyStateKind.CONNECTING) {
            RadarSearchIndicator(
                kind = state.kind,
                periodMs = if (state.kind == EmptyStateKind.SEARCHING) RADAR_SEARCHING_PERIOD_MS else RADAR_CONNECTING_PERIOD_MS,
                logEvent = onUiEventLog,
            )
        } else {
            RadarStaticFrame()
        }
        Spacer(modifier = Modifier.height(AirMeshSpacing.xs))
        Text(
            text = state.headline,
            color = colors.onSurface,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (state.caption != null) {
            Text(
                text = state.caption,
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        if (state.kind == EmptyStateKind.PERMISSION_MISSING) {
            Button(onClick = onGrantPermission) { Text("Grant permission") }
        }
        if (state.showManualButton) {
            // Escape hatch after a long search: opens the Network tab with Advanced expanded.
            Button(onClick = onManualConnect) { Text("Manual connect") }
        }
    }
}

/** A contact without a conversation yet: avatar with presence dot, name and presence line. */
@Composable
private fun PersonRow(item: ConversationItem, nowMs: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AirMeshSpacing.md, vertical = AirMeshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The builder only lists ONLINE/RECENT contacts here, so the dot always means something.
        Avatar(name = item.title, colorIndex = item.colorIndex, size = 48.dp, presence = item.presence.state)
        Spacer(modifier = Modifier.width(AirMeshSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PresenceText(presence = item.presence, nowMs = nowMs)
        }
    }
}

/**
 * Bottom sheet behind the "New message" FAB (TASK U1 part 3): every known contact with its
 * presence; tapping one opens that direct conversation. Internal so previews can render it.
 */
@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet is experimental in this M3 version
@Composable
internal fun ContactPickerSheet(
    contacts: List<Contact>,
    presence: Map<Int, NodePresence>,
    nowMs: Long,
    onOpenConversation: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = AirMeshSpacing.md)) {
            Text(
                text = "New message",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = AirMeshSpacing.sm),
            )
            if (contacts.isEmpty()) {
                Text(
                    text = "No contacts yet — phones you meet will appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // Bounded height so a long contact list scrolls inside the sheet.
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(contacts, key = { it.nodeId }) { contact ->
                        val nodePresence = presence[contact.nodeId] ?: NodePresence(PresenceState.UNKNOWN, false)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = { onOpenConversation(contact.nodeId) })
                                .padding(vertical = AirMeshSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                name = ConversationListBuilder.titleFor(contact.nodeId, contact),
                                colorIndex = contact.colorIndex,
                                size = 40.dp,
                                presence = nodePresence.state.takeIf { it != PresenceState.UNKNOWN },
                            )
                            Spacer(modifier = Modifier.width(AirMeshSpacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ConversationListBuilder.titleFor(contact.nodeId, contact),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                PresenceText(presence = nodePresence, nowMs = nowMs)
                            }
                        }
                    }
                }
            }
        }
        // Keep the last row clear of the navigation bar.
        Spacer(modifier = Modifier.height(AirMeshSpacing.xl))
    }
}

/**
 * The overflow menu's "About" dialog (TASK U1 part 3): what AirMesh is, the honest note that
 * messages are not encrypted, and the reminder that it does not replace emergency services.
 */
@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About AirMesh") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AirMeshSpacing.sm)) {
                Text(
                    text = "AirMesh is a mesh messenger for disaster scenarios: phones relay messages to each other over Wi-Fi Direct without cellular or internet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Messages are not encrypted. Do not send sensitive information.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "AirMesh does not replace emergency services (112/AFAD).",
                    color = LocalAirMeshColors.current.warning,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
