package com.airmesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airmesh.node.MeshMessage
import com.airmesh.protocol.FrameCodec
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * One row in the chat list. New message kinds (e.g. a future LOCATION item)
 * add a subtype plus one `when` branch below — no changes to scrolling,
 * keys or layout are needed for that.
 */
sealed interface ChatRow {
    data class TextMessage(val message: MeshMessage) : ChatRow
}

/**
 * Chat tab: message bubbles (mine right / received left), auto-scroll while
 * following the newest message, and a pinned compose bar with a payload-limit
 * indicator. The keyboard is handled by imePadding in [MeshScreen].
 */
@Composable
fun MeshChatTab(
    nodeId: Int,
    messages: List<MeshMessage>,
    onSendText: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    // Follow the newest message by default. Scrolling up pauses it so old
    // messages stay readable; scrolling back to the bottom resumes it.
    var follow by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(messages.size, follow) {
        if (follow && messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { first ->
                // Within two items of the end counts as "at the bottom".
                follow = messages.isNotEmpty() && first >= messages.size - 2
            }
    }

    val rows: List<ChatRow> = remember(messages) { messages.map { ChatRow.TextMessage(it) } }

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
                // Index keys: MeshMessage has no id field and the list is append-only.
                items(count = rows.size, key = { it }) { index ->
                    when (val row = rows[index]) {
                        is ChatRow.TextMessage -> MessageBubble(row.message)
                    }
                }
            }
        }
        ComposeBar(onSendText = onSendText)
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

@Composable
private fun ComposeBar(onSendText: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
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
            Button(onClick = { onSendText(text); text = "" }, enabled = text.isNotBlank()) {
                Text("Send")
            }
        }
    }
}
