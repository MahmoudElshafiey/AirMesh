package com.airmesh.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airmesh.node.LocationFix
import com.airmesh.node.LogEntry
import com.airmesh.node.MeshLocation
import com.airmesh.node.MeshMessage
import com.airmesh.routing.Router
import com.airmesh.transport.AirMeshPeer
import com.airmesh.transport.ConnectorState
import com.airmesh.transport.P2pRole

/**
 * Design-time previews: every tab in both light and dark, with the fixed
 * fallback palette (dynamicColor = false) so they render identically on any
 * machine. Data is static; nothing here touches a real node or connector.
 */

private val previewMessages = listOf(
    MeshMessage(2, "Cellular is down here — does the mesh work?", 1_700_000_000_000L, Router.DEFAULT_TTL - 1, outgoing = false),
    MeshMessage(1, "Yes. I am two relays from base camp.", 1_700_000_060_000L, Router.DEFAULT_TTL, outgoing = true, handedTo = setOf(2, 3)),
    MeshMessage(3, "Copy that. Sending the supply list next.", 1_700_000_120_000L, Router.DEFAULT_TTL - 2, outgoing = false),
)

private val previewLocations = listOf(
    MeshLocation(3, 30_044_420, 31_235_760, 12, 1_700_000_090_000L, Router.DEFAULT_TTL - 1),
)

// Timestamped "now" so the card shows a distance to my own fix in previews.
private val previewMyFix = LocationFix(30_044_000, 31_235_000, 8, timeMs = System.currentTimeMillis())

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

// Same MAC as [previewPeer], so the manual peer list shows it tagged "AirMesh #2".
private val previewAirMeshPeers = listOf(
    AirMeshPeer("AA:BB:CC:DD:EE:FF", nodeId = 2, isGroupOwner = true, tieBreakToken = 0x1234),
)

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

/** All chat previews share a 360dp-wide phone canvas (the reported device class). */
private const val PREVIEW_WIDTH_DP = 360
private const val PREVIEW_HEIGHT_DP = 720

@Composable
private fun ChatTabPreviewContent(emptyList: Boolean, testLocationOn: Boolean = false) {
    MeshChatTab(
        nodeId = 1,
        messages = if (emptyList) emptyList() else previewMessages,
        locations = if (emptyList) emptyList() else previewLocations,
        myLastFix = previewMyFix,
        shareStatus = LocationShareStatus.IDLE,
        onSendText = {},
        onShareLocation = {},
        useTestLocation = testLocationOn,
    )
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chat · mixed · light")
@Composable
private fun ChatTabMixedLightPreview() = ThemedTabPreview(darkTheme = false) {
    ChatTabPreviewContent(emptyList = false)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chat · mixed · dark")
@Composable
private fun ChatTabMixedDarkPreview() = ThemedTabPreview(darkTheme = true) {
    ChatTabPreviewContent(emptyList = false)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chat · empty · light")
@Composable
private fun ChatTabEmptyLightPreview() = ThemedTabPreview(darkTheme = false) {
    ChatTabPreviewContent(emptyList = true)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chat · empty · dark")
@Composable
private fun ChatTabEmptyDarkPreview() = ThemedTabPreview(darkTheme = true) {
    ChatTabPreviewContent(emptyList = true)
}

// fontScale 1.5: the hint must stay on one line even with large text.
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, fontScale = 1.5f, name = "Chat · mixed · light · large text")
@Composable
private fun ChatTabMixedLightLargeTextPreview() = ThemedTabPreview(darkTheme = false) {
    ChatTabPreviewContent(emptyList = false)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, fontScale = 1.5f, name = "Chat · mixed · dark · large text")
@Composable
private fun ChatTabMixedDarkLargeTextPreview() = ThemedTabPreview(darkTheme = true) {
    ChatTabPreviewContent(emptyList = false)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, fontScale = 1.5f, name = "Chat · empty · light · large text")
@Composable
private fun ChatTabEmptyLightLargeTextPreview() = ThemedTabPreview(darkTheme = false) {
    ChatTabPreviewContent(emptyList = true)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, fontScale = 1.5f, name = "Chat · empty · dark · large text")
@Composable
private fun ChatTabEmptyDarkLargeTextPreview() = ThemedTabPreview(darkTheme = true) {
    ChatTabPreviewContent(emptyList = true)
}

// Debug builds only: the armed test point shows a chip next to the pin icon.
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chat · test-location chip")
@Composable
private fun ChatTabTestLocationChipPreview() = ThemedTabPreview(darkTheme = true) {
    ChatTabPreviewContent(emptyList = false, testLocationOn = true)
}

/** A received card with a distance to my own fix, and one of my own cards. */
@Composable
private fun LocationCardsContent() {
    Column(modifier = Modifier.padding(16.dp)) {
        LocationCard(location = previewLocations.first(), isMine = false, distanceMeters = 87.0)
        Spacer(modifier = Modifier.height(8.dp))
        LocationCard(
            location = MeshLocation(1, 30_044_000, 31_235_000, 8, 1_700_000_150_000L, Router.DEFAULT_TTL),
            isMine = true,
        )
    }
}

@Preview(name = "Location card · light")
@Composable
private fun LocationCardLightPreview() = ThemedTabPreview(darkTheme = false) {
    LocationCardsContent()
}

@Preview(name = "Location card · dark")
@Composable
private fun LocationCardDarkPreview() = ThemedTabPreview(darkTheme = true) {
    LocationCardsContent()
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
        airMeshPeers = previewAirMeshPeers,
        // Debug card: off here, armed in the dark preview.
        showTestLocationToggle = true,
        useTestLocation = false,
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
        showTestLocationToggle = true,
        useTestLocation = true,
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

/** The manual card expanded: Discover / Create group / Retry TCP + tagged peer list. */
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 320, name = "Network · advanced expanded")
@Composable
private fun NetworkTabAdvancedExpandedPreview() = ThemedTabPreview(darkTheme = false) {
    Column(modifier = Modifier.padding(16.dp)) {
        AdvancedManualCard(
            state = previewState,
            airMeshPeers = previewAirMeshPeers,
            onDiscover = {},
            onCreateGroup = {},
            onRetryTcp = {},
            onConnectPeer = {},
            initiallyExpanded = true,
        )
    }
}

/** Every banner text the auto-mesh controller can produce, in order of use. */
private val bannerPreviewTexts = listOf(
    "Searching for nearby AirMesh phones…",
    "Found 2 Wi-Fi Direct devices but none advertises AirMesh. Open Advanced to connect manually.",
    "Creating a group — waiting for another phone to join",
    "Connecting to #7…",
    "Waiting for the other phone to accept the connection request",
    "Connected · 2 neighbors",
    "Retrying in 5s — connect failed (other)",
    "Wi-Fi Direct permission not granted",
)

@Composable
private fun BannerPreviewContent() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bannerPreviewTexts.forEach { AutoMeshBanner(it) }
    }
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 420, name = "Auto-mesh banners · light")
@Composable
private fun BannerLightPreview() = ThemedTabPreview(darkTheme = false) {
    BannerPreviewContent()
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 420, name = "Auto-mesh banners · dark")
@Composable
private fun BannerDarkPreview() = ThemedTabPreview(darkTheme = true) {
    BannerPreviewContent()
}

/** The full header: a banner plus the Auto connect switch. */
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 200, name = "Auto connect · header")
@Composable
private fun AutoMeshHeaderPreview() = ThemedTabPreview(darkTheme = false) {
    Column(modifier = Modifier.padding(16.dp)) {
        AutoMeshHeader(bannerText = "Searching for nearby AirMesh phones…", enabled = true, onSetEnabled = {})
    }
}
