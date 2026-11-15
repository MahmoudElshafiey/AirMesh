package com.airmesh.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airmesh.chat.ChatDirection
import com.airmesh.chat.ChatKind
import com.airmesh.chat.Contact
import com.airmesh.chat.ConversationSummary
import com.airmesh.chat.MESH_CONVERSATION_ID
import com.airmesh.chat.NodePresence
import com.airmesh.chat.PresenceState
import com.airmesh.node.LocationFix
import com.airmesh.node.LogEntry
import com.airmesh.node.MeshLocation
import com.airmesh.node.MeshMessage
import com.airmesh.node.MyProfile
import com.airmesh.profile.ProfileInfo
import com.airmesh.routing.Router
import com.airmesh.transport.AirMeshPeer
import com.airmesh.transport.AutoMeshState
import com.airmesh.transport.ConnectorState
import com.airmesh.transport.P2pRole
import com.airmesh.ui.components.drawRadar
import com.airmesh.ui.theme.AirMeshTheme

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

/**
 * Display names for the preview senders. Node 3 carries exactly 24 characters
 * (the payload cap), node 4 is an emoji name, and node 5 is deliberately absent
 * so previews show the "User #5" fallback.
 */
private val previewProfiles = mapOf(
    2 to ProfileInfo("Mira", 1, 0L),
    3 to ProfileInfo("Abdelrahman Mohamed Adel", 4, 0L),
    4 to ProfileInfo("🚑 Rescue", 5, 0L),
)

/** One bubble per name shape: short, emoji, unknown, and the 24-character cap. */
private val previewProfileMessages = listOf(
    MeshMessage(2, "Short name.", 1_700_000_000_000L, Router.DEFAULT_TTL - 1, outgoing = false),
    MeshMessage(4, "Emoji name.", 1_700_000_030_000L, Router.DEFAULT_TTL, outgoing = false),
    MeshMessage(5, "Unknown sender, so the id is the label.", 1_700_000_060_000L, Router.DEFAULT_TTL - 2, outgoing = false),
    MeshMessage(3, "The 24-character name.", 1_700_000_090_000L, Router.DEFAULT_TTL - 1, outgoing = false),
    MeshMessage(1, "My own bubble keeps the old layout.", 1_700_000_120_000L, Router.DEFAULT_TTL, outgoing = true, handedTo = setOf(2, 3)),
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

// Distinct seqs: the log tab uses them as stable list keys (TASK U4 part 4).
private val previewEvents = listOf(
    LogEntry(1_700_000_000_000L, "P2P: group formed, role=GROUP_OWNER", seq = 1),
    LogEntry(1_700_000_010_000L, "TX #1 -> all (ttl=5): \"does the mesh work?\"", seq = 2),
    LogEntry(1_700_000_020_000L, "RX #2 from 3: delivered", seq = 3),
    LogEntry(1_700_000_030_000L, "TCP: listening on port 4780", seq = 4),
    LogEntry(1_700_000_040_000L, "TCP: connect to owner failed — retrying in 2s", seq = 5),
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
private fun ChatTabPreviewContent(
    emptyList: Boolean,
    testLocationOn: Boolean = false,
    namedSenders: Boolean = false,
) {
    MeshChatTab(
        nodeId = 1,
        messages = when {
            emptyList -> emptyList()
            namedSenders -> previewProfileMessages
            else -> previewMessages
        },
        locations = if (emptyList) emptyList() else previewLocations,
        myLastFix = previewMyFix,
        shareStatus = LocationShareStatus.IDLE,
        onSendText = {},
        onShareLocation = {},
        useTestLocation = testLocationOn,
        profiles = previewProfiles,
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

// Sender identity: short name, 24-character name, emoji name, and an unknown
// sender that falls back to "User #id".
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chat · sender names · light")
@Composable
private fun ChatTabSenderNamesLightPreview() = ThemedTabPreview(darkTheme = false) {
    ChatTabPreviewContent(emptyList = false, namedSenders = true)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chat · sender names · dark")
@Composable
private fun ChatTabSenderNamesDarkPreview() = ThemedTabPreview(darkTheme = true) {
    ChatTabPreviewContent(emptyList = false, namedSenders = true)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, fontScale = 1.5f, name = "Chat · sender names · light · large text")
@Composable
private fun ChatTabSenderNamesLightLargeTextPreview() = ThemedTabPreview(darkTheme = false) {
    ChatTabPreviewContent(emptyList = false, namedSenders = true)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, fontScale = 1.5f, name = "Chat · sender names · dark · large text")
@Composable
private fun ChatTabSenderNamesDarkLargeTextPreview() = ThemedTabPreview(darkTheme = true) {
    ChatTabPreviewContent(emptyList = false, namedSenders = true)
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
        LocationCard(
            location = previewLocations.first(),
            isMine = false,
            distanceMeters = 87.0,
            senderName = displayName(3, previewProfiles),
            senderColorIndex = avatarColorIndex(3, previewProfiles),
        )
        Spacer(modifier = Modifier.height(8.dp))
        LocationCard(
            location = MeshLocation(1, 30_044_000, 31_235_000, 8, 1_700_000_150_000L, Router.DEFAULT_TTL),
            isMine = true,
            senderName = displayName(1, previewProfiles),
            senderColorIndex = avatarColorIndex(1, previewProfiles),
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
        discoveryStrategy = 3,
        rawCallbackCount = 47,
        profiles = previewProfiles,
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
        discoveryStrategy = 3,
        rawCallbackCount = 47,
        profiles = previewProfiles,
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

/**
 * The banners home can actually show (TASK U4 part 2), built with the real pure mapping so the
 * previews never drift from device behavior: a merge in progress, a retry after an error, and —
 * only when conversations exist — the missing-permission line. Searching/connecting states get
 * no banner; they are explained by the empty state instead.
 */
private val previewHomeBanners = listOfNotNull(
    homeBannerText(AutoMeshState.Yielding("192.168.49.1", 7, 3, 0L), true, 2, null, 0L, false),
    homeBannerText(AutoMeshState.Backoff(untilMs = 5_000L, reason = "connect failed (other)"), true, 0, null, 0L, false),
    // emptyStateVisible = false: with conversations on screen the banner owns this message.
    homeBannerText(AutoMeshState.Idle, permissionOk = false, neighborCount = 0, idleReason = null, nowMs = 0L, emptyStateVisible = false),
)

@Composable
private fun BannerPreviewContent() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        previewHomeBanners.forEach { AutoMeshBanner(it) }
    }
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 420, name = "Home banners · light")
@Composable
private fun BannerLightPreview() = ThemedTabPreview(darkTheme = false) {
    BannerPreviewContent()
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 420, name = "Home banners · dark")
@Composable
private fun BannerDarkPreview() = ThemedTabPreview(darkTheme = true) {
    BannerPreviewContent()
}

/** The single banner above the empty state while a merge is in progress (TASK U4 part 2). */
private val previewMergingState = AutoMeshState.Yielding(
    targetAddress = "192.168.49.1", targetNodeId = 7, targetPhones = 3, sinceMs = 0L,
)

@Composable
private fun HomeBannerOverEmptyStateContent() {
    Column(modifier = Modifier.fillMaxSize()) {
        homeBannerText(previewMergingState, true, 2, null, 0L, false)?.let {
            AutoMeshBanner(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ChatsEmptyState(
                state = homeEmptyState(previewMergingState, otherDevicesFound = 0, searchingForMs = 0L, permissionOk = true),
                modifier = Modifier.padding(horizontal = 32.dp).widthIn(max = 320.dp),
            )
        }
    }
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Home · merging banner · light")
@Composable
private fun HomeMergingBannerLightPreview() = ThemedTabPreview(darkTheme = false) {
    HomeBannerOverEmptyStateContent()
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Home · merging banner · dark")
@Composable
private fun HomeMergingBannerDarkPreview() = ThemedTabPreview(darkTheme = true) {
    HomeBannerOverEmptyStateContent()
}

/** The Auto connect switch in its new home: the Network tab (TASK U4 part 2). */
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 200, name = "Auto connect · network card")
@Composable
private fun AutoConnectCardPreview() = ThemedTabPreview(darkTheme = false) {
    Column(modifier = Modifier.padding(16.dp)) {
        AutoConnectCard(enabled = true, onSetEnabled = {})
    }
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 200, name = "Auto connect · network card · dark")
@Composable
private fun AutoConnectCardDarkPreview() = ThemedTabPreview(darkTheme = true) {
    Column(modifier = Modifier.padding(16.dp)) {
        AutoConnectCard(enabled = false, onSetEnabled = {})
    }
}

// --- Profile onboarding and sheet -------------------------------------------
// Names used here on purpose: a 24-character name and an emoji name are the two
// layouts most likely to break at fontScale 1.5.

private const val PREVIEW_LONG_NAME = "Abdelrahman Mohamed Adel"
private const val PREVIEW_EMOJI_NAME = "🚑 Rescue"

@Composable
private fun OnboardingPreviewContent(initialName: String, initialColorIndex: Int = 0) {
    OnboardingDialog(
        nodeId = 1,
        initialName = initialName,
        initialColorIndex = initialColorIndex,
        onSave = { _, _ -> },
        onSkip = {},
    )
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Onboarding · empty · light")
@Composable
private fun OnboardingEmptyLightPreview() = ThemedTabPreview(darkTheme = false) {
    OnboardingPreviewContent(initialName = "")
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Onboarding · long name · dark")
@Composable
private fun OnboardingLongDarkPreview() = ThemedTabPreview(darkTheme = true) {
    OnboardingPreviewContent(initialName = PREVIEW_LONG_NAME, initialColorIndex = 3)
}

@Preview(
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    fontScale = 1.5f,
    name = "Onboarding · emoji · light · large text",
)
@Composable
private fun OnboardingEmojiLargeTextPreview() = ThemedTabPreview(darkTheme = false) {
    OnboardingPreviewContent(initialName = PREVIEW_EMOJI_NAME, initialColorIndex = 4)
}

@Composable
private fun ProfileSheetPreviewContent(initialName: String, initialColorIndex: Int) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ProfileSheetContent(
            nodeId = 7,
            initialName = initialName,
            initialColorIndex = initialColorIndex,
            onSave = { _, _ -> },
            onCancel = {},
        )
    }
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 520, name = "Profile sheet · light")
@Composable
private fun ProfileSheetLightPreview() = ThemedTabPreview(darkTheme = false) {
    ProfileSheetPreviewContent(initialName = "Mira", initialColorIndex = 1)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 520, name = "Profile sheet · dark")
@Composable
private fun ProfileSheetDarkPreview() = ThemedTabPreview(darkTheme = true) {
    ProfileSheetPreviewContent(initialName = PREVIEW_LONG_NAME, initialColorIndex = 5)
}

@Preview(
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = 640,
    fontScale = 1.5f,
    name = "Profile sheet · emoji · large text",
)
@Composable
private fun ProfileSheetEmojiLargeTextPreview() = ThemedTabPreview(darkTheme = false) {
    ProfileSheetPreviewContent(initialName = PREVIEW_EMOJI_NAME, initialColorIndex = 2)
}

// --- Chats home (TASK U1 part 3) --------------------------------------------
// A fixed "now" keeps the list times deterministic in design mode.

private val previewNowMs = 1_700_002_000_000L

/** Room row with a location message, plus two direct conversations (today + yesterday). */
private val previewHomeSummaries = listOf(
    ConversationSummary(MESH_CONVERSATION_ID, previewNowMs - 30_000L, ChatDirection.IN, ChatKind.LOCATION, null, 2),
    ConversationSummary(2, previewNowMs - 60_000L, ChatDirection.IN, ChatKind.TEXT, "Copy that. Sending the supply list next.", 3),
    ConversationSummary(3, previewNowMs - 86_400_000L, ChatDirection.OUT, ChatKind.TEXT, "On my way to the checkpoint.", 0),
)

/** Short name (has a chat), 24-character name (has a chat), emoji (no chat yet), anonymous. */
private val previewHomeContacts = listOf(
    Contact(2, "Mira", 1),
    Contact(3, PREVIEW_LONG_NAME, 4),
    Contact(4, PREVIEW_EMOJI_NAME, 5),
    Contact(7, null, 0), // anonymous: no chat and no presence, so the home list skips it
)

private val previewHomePresence = mapOf(
    2 to NodePresence(PresenceState.ONLINE, true),
    3 to NodePresence(PresenceState.RECENT, false),
    4 to NodePresence(PresenceState.ONLINE, true),
)

/** Thirty named contacts for the long-scroll state; presence alternates so both dots show. */
private val previewManyContacts = (1..30).map { i -> Contact(i, "Rescuer $i", i % AVATAR_COLOR_COUNT) }

private val previewManyPresence: Map<Int, NodePresence> = (1..30).associate { i ->
    i to NodePresence(if (i % 2 == 0) PresenceState.RECENT else PresenceState.ONLINE, i % 3 == 0)
}

@Composable
private fun ChatsHomePreviewContent(
    summaries: List<ConversationSummary>,
    contacts: List<Contact>,
    presence: Map<Int, NodePresence>,
) {
    ChatsHomeContent(
        summaries = summaries,
        contacts = contacts,
        presence = presence,
        nowMs = previewNowMs,
        onOpenConversation = {},
    )
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chats home · mixed · light")
@Composable
private fun ChatsHomeMixedLightPreview() = ThemedTabPreview(darkTheme = false) {
    ChatsHomePreviewContent(previewHomeSummaries, previewHomeContacts, previewHomePresence)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chats home · mixed · dark")
@Composable
private fun ChatsHomeMixedDarkPreview() = ThemedTabPreview(darkTheme = true) {
    ChatsHomePreviewContent(previewHomeSummaries, previewHomeContacts, previewHomePresence)
}

// fontScale 1.5: long and emoji names must stay on one line in every row.
@Preview(
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    fontScale = 1.5f,
    name = "Chats home · mixed · light · large text",
)
@Composable
private fun ChatsHomeMixedLightLargeTextPreview() = ThemedTabPreview(darkTheme = false) {
    ChatsHomePreviewContent(previewHomeSummaries, previewHomeContacts, previewHomePresence)
}

@Preview(
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    fontScale = 1.5f,
    name = "Chats home · mixed · dark · large text",
)
@Composable
private fun ChatsHomeMixedDarkLargeTextPreview() = ThemedTabPreview(darkTheme = true) {
    ChatsHomePreviewContent(previewHomeSummaries, previewHomeContacts, previewHomePresence)
}

// First-run state: only the pinned room and the animated "looking" illustration.
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chats home · empty · light")
@Composable
private fun ChatsHomeEmptyLightPreview() = ThemedTabPreview(darkTheme = false) {
    ChatsHomePreviewContent(emptyList(), emptyList(), emptyMap())
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chats home · empty · dark")
@Composable
private fun ChatsHomeEmptyDarkPreview() = ThemedTabPreview(darkTheme = true) {
    ChatsHomePreviewContent(emptyList(), emptyList(), emptyMap())
}

// One contact: the smallest non-empty list (room + a single "People" row).
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chats home · one contact · light")
@Composable
private fun ChatsHomeOneContactLightPreview() = ThemedTabPreview(darkTheme = false) {
    ChatsHomePreviewContent(
        summaries = listOf(previewHomeSummaries.first()),
        contacts = listOf(Contact(2, "Mira", 1)),
        presence = mapOf(2 to NodePresence(PresenceState.ONLINE, true)),
    )
}

// Thirty contacts: the long-scroll state with both presence dots.
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Chats home · 30 contacts · dark")
@Composable
private fun ChatsHomeManyContactsDarkPreview() = ThemedTabPreview(darkTheme = true) {
    ChatsHomePreviewContent(
        summaries = listOf(previewHomeSummaries.first()),
        contacts = previewManyContacts,
        presence = previewManyPresence,
    )
}

// --- Contact picker and About (TASK U1 part 3) -------------------------------

@Composable
private fun ContactPickerPreviewContent(contacts: List<Contact>, presence: Map<Int, NodePresence>) {
    ContactPickerSheet(
        contacts = contacts,
        presence = presence,
        nowMs = previewNowMs,
        onOpenConversation = {},
        onDismiss = {},
    )
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 480, name = "Contact picker · light")
@Composable
private fun ContactPickerLightPreview() = ThemedTabPreview(darkTheme = false) {
    ContactPickerPreviewContent(previewHomeContacts, previewHomePresence)
}

// Thirty contacts: the sheet's list must scroll inside its bounded height.
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 560, name = "Contact picker · 30 contacts · dark")
@Composable
private fun ContactPickerManyDarkPreview() = ThemedTabPreview(darkTheme = true) {
    ContactPickerPreviewContent(previewManyContacts, previewManyPresence)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 320, name = "Contact picker · empty")
@Composable
private fun ContactPickerEmptyPreview() = ThemedTabPreview(darkTheme = false) {
    ContactPickerPreviewContent(emptyList(), emptyMap())
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 420, name = "About · light")
@Composable
private fun AboutLightPreview() = ThemedTabPreview(darkTheme = false) {
    AboutDialog(onDismiss = {})
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 420, name = "About · dark")
@Composable
private fun AboutDarkPreview() = ThemedTabPreview(darkTheme = true) {
    AboutDialog(onDismiss = {})
}

// --- Profile screen (TASK U1 part 4) -----------------------------------------

@Composable
private fun ProfileScreenPreviewContent(initialName: String, initialColorIndex: Int) {
    ProfileScreen(
        nodeId = 7,
        myProfile = MyProfile(name = initialName.ifEmpty { null }, colorIndex = initialColorIndex),
        onSave = { _, _ -> },
        onBack = {},
    )
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Profile screen · light")
@Composable
private fun ProfileScreenLightPreview() = ThemedTabPreview(darkTheme = false) {
    ProfileScreenPreviewContent(initialName = "Mira", initialColorIndex = 1)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP, name = "Profile screen · dark")
@Composable
private fun ProfileScreenDarkPreview() = ThemedTabPreview(darkTheme = true) {
    ProfileScreenPreviewContent(initialName = PREVIEW_LONG_NAME, initialColorIndex = 5)
}

@Preview(
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    fontScale = 1.5f,
    name = "Profile screen · emoji · large text",
)
@Composable
private fun ProfileScreenEmojiLargeTextPreview() = ThemedTabPreview(darkTheme = false) {
    ProfileScreenPreviewContent(initialName = PREVIEW_EMOJI_NAME, initialColorIndex = 2)
}

// --- Chats empty state + radar indicator (TASK U3 part 3) ---------------------
// The canvas is deliberately shorter than the phone height: a cramped free area is the worst
// case for text wrapping and FAB clearance, which is what these previews verify.
private const val EMPTY_STATE_PREVIEW_HEIGHT_DP = 480

// TASK U4 part 1 scenarios, built with the real pure mapping so previews show exactly what
// homeEmptyState(...) produces on device. NO_AIRMESH_NEARBY is unreachable from it by design:
// "a classic device was found" now only changes the caption while the radar keeps animating.
private val previewSearchingNoDevices = homeEmptyState(
    status = AutoMeshState.Searching(sinceMs = 0L),
    otherDevicesFound = 0,
    searchingForMs = 5_000L,
    permissionOk = true,
)

private val previewSearchingOtherDevice = homeEmptyState(
    status = AutoMeshState.Searching(sinceMs = 0L),
    otherDevicesFound = 1,
    searchingForMs = 5_000L,
    permissionOk = true,
)

private val previewSearchingLongWait = homeEmptyState(
    status = AutoMeshState.Searching(sinceMs = 0L),
    otherDevicesFound = 2,
    searchingForMs = HOME_MANUAL_BUTTON_AFTER_MS, // exactly at the boundary: button appears
    permissionOk = true,
)

private val previewConnecting = homeEmptyState(
    status = AutoMeshState.Joining(targetAddress = "192.168.49.1", targetNodeId = 42, sinceMs = 0L),
    otherDevicesFound = 0,
    searchingForMs = 0L,
    permissionOk = true,
)

private val previewPermissionMissing = homeEmptyState(
    status = AutoMeshState.Searching(sinceMs = 0L),
    otherDevicesFound = 0,
    searchingForMs = 5_000L,
    permissionOk = false,
)

private val previewIdleBackoff = homeEmptyState(
    status = AutoMeshState.Backoff(untilMs = Long.MAX_VALUE / 2, reason = "join failed"),
    otherDevicesFound = 0,
    searchingForMs = 0L,
    permissionOk = true,
)

private val previewConnectedNoContacts = homeEmptyState(
    status = AutoMeshState.Owner,
    otherDevicesFound = 0,
    searchingForMs = 0L,
    permissionOk = true,
)

/** The empty-state block exactly as ChatsHomeContent lays it out (centered + FAB clearance). */
@Composable
private fun HomeEmptyStatePreviewContent(state: HomeEmptyState, withFab: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ChatsEmptyState(
                state = state,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 96.dp)
                    .widthIn(max = 320.dp),
            )
        }
        if (withFab) {
            // The real "New message" FAB from MeshScreen, to verify the 96dp clearance.
            ExtendedFloatingActionButton(
                icon = { Icon(imageVector = MeshNewMessageIcon, contentDescription = null) },
                text = { Text("New message") },
                onClick = {},
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

/** One radar at a fixed phase — the same drawRadar code as the live indicator. */
@Composable
private fun RadarAtPhase(phase: Float) {
    val color = MaterialTheme.colorScheme.primary
    val glyphColor = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier = Modifier.size(120.dp)) {
        drawRadar(color, glyphColor, List(3) { ring(phase, it, 3) })
    }
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 240, name = "Empty state · radar phases 0 / 0.33 / 0.66 · light")
@Composable
private fun RadarPhasesLightPreview() = ThemedTabPreview(darkTheme = false) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadarAtPhase(0f)
        RadarAtPhase(0.33f)
        RadarAtPhase(0.66f)
    }
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 240, name = "Empty state · radar phases 0 / 0.33 / 0.66 · dark")
@Composable
private fun RadarPhasesDarkPreview() = ThemedTabPreview(darkTheme = true) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadarAtPhase(0f)
        RadarAtPhase(0.33f)
        RadarAtPhase(0.66f)
    }
}

/** The static frame drawn when the system animation scale is off (rings at 0.15 / 0.5 / 0.85). */
@Composable
private fun RadarReducedMotionFrame() {
    val color = MaterialTheme.colorScheme.primary
    val glyphColor = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier = Modifier.size(160.dp)) {
        drawRadar(color, glyphColor, REDUCED_MOTION_RING_PHASES.map(::ringAt))
    }
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 240, name = "Empty state · radar reduced-motion frame · light")
@Composable
private fun RadarReducedMotionLightPreview() = ThemedTabPreview(darkTheme = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        RadarReducedMotionFrame()
    }
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = 240, name = "Empty state · radar reduced-motion frame · dark")
@Composable
private fun RadarReducedMotionDarkPreview() = ThemedTabPreview(darkTheme = true) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        RadarReducedMotionFrame()
    }
}

// Every kind in both themes. SEARCHING/CONNECTING run the live frame clock, as on device.
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · searching · light")
@Composable
private fun EmptyStateSearchingLightPreview() = ThemedTabPreview(darkTheme = false) {
    HomeEmptyStatePreviewContent(previewSearchingNoDevices)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · searching · dark")
@Composable
private fun EmptyStateSearchingDarkPreview() = ThemedTabPreview(darkTheme = true) {
    HomeEmptyStatePreviewContent(previewSearchingNoDevices)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · connecting · light")
@Composable
private fun EmptyStateConnectingLightPreview() = ThemedTabPreview(darkTheme = false) {
    HomeEmptyStatePreviewContent(previewConnecting)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · connecting · dark")
@Composable
private fun EmptyStateConnectingDarkPreview() = ThemedTabPreview(darkTheme = true) {
    HomeEmptyStatePreviewContent(previewConnecting)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · permission missing · light")
@Composable
private fun EmptyStatePermissionMissingLightPreview() = ThemedTabPreview(darkTheme = false) {
    HomeEmptyStatePreviewContent(previewPermissionMissing)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · permission missing · dark")
@Composable
private fun EmptyStatePermissionMissingDarkPreview() = ThemedTabPreview(darkTheme = true) {
    HomeEmptyStatePreviewContent(previewPermissionMissing)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · idle backoff · light")
@Composable
private fun EmptyStateIdleBackoffLightPreview() = ThemedTabPreview(darkTheme = false) {
    HomeEmptyStatePreviewContent(previewIdleBackoff)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · idle backoff · dark")
@Composable
private fun EmptyStateIdleBackoffDarkPreview() = ThemedTabPreview(darkTheme = true) {
    HomeEmptyStatePreviewContent(previewIdleBackoff)
}

// The bug this task fixes: a classic (non-AirMesh) device is found. The radar stays animated,
// the headline never repeats the banner, and only the small caption mentions the ignored device.
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · other device found · light")
@Composable
private fun EmptyStateOtherDeviceLightPreview() = ThemedTabPreview(darkTheme = false) {
    HomeEmptyStatePreviewContent(previewSearchingOtherDevice)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · other device found · dark")
@Composable
private fun EmptyStateOtherDeviceDarkPreview() = ThemedTabPreview(darkTheme = true) {
    HomeEmptyStatePreviewContent(previewSearchingOtherDevice)
}

// 90 s of searching: the long-wait caption replaces the ignored-device one and the
// "Manual connect" escape hatch appears.
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · 90s + manual button · light")
@Composable
private fun EmptyStateLongWaitLightPreview() = ThemedTabPreview(darkTheme = false) {
    HomeEmptyStatePreviewContent(previewSearchingLongWait)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · 90s + manual button · dark")
@Composable
private fun EmptyStateLongWaitDarkPreview() = ThemedTabPreview(darkTheme = true) {
    HomeEmptyStatePreviewContent(previewSearchingLongWait)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · connected no contacts · light")
@Composable
private fun EmptyStateConnectedNoContactsLightPreview() = ThemedTabPreview(darkTheme = false) {
    HomeEmptyStatePreviewContent(previewConnectedNoContacts)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · connected no contacts · dark")
@Composable
private fun EmptyStateConnectedNoContactsDarkPreview() = ThemedTabPreview(darkTheme = true) {
    HomeEmptyStatePreviewContent(previewConnectedNoContacts)
}

// fontScale 1.5: the longest caption must wrap cleanly inside the 320dp cap.
@Preview(
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP,
    fontScale = 1.5f,
    name = "Empty state · other device found · large text",
)
@Composable
private fun EmptyStateOtherDeviceLargeTextPreview() = ThemedTabPreview(darkTheme = false) {
    HomeEmptyStatePreviewContent(previewSearchingOtherDevice)
}

@Preview(
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP,
    fontScale = 1.5f,
    name = "Empty state · connected no contacts · large text",
)
@Composable
private fun EmptyStateConnectedNoContactsLargeTextPreview() = ThemedTabPreview(darkTheme = false) {
    HomeEmptyStatePreviewContent(previewConnectedNoContacts)
}

// The real FAB over the corner: the 96dp bottom padding must keep the button clear of it.
@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · 90s + manual button + FAB · light")
@Composable
private fun EmptyStateWithFabLightPreview() = ThemedTabPreview(darkTheme = false) {
    HomeEmptyStatePreviewContent(previewSearchingLongWait, withFab = true)
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = EMPTY_STATE_PREVIEW_HEIGHT_DP, name = "Empty state · 90s + manual button + FAB · dark")
@Composable
private fun EmptyStateWithFabDarkPreview() = ThemedTabPreview(darkTheme = true) {
    HomeEmptyStatePreviewContent(previewSearchingLongWait, withFab = true)
}
