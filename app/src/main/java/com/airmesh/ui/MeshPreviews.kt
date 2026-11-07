package com.airmesh.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.airmesh.node.LogEntry
import com.airmesh.node.MeshMessage
import com.airmesh.routing.Router
import com.airmesh.transport.ConnectorState
import com.airmesh.transport.P2pRole

/**
 * Design-time previews: every tab in both light and dark, with the fixed
 * fallback palette (dynamicColor = false) so they render identically on any
 * machine. Data is static; nothing here touches a real node or connector.
 */

private val previewMessages = listOf(
    MeshMessage(2, "Cellular is down here — does the mesh work?", 1_700_000_000_000L, Router.DEFAULT_TTL - 1, outgoing = false),
    MeshMessage(1, "Yes. I am two relays from base camp.", 1_700_000_060_000L, Router.DEFAULT_TTL, outgoing = true),
    MeshMessage(3, "Copy that. Sending the supply list next.", 1_700_000_120_000L, Router.DEFAULT_TTL - 2, outgoing = false),
)

private val previewPeer = WifiP2pDevice().apply {
    deviceName = "Pixel 8"
    deviceAddress = "AA:BB:CC:DD:EE:FF"
    status = WifiP2pDevice.AVAILABLE
}

private val previewState = ConnectorState(
    permissionsGranted = true,
    p2pEnabled = true,
    peers = listOf(previewPeer),
    role = P2pRole.GROUP_OWNER,
    groupFormed = true,
    groupOwnerAddress = "192.168.46.1",
)

private val previewNeighbors = setOf(2, 3)

private val previewEvents = listOf(
    LogEntry(1_700_000_000_000L, "P2P: group formed, role=GROUP_OWNER"),
    LogEntry(1_700_000_010_000L, "TX #1 -> all (ttl=5): \"does the mesh work?\""),
    LogEntry(1_700_000_020_000L, "RX #2 from 3: delivered"),
    LogEntry(1_700_000_030_000L, "TCP: listening on port 4780"),
    LogEntry(1_700_000_040_000L, "TCP: connect to owner failed — retrying in 2s"),
)

/** Shared wrapper: fixed palette + a full-screen background like the real screen. */
@Composable
private fun ThemedTabPreview(darkTheme: Boolean, content: @Composable () -> Unit) {
    AirMeshTheme(darkTheme = darkTheme, dynamicColor = false) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            content()
        }
    }
}

@Preview(name = "Chat · light")
@Composable
private fun ChatTabLightPreview() = ThemedTabPreview(darkTheme = false) {
    MeshChatTab(nodeId = 1, messages = previewMessages, onSendText = {})
}

@Preview(name = "Chat · dark")
@Composable
private fun ChatTabDarkPreview() = ThemedTabPreview(darkTheme = true) {
    MeshChatTab(nodeId = 1, messages = previewMessages, onSendText = {})
}

@Preview(name = "Network · light")
@Composable
private fun NetworkTabLightPreview() = ThemedTabPreview(darkTheme = false) {
    MeshNetworkTab(
        nodeId = 1,
        state = previewState,
        neighbors = previewNeighbors,
        onRequestPermissions = {},
        onDiscover = {},
        onCreateGroup = {},
        onRetryTcp = {},
        onDisconnect = {},
        onConnectPeer = {},
        onOpenPrototype = {},
        themeMode = ThemeMode.SYSTEM,
        onSetThemeMode = {},
    )
}

@Preview(name = "Network · dark")
@Composable
private fun NetworkTabDarkPreview() = ThemedTabPreview(darkTheme = true) {
    MeshNetworkTab(
        nodeId = 1,
        state = previewState,
        neighbors = previewNeighbors,
        onRequestPermissions = {},
        onDiscover = {},
        onCreateGroup = {},
        onRetryTcp = {},
        onDisconnect = {},
        onConnectPeer = {},
        onOpenPrototype = {},
        themeMode = ThemeMode.DARK,
        onSetThemeMode = {},
    )
}

@Preview(name = "Log · light")
@Composable
private fun LogTabLightPreview() = ThemedTabPreview(darkTheme = false) {
    MeshLogTab(events = previewEvents, onClearLog = {})
}

@Preview(name = "Log · dark")
@Composable
private fun LogTabDarkPreview() = ThemedTabPreview(darkTheme = true) {
    MeshLogTab(events = previewEvents, onClearLog = {})
}
