package com.airmesh.transport

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
import androidx.compose.runtime.collectAsState
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

/** THROWAWAY PROTOTYPE UI. */
@Composable
fun PrototypeScreen(
    prototype: WifiDirectPrototype,
    onRequestPermissions: () -> Unit,
) {
    val state by prototype.state.collectAsState()
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    val logState = rememberLazyListState()

    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) logState.scrollToItem(state.log.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Wi-Fi Direct prototype", style = MaterialTheme.typography.titleLarge)
        Text(
            "THROWAWAY TEST BUILD — raw TCP text, no protocol integration",
            style = MaterialTheme.typography.bodySmall,
        )

        StatusBlock(state)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRequestPermissions) { Text("Permissions") }
            Button(onClick = prototype::discover) { Text("Discover") }
            OutlinedButton(onClick = prototype::disconnect) { Text("Disconnect") }
        }

        Text("Peers (${state.peers.size})", style = MaterialTheme.typography.titleSmall)
        if (state.peers.isEmpty()) {
            Text(
                "No peers yet — tap Discover on both phones.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn(modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 130.dp)) {
                items(state.peers) { device ->
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
                        Button(onClick = { prototype.connect(device) }) { Text("Connect") }
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
                    prototype.sendMessage(message)
                    message = ""
                },
                enabled = state.tcpConnected,
            ) {
                Text("Send")
            }
        }

        if (state.messages.isNotEmpty()) {
            SelectionContainer {
                LazyColumn(modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 110.dp)) {
                    items(state.messages) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Event log (${state.log.size})",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { copyLog(context, state.log) }) { Text("Copy log") }
            TextButton(onClick = prototype::clearLog) { Text("Clear") }
        }

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
                items(state.log) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(state: PrototypeState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StatusLine("Permission", if (state.permissionsGranted) "granted" else "NOT granted")
        StatusLine("Wi-Fi Direct", state.p2pEnabled?.let { if (it) "enabled" else "DISABLED" } ?: "unknown")
        StatusLine("Role", state.role)
        StatusLine("Group owner IP", state.groupOwnerAddress ?: "-")
        StatusLine("TCP", if (state.tcpConnected) "connected" else "disconnected")
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

private fun copyLog(context: Context, log: List<String>) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(
        ClipData.newPlainText("AirMesh Wi-Fi Direct log", log.joinToString("\n")),
    )
    Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
}

private fun deviceStatusLabel(status: Int): String = when (status) {
    WifiP2pDevice.AVAILABLE -> "AVAILABLE"
    WifiP2pDevice.INVITED -> "INVITED"
    WifiP2pDevice.CONNECTED -> "CONNECTED"
    WifiP2pDevice.FAILED -> "FAILED"
    WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
    else -> "UNKNOWN($status)"
}
