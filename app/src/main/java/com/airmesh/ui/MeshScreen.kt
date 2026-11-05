package com.airmesh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airmesh.node.LogEntry
import com.airmesh.node.MeshMessage
import com.airmesh.transport.ConnectorState
import com.airmesh.transport.P2pRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)

private fun formatTime(timeMs: Long): String =
    Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

/**
 * Main AirMesh screen: node identity, Wi-Fi Direct controls, peers, neighbors,
 * chat messages, and the shared event log.
 */
@Composable
fun MeshScreen(
    nodeId: Int,
    connectorState: ConnectorState,
    neighbors: Set<Int>,
    messages: List<MeshMessage>,
    events: List<LogEntry>,
    onRequestPermissions: () -> Unit,
    onDiscover: () -> Unit,
    onCreateGroup: () -> Unit,
    onRetryTcp: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectPeer: (WifiP2pDevice) -> Unit,
    onSendText: (String) -> Unit,
    onClearLog: () -> Unit,
    onOpenPrototype: () -> Unit,
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    val logState = rememberLazyListState()

    LaunchedEffect(events.size, showLog) {
        if (showLog && events.isNotEmpty()) logState.scrollToItem(events.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("AirMesh", style = MaterialTheme.typography.titleLarge)
        Text(
            "Node id $nodeId  •  role ${roleLabel(connectorState.role)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        StatusLine("Permission", if (connectorState.permissionsGranted) "granted" else "NOT granted")
        StatusLine(
            "Wi-Fi Direct",
            connectorState.p2pEnabled?.let { if (it) "enabled" else "DISABLED" } ?: "unknown",
        )
        StatusLine("Group owner IP", connectorState.groupOwnerAddress ?: "-")
        StatusLine(
            "Neighbors",
            if (neighbors.isEmpty()) "-" else neighbors.sorted().joinToString(", ") { "#$it" },
        )

        // Local Network Protection: without this permission the OS drops LAN TCP
        // packets, so every client connect to the group owner times out.
        if (connectorState.localNetworkRequired && !connectorState.localNetworkGranted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.error)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Local network permission missing — TCP to the group owner will time out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onRequestPermissions) { Text("Grant") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRequestPermissions) { Text("Permissions") }
            Button(onClick = onDiscover) { Text("Discover") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreateGroup) { Text("Create group") }
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            // Only meaningful for a client waiting on the owner's TCP port.
            OutlinedButton(
                onClick = onRetryTcp,
                enabled = connectorState.role == P2pRole.CLIENT && connectorState.groupFormed,
            ) {
                Text("Retry TCP")
            }
            TextButton(onClick = onOpenPrototype) { Text("Prototype") }
        }

        Text(
            "Discovered peers (${connectorState.peers.size})",
            style = MaterialTheme.typography.titleSmall,
        )
        if (connectorState.peers.isEmpty()) {
            Text(
                "No peers yet — tap Discover on both phones.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp),
            ) {
                items(connectorState.peers, key = { it.deviceAddress }) { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                device.deviceName.ifEmpty { "(unnamed)" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${device.deviceAddress}  ${deviceStatusLabel(device.status)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        // A device can only join one group at a time, so block new
                        // connect requests while a group is formed or forming.
                        Button(
                            onClick = { onConnectPeer(device) },
                            enabled = !connectorState.groupFormed && !connectorState.connecting,
                        ) {
                            Text("Connect")
                        }
                    }
                }
            }
        }

        Text("Messages (${messages.size})", style = MaterialTheme.typography.titleSmall)
        if (messages.isEmpty()) {
            Text("No messages yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(messages) { msg ->
                        Text(
                            formatMessage(msg),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Text to send") },
            )
            Button(
                onClick = {
                    onSendText(message)
                    message = ""
                },
                enabled = message.isNotBlank(),
            ) {
                Text("Send")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Event log (${events.size})",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showLog = !showLog }) {
                Text(if (showLog) "Hide" else "Show")
            }
            TextButton(onClick = { copyLog(context, events) }) { Text("Copy") }
            TextButton(onClick = onClearLog) { Text("Clear") }
        }

        if (showLog) {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                LazyColumn(
                    state = logState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                ) {
                    items(events) { entry ->
                        Text(
                            formatLogEntry(entry),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun roleLabel(role: P2pRole): String = when (role) {
    P2pRole.NONE -> "none"
    P2pRole.GROUP_OWNER -> "group owner"
    P2pRole.CLIENT -> "client"
}

private fun formatMessage(message: MeshMessage): String {
    val direction = if (message.outgoing) "TX" else "RX"
    return "${formatTime(message.timestamp)}  $direction from ${message.senderId} " +
        "ttl=${message.ttl}  ${message.text}"
}

private fun formatLogEntry(entry: LogEntry): String =
    "${formatTime(entry.timeMs)}  ${entry.text}"

private fun copyLog(context: Context, events: List<LogEntry>) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            "AirMesh event log",
            events.joinToString("\n") { formatLogEntry(it) },
        ),
    )
    Toast.makeText(context, "Event log copied to clipboard", Toast.LENGTH_SHORT).show()
}

private fun deviceStatusLabel(status: Int): String = when (status) {
    WifiP2pDevice.AVAILABLE -> "AVAILABLE"
    WifiP2pDevice.INVITED -> "INVITED"
    WifiP2pDevice.CONNECTED -> "CONNECTED"
    WifiP2pDevice.FAILED -> "FAILED"
    WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
    else -> "UNKNOWN($status)"
}
