package com.airmesh.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.airmesh.node.LocationFix
import com.airmesh.node.LogEntry
import com.airmesh.node.MeshLocation
import com.airmesh.node.MeshMessage
import com.airmesh.transport.ConnectorState
import com.airmesh.transport.P2pRole

/** Bottom tabs. The selection is stored as an ordinal so it survives rotation. */
enum class MeshTab(val label: String) { CHAT("Chat"), NETWORK("Network"), LOG("Log") }

private val MeshTab.icon: ImageVector
    get() = when (this) {
        MeshTab.CHAT -> MeshChatIcon
        MeshTab.NETWORK -> MeshNetworkIcon
        MeshTab.LOG -> MeshLogIcon
    }

/**
 * Main AirMesh screen. A Scaffold provides edge-to-edge insets for the system
 * bars (the top bar sits below the status bar, content above the navigation
 * bar), a compact top bar with title + status chip, and three bottom tabs:
 * chat, network controls, and the event log. imePadding keeps the keyboard
 * from covering the compose bar.
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar is experimental in this M3 version
@Composable
fun MeshScreen(
    nodeId: Int,
    connectorState: ConnectorState,
    neighbors: Set<Int>,
    messages: List<MeshMessage>,
    locations: List<MeshLocation>,
    myLastFix: LocationFix?,
    shareStatus: LocationShareStatus,
    events: List<LogEntry>,
    onRequestPermissions: () -> Unit,
    onDiscover: () -> Unit,
    onCreateGroup: () -> Unit,
    onRetryTcp: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectPeer: (WifiP2pDevice) -> Unit,
    onSendText: (String) -> Unit,
    onShareLocation: () -> Unit,
    // Debug builds only: the fixed test-location toggle lives in the Network
    // tab; while armed, a "TEST LOCATION" chip sits next to the share icon.
    showTestLocationToggle: Boolean = false,
    useTestLocation: Boolean = false,
    onSetUseTestLocation: (Boolean) -> Unit = {},
    onClearLog: () -> Unit,
    onOpenPrototype: () -> Unit,
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(MeshTab.CHAT.ordinal) }
    val tab = MeshTab.values()[selectedTab.coerceIn(0, MeshTab.values().size - 1)]

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AirMesh") },
                actions = { StatusChip(role = connectorState.role, neighborCount = neighbors.size) },
            )
        },
        bottomBar = {
            NavigationBar {
                MeshTab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { selectedTab = t.ordinal },
                        icon = { Icon(imageVector = t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Keep the compose bar (and any content) above the keyboard.
                .imePadding(),
        ) {
            when (tab) {
                MeshTab.CHAT ->
                    MeshChatTab(
                        nodeId = nodeId,
                        messages = messages,
                        locations = locations,
                        myLastFix = myLastFix,
                        shareStatus = shareStatus,
                        onSendText = onSendText,
                        onShareLocation = onShareLocation,
                        useTestLocation = useTestLocation,
                    )
                MeshTab.NETWORK ->
                    MeshNetworkTab(
                        nodeId = nodeId,
                        state = connectorState,
                        neighbors = neighbors,
                        onRequestPermissions = onRequestPermissions,
                        onDiscover = onDiscover,
                        onCreateGroup = onCreateGroup,
                        onRetryTcp = onRetryTcp,
                        onDisconnect = onDisconnect,
                        onConnectPeer = onConnectPeer,
                        onOpenPrototype = onOpenPrototype,
                        themeMode = themeMode,
                        onSetThemeMode = onSetThemeMode,
                        showTestLocationToggle = showTestLocationToggle,
                        useTestLocation = useTestLocation,
                        onSetUseTestLocation = onSetUseTestLocation,
                    )
                MeshTab.LOG -> MeshLogTab(events = events, onClearLog = onClearLog)
            }
        }
    }
}

/** Compact top-bar chip: colored dot plus words, so state is readable without color. */
@Composable
private fun StatusChip(role: P2pRole, neighborCount: Int) {
    val colors = MaterialTheme.colorScheme
    val dotColor = when (meshStatus(role, neighborCount)) {
        MeshStatus.CONNECTED -> colors.primary
        MeshStatus.GROUP_NO_NEIGHBORS -> colors.tertiary
        MeshStatus.NO_GROUP -> colors.outline
    }
    Box(
        modifier = Modifier
            .padding(end = 16.dp)
            .background(colors.surfaceVariant, CircleShape),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
            Spacer(modifier = Modifier.width(6.dp))
            Text(statusChipText(role, neighborCount), style = MaterialTheme.typography.labelMedium)
        }
    }
}

