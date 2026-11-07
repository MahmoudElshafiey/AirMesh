package com.airmesh.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airmesh.transport.AirMeshPeer
import com.airmesh.transport.ConnectorState
import com.airmesh.transport.P2pRole

/** Minimum touch target height required by the design spec. */
private val MinTouch = 48.dp

/**
 * Network tab: local-network permission banner, a status card, the action
 * buttons (equal width, two per row), discovered peers with Connect, the
 * current neighbor list, and — in debug builds only — a Debug card with the
 * fixed test-location toggle. Everything scrolls; the screen itself is fixed.
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
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
    // AirMesh-tagged peers from the DNS-SD directory; listed first in the manual section.
    airMeshPeers: List<AirMeshPeer> = emptyList(),
    // Debug builds only: a clearly-labeled toggle that shares a fixed point.
    showTestLocationToggle: Boolean = false,
    useTestLocation: Boolean = false,
    onSetUseTestLocation: (Boolean) -> Unit = {},
    // Per-message notifications while the app is in the background; persisted by the service.
    messageNotificationsEnabled: Boolean = true,
    onSetMessageNotificationsEnabled: (Boolean) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThemeSelector(selected = themeMode, onSelect = onSetThemeMode)

        if (state.localNetworkRequired && !state.localNetworkGranted) {
            LocalNetworkBanner(onGrant = onRequestPermissions)
        }

        StatusCard(nodeId, state)

        // The common path is automatic; only the always-useful actions stay out.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onRequestPermissions, modifier = ActionButtonModifier) {
                Text("Permissions", maxLines = 1)
            }
            OutlinedButton(onClick = onDisconnect, modifier = ActionButtonModifier) {
                Text("Disconnect", maxLines = 1)
            }
            TextButton(onClick = onOpenPrototype, modifier = ActionButtonModifier) {
                Text("Prototype", maxLines = 1)
            }
        }

        AdvancedManualCard(
            state = state,
            airMeshPeers = airMeshPeers,
            onDiscover = onDiscover,
            onCreateGroup = onCreateGroup,
            onRetryTcp = onRetryTcp,
            onConnectPeer = onConnectPeer,
        )

        NeighborsSection(neighbors)

        MessageNotificationsCard(
            enabled = messageNotificationsEnabled,
            onSetEnabled = onSetMessageNotificationsEnabled,
        )

        if (showTestLocationToggle) {
            // DEBUG builds only: GPS may never fix indoors, so offer a fixed point.
            DebugCard(useTestLocation = useTestLocation, onSetUseTestLocation = onSetUseTestLocation)
        }
    }
}

/** Shared size for the action buttons: equal width (two per row) and a 48dp touch target. */
private val ActionButtonModifier = Modifier
    .fillMaxWidth(0.45f)
    .heightIn(min = MinTouch)

/**
 * Collapsible card with the manual Wi-Fi Direct controls (Discover, Create group,
 * Retry TCP) and the raw peer list. Auto-mesh covers the common path, so this
 * starts collapsed; previews pass initiallyExpanded = true to show its content.
 * Internal (not private) so the design-time previews can render it expanded.
 */
@Composable
internal fun AdvancedManualCard(
    state: ConnectorState,
    airMeshPeers: List<AirMeshPeer>,
    onDiscover: () -> Unit,
    onCreateGroup: () -> Unit,
    onRetryTcp: () -> Unit,
    onConnectPeer: (WifiP2pDevice) -> Unit,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouch)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced / manual", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                // No icon dependency in this project; a text chevron is enough.
                Text(if (expanded) "▴" else "▾")
            }
            if (expanded) {
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onDiscover, modifier = ActionButtonModifier) {
                            Text("Discover", maxLines = 1)
                        }
                        Button(onClick = onCreateGroup, modifier = ActionButtonModifier) {
                            Text("Create group", maxLines = 1)
                        }
                        // Only meaningful for a client waiting on the owner's TCP port.
                        OutlinedButton(
                            onClick = onRetryTcp,
                            enabled = state.role == P2pRole.CLIENT && state.groupFormed,
                            modifier = ActionButtonModifier,
                        ) {
                            Text("Retry TCP", maxLines = 1)
                        }
                    }
                    PeersSection(state, airMeshPeers, onConnectPeer)
                }
            }
        }
    }
}

/** System / Light / Dark selector; the change applies immediately in the activity. */
@OptIn(ExperimentalMaterial3Api::class) // Segmented buttons are experimental in this M3 version
@Composable
private fun ThemeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val modes = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
            ) {
                Text(mode.label)
            }
        }
    }
}

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
 * while a group is formed or a connect request is in flight. Peers that also
 * advertise via DNS-SD are AirMesh phones and are listed first, tagged with
 * their node id (and "group owner" when they own a group). */
@Composable
private fun PeersSection(state: ConnectorState, airMeshPeers: List<AirMeshPeer>, onConnectPeer: (WifiP2pDevice) -> Unit) {
    Text("Discovered peers (${state.peers.size})", style = MaterialTheme.typography.titleSmall)
    if (state.peers.isEmpty()) {
        Text(
            "No peers yet — auto-mesh is searching, or tap Discover.",
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
    // AirMesh-tagged peers first: they are the phones we can actually mesh with.
    val tagged = airMeshPeers.associateBy { it.deviceAddress }
    val sorted = state.peers.sortedWith(
        compareByDescending<WifiP2pDevice> { tagged[it.deviceAddress] != null }.thenBy { it.deviceName },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sorted.forEach { device ->
            val peer = tagged[device.deviceAddress]
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
                    if (peer != null) {
                        // The DNS-SD ad tells us this phone runs AirMesh and its node id.
                        Text(
                            "AirMesh #${peer.nodeId}${if (peer.isGroupOwner) " · group owner" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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

/** Display label for the debug test point; must match MeshActivity's TEST_*_E6. */
private const val TEST_LOCATION_LABEL = "30.00000 N, 31.00000 E"

/** Toggle for per-message notifications while no screen is visible. */
@Composable
private fun MessageNotificationsCard(enabled: Boolean, onSetEnabled: (Boolean) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Message notifications", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Notify for received texts and locations while the app is in the background.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onSetEnabled)
        }
    }
}

/** Debug-builds-only card: shares a fixed point instead of GPS (indoor testing). */
@Composable
private fun DebugCard(useTestLocation: Boolean, onSetUseTestLocation: (Boolean) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Share a fixed test location", style = MaterialTheme.typography.bodyMedium)
                Text(
                    TEST_LOCATION_LABEL,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = useTestLocation, onCheckedChange = onSetUseTestLocation)
        }
    }
}

private fun deviceStatusLabel(status: Int): String = when (status) {
    WifiP2pDevice.AVAILABLE -> "AVAILABLE"
    WifiP2pDevice.INVITED -> "INVITED"
    WifiP2pDevice.CONNECTED -> "CONNECTED"
    else -> "UNKNOWN"
}
