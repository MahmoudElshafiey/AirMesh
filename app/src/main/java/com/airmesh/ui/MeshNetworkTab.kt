package com.airmesh.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airmesh.transport.ConnectorState
import com.airmesh.transport.P2pRole

/** Minimum touch target height required by the design spec. */
private val MinTouch = 48.dp

/**
 * Network tab: local-network permission banner, a status card, the action
 * buttons (equal width, two per row), discovered peers with Connect, and the
 * current neighbor list. Everything scrolls; the screen itself is fixed.
 */
@Composable
fun MeshNetworkTab(
    nodeId: Int,
    state: ConnectorState,
    neighbors: Set<Int>,
    onRequestPermissions: () -> Unit,
    onDiscover: () -> Unit,
    onCreateGroup: () -> Unit,
    onRetryTcp: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectPeer: (WifiP2pDevice) -> Unit,
    onOpenPrototype: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.localNetworkRequired && !state.localNetworkGranted) {
            LocalNetworkBanner(onGrant = onRequestPermissions)
        }

        StatusCard(nodeId, state)

        // 45% width per button => exactly two equal-width buttons per row.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onRequestPermissions, modifier = ActionButtonModifier) {
                Text("Permissions", maxLines = 1)
            }
            Button(onClick = onDiscover, modifier = ActionButtonModifier) {
                Text("Discover", maxLines = 1)
            }
            Button(onClick = onCreateGroup, modifier = ActionButtonModifier) {
                Text("Create group", maxLines = 1)
            }
            OutlinedButton(onClick = onDisconnect, modifier = ActionButtonModifier) {
                Text("Disconnect", maxLines = 1)
            }
            // Only meaningful for a client waiting on the owner's TCP port.
            OutlinedButton(
                onClick = onRetryTcp,
                enabled = state.role == P2pRole.CLIENT && state.groupFormed,
                modifier = ActionButtonModifier,
            ) {
                Text("Retry TCP", maxLines = 1)
            }
            TextButton(onClick = onOpenPrototype, modifier = ActionButtonModifier) {
                Text("Prototype", maxLines = 1)
            }
        }

        PeersSection(state, onConnectPeer)

        NeighborsSection(neighbors)
    }
}

/** Shared size for the action buttons: equal width (two per row) and a 48dp touch target. */
private val ActionButtonModifier = Modifier
    .fillMaxWidth(0.45f)
    .heightIn(min = MinTouch)

/** Red banner shown on Android 17+ while ACCESS_LOCAL_NETWORK is missing. */
@Composable
private fun LocalNetworkBanner(onGrant: () -> Unit) {
    // Without this permission the OS drops LAN TCP packets, so every client
    // connect to the group owner times out instead of failing fast.
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
        Button(onClick = onGrant, modifier = Modifier.heightIn(min = MinTouch)) { Text("Grant") }
    }
}

/** Key/value card with the current Wi-Fi Direct and group state. */
@Composable
private fun StatusCard(nodeId: Int, state: ConnectorState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Status", style = MaterialTheme.typography.titleSmall)
            StatusRow("Node id", "#$nodeId")
            StatusRow("Role", roleLabel(state.role))
            StatusRow("Wi-Fi Direct", wifiDirectStateLabel(state.p2pEnabled))
            StatusRow("Group owner IP", state.groupOwnerAddress ?: "—")
            StatusRow(
                "Permissions",
                if (state.permissionsGranted) "granted" else "missing — tap Permissions",
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Discovered peers with a Connect button; disabled (with the reason shown)
 * while a group is formed or a connect request is in flight. */
@Composable
private fun PeersSection(state: ConnectorState, onConnectPeer: (WifiP2pDevice) -> Unit) {
    Text("Discovered peers (${state.peers.size})", style = MaterialTheme.typography.titleSmall)
    if (state.peers.isEmpty()) {
        Text(
            "No peers yet — tap Discover on both phones.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val connectBlockedReason = when {
        state.groupFormed -> "Disconnect from the current group first"
        state.connecting -> "Connect in progress…"
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.peers.forEach { device ->
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                    enabled = connectBlockedReason == null,
                    modifier = Modifier.heightIn(min = MinTouch),
                ) {
                    Text("Connect")
                }
            }
            if (connectBlockedReason != null) {
                Text(
                    "Connect disabled: $connectBlockedReason",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Current neighbors as small chips with their node ids. */
@Composable
private fun NeighborsSection(neighbors: Set<Int>) {
    Text("Neighbors (${neighbors.size})", style = MaterialTheme.typography.titleSmall)
    if (neighbors.isEmpty()) {
        Text(
            "No live neighbors yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        neighbors.sorted().forEach { id ->
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("#$id", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun deviceStatusLabel(status: Int): String = when (status) {
    WifiP2pDevice.AVAILABLE -> "AVAILABLE"
    WifiP2pDevice.INVITED -> "INVITED"
    WifiP2pDevice.CONNECTED -> "CONNECTED"
    else -> "UNKNOWN"
}
