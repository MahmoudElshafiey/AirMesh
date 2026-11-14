package com.airmesh.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.airmesh.node.LocationFix
import com.airmesh.node.LogEntry
import com.airmesh.node.MeshLocation
import com.airmesh.node.MeshMessage
import com.airmesh.node.MyProfile
import com.airmesh.profile.ProfileInfo
import com.airmesh.chat.ChatMessage
import com.airmesh.chat.Contact
import com.airmesh.chat.ConversationListBuilder
import com.airmesh.chat.ConversationSummary
import com.airmesh.chat.MESH_CONVERSATION_ID
import com.airmesh.chat.NodePresence
import com.airmesh.chat.PresenceState
import com.airmesh.transport.AirMeshPeer
import com.airmesh.transport.AutoMeshState
import com.airmesh.transport.ConnectorState
import com.airmesh.transport.autoMeshBannerText
import com.airmesh.transport.noAirMeshPeersBannerText
import com.airmesh.ui.components.Avatar
import com.airmesh.ui.components.ChipTone
import com.airmesh.ui.components.StatusChip
import com.airmesh.ui.theme.AirMeshSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Bottom tabs. The selection is a navigation route now (TASK U1 part 2), so it survives rotation via the nav back stack. */
enum class MeshTab(val label: String) { CHAT("Chat"), NETWORK("Network"), LOG("Log") }

private val MeshTab.icon: ImageVector
    get() = when (this) {
        MeshTab.CHAT -> MeshChatIcon
        MeshTab.NETWORK -> MeshNetworkIcon
        MeshTab.LOG -> MeshLogIcon
    }

/** The top-level route each bottom-bar tab navigates to. */
private val MeshTab.route: String
    get() = when (this) {
        MeshTab.CHAT -> ROUTE_CHATS
        MeshTab.NETWORK -> ROUTE_NETWORK
        MeshTab.LOG -> ROUTE_LOG
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
    // A notification tap asks to switch tab and (for chat) scroll to an item.
    // The seq re-triggers the effect on repeated taps; consumption is split —
    // this screen handles keyless targets, the chat tab consumes its own key.
    navRequest: NavRequest? = null,
    onNavConsumed: () -> Unit = {},
    onRequestPermissions: () -> Unit,
    onDiscover: () -> Unit,
    onCreateGroup: () -> Unit,
    onRetryTcp: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectPeer: (WifiP2pDevice) -> Unit,
    onSendText: (String) -> Unit,
    onShareLocation: () -> Unit,
    // Share progress details for the compose bar: countdown deadline of a real
    // fix wait (null while none), its cancel action, the specific failure text
    // of a failed attempt, and whether my latest shared card used a cached fix.
    shareDeadlineMs: Long? = null,
    onCancelShare: () -> Unit = {},
    shareFailureMessage: String? = null,
    myLatestShareWasCached: Boolean = false,
    // Debug builds only: the fixed test-location toggle lives in the Network
    // tab; while armed, a "TEST LOCATION" chip sits next to the share icon. A
    // failed share additionally offers arming it from the Chat tab.
    showTestLocationToggle: Boolean = false,
    useTestLocation: Boolean = false,
    onSetUseTestLocation: (Boolean) -> Unit = {},
    onShareTestLocation: () -> Unit = {},
    onClearLog: () -> Unit,
    onOpenPrototype: () -> Unit,
    themeMode: ThemeMode,
    onSetThemeMode: (ThemeMode) -> Unit,
    // Per-message notifications while the app is in the background; toggled in
    // the Network tab and persisted by the service.
    messageNotificationsEnabled: Boolean = true,
    onSetMessageNotificationsEnabled: (Boolean) -> Unit = {},
    // Whether a group owner keeps scanning for other groups to merge with (TASK M1).
    mergeGroupsEnabled: Boolean = true,
    onSetMergeGroupsEnabled: (Boolean) -> Unit = {},
    // Auto-mesh status shown above every tab, its "Auto connect" switch, and the
    // DNS-SD peer list the Network tab's manual section sorts by.
    autoMeshState: AutoMeshState = AutoMeshState.Idle,
    autoIdleReason: String? = null,
    airMeshPeers: List<AirMeshPeer> = emptyList(),
    autoMeshEnabled: Boolean = true,
    onSetAutoMeshEnabled: (Boolean) -> Unit = {},
    // Discovery diagnostics shown as the banner's small second line.
    discoveryStrategy: Int = 1,
    rawCallbackCount: Int = 0,
    // Display identity: my own profile, the names the mesh learned, and the save
    // action. promptOnboarding is true only on the first run, until the dialog
    // has been answered (saved or skipped) once.
    myProfile: MyProfile = MyProfile(name = null, colorIndex = 0),
    profiles: Map<Int, ProfileInfo> = emptyMap(),
    // Chats home data (TASK U1 part 3): conversation rollups and known contacts from the
    // store, plus per-node presence for the dots and "Online / Last seen" lines.
    conversationSummaries: List<ConversationSummary> = emptyList(),
    contacts: List<Contact> = emptyList(),
    presence: Map<Int, NodePresence> = emptyMap(),
    onSaveProfile: (name: String, colorIndex: Int) -> Unit = { _, _ -> },
    promptOnboarding: Boolean = false,
    onOnboardingAnswered: () -> Unit = {},
    // Conversation screen data and actions (TASK U2 part 1). messagesFor is a cold-flow
    // factory so each opened conversation collects its own rows from the store.
    messagesFor: (Int) -> Flow<List<ChatMessage>> = { emptyFlow() },
    onSendDirect: (conversationId: Int, text: String) -> Unit = { _, _ -> },
    onMarkRead: (Int) -> Unit = {},
    onClearChat: (Int) -> Unit = {},
    onOpenInMaps: (latitudeE6: Int, longitudeE6: Int) -> Unit = { _, _ -> },
    // One-time "not encrypted" note; the activity persists the dismissal.
    showEncryptionNote: Boolean = false,
    onDismissEncryptionNote: () -> Unit = {},
) {
    // Navigation (TASK U1 part 2): the back stack is saved and restored by
    // navigation-compose, so the selected route survives rotation for free.
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // The contact picker opens from the chats-home FAB and "About" from its overflow menu;
    // onboarding shows once, first run only.
    var showContactPicker by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showOnboarding by rememberSaveable { mutableStateOf(promptOnboarding) }

    // The banner's "Retrying in Ns" and join-waiting texts need a live clock;
    // the state flows alone won't re-emit an unchanged state object each second.
    // Declared before the chat header below because its presence subtitle uses it too.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    // Chat route header (TASK U2 part 1): the shared top bar shows avatar + name + presence
    // subtitle on chat/{id}, and its overflow menu offers Contact info / Clear chat. The
    // dialogs are keyed by conversation id so a stale flag can never show another chat's data.
    val chatConversationId: Int? = if (currentRoute == ROUTE_CHAT) {
        backStackEntry?.arguments?.getString("conversationId")?.toIntOrNull() ?: 0
    } else null
    var contactInfoConversation by rememberSaveable { mutableStateOf<Int?>(null) }
    var clearChatTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    val chatContact = if (chatConversationId != null && chatConversationId != MESH_CONVERSATION_ID) {
        contacts.firstOrNull { it.nodeId == chatConversationId }
    } else null
    val chatTitle = chatConversationId?.let { ConversationListBuilder.titleFor(it, chatContact) }.orEmpty()
    // Room: live group size; direct chat: the peer's presence line ("Not nearby" when unknown).
    val chatSubtitle: String? = when (chatConversationId) {
        null -> null
        MESH_CONVERSATION_ID -> "${neighbors.size} phones"
        else -> presenceText(
            presence[chatConversationId] ?: NodePresence(PresenceState.UNKNOWN, false), nowMs,
        ).ifEmpty { "Not nearby" }
    }
    // Header avatar: the peer's identity color (deterministic fallback when unknown);
    // the room uses a neutral mesh icon instead. The presence dot only shows for direct
    // chats with a known state — UNKNOWN means "we have no data", not "offline".
    val chatColorIndex = if (chatConversationId != null && chatConversationId != MESH_CONVERSATION_ID) {
        (chatContact?.colorIndex ?: 0).takeIf { it != 0 } ?: avatarColorIndex(chatConversationId, profiles)
    } else 0
    val chatPresenceDot: PresenceState? = if (chatConversationId == null || chatConversationId == MESH_CONVERSATION_ID) {
        null
    } else presence[chatConversationId]?.state?.takeIf { it != PresenceState.UNKNOWN }

    // A notification tap navigates to its target's top-level route, popping any detail
    // screens first so repeated taps never grow the stack. The chats home does not deep-link
    // into messages yet (the conversation screen is built in a later step), so every request
    // is consumed here after navigating.
    LaunchedEffect(navRequest?.seq) {
        val request = navRequest ?: return@LaunchedEffect
        navController.navigateToTopLevel(navTabToRoute(request.target.tab))
        onNavConsumed()
    }

    // While searching, a long wait with only non-AirMesh devices visible gets the
    // hint banner; otherwise the state banner applies.
    val autoBannerText = noAirMeshPeersBannerText(
        autoMeshState, nowMs, airMeshPeers.size, connectorState.peers.size,
        strategy = discoveryStrategy, rawCallbacks = rawCallbackCount,
    ) ?: autoMeshBannerText(autoMeshState, neighbors.size, autoIdleReason, nowMs)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // chat/{id} gets the WhatsApp-style header: avatar + name + presence
                    // subtitle (TASK U2 part 1); every other route keeps its plain title.
                    if (chatConversationId != null) {
                        Row(
                            modifier = Modifier.padding(start = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChatHeaderAvatar(
                                conversationId = chatConversationId,
                                name = chatTitle,
                                colorIndex = chatColorIndex,
                                presence = chatPresenceDot,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    chatTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (chatSubtitle != null) {
                                    Text(
                                        chatSubtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    } else {
                        Text(topBarTitle(currentRoute))
                    }
                },
                // Detail screens (chat/{id}, profile) get a back arrow; the
                // bottom-bar routes do not.
                navigationIcon = {
                    if (!isTopLevelRoute(currentRoute)) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(imageVector = MeshBackIcon, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    // The chats home owns the status chip and overflow menu (TASK U1 part 3);
                    // the other tabs keep a plain title bar.
                    if (currentRoute == ROUTE_CHATS) {
                        val status = ChatsStatus.from(autoMeshState, neighbors.size)
                        StatusChip(
                            text = status.text,
                            tone = if (status.connected) ChipTone.Active else ChipTone.Neutral,
                            modifier = Modifier.padding(end = AirMeshSpacing.xs),
                        )
                        Box {
                            var menuOpen by rememberSaveable { mutableStateOf(false) }
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(imageVector = MeshMoreIcon, contentDescription = "Menu")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("My profile") },
                                    onClick = { menuOpen = false; navController.navigate(ROUTE_PROFILE) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Network") },
                                    onClick = { menuOpen = false; navController.navigateToTopLevel(ROUTE_NETWORK) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Event log") },
                                    onClick = { menuOpen = false; navController.navigateToTopLevel(ROUTE_LOG) },
                                )
                                DropdownMenuItem(
                                    text = { Text("About") },
                                    onClick = { menuOpen = false; showAbout = true },
                                )
                            }
                        }
                    }
                    // The conversation screen owns Contact info / Clear chat (TASK U2 part 1).
                    else if (currentRoute == ROUTE_CHAT && chatConversationId != null) {
                        Box {
                            var menuOpen by rememberSaveable { mutableStateOf(false) }
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(imageVector = MeshMoreIcon, contentDescription = "Menu")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Contact info") },
                                    onClick = { menuOpen = false; contactInfoConversation = chatConversationId },
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear chat") },
                                    onClick = { menuOpen = false; clearChatTarget = chatConversationId },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            // The bar only exists on top-level routes (TASK U1 part 2).
            if (isTopLevelRoute(currentRoute)) {
                NavigationBar {
                    MeshTab.values().forEach { t ->
                        NavigationBarItem(
                            selected = currentRoute == t.route,
                            onClick = { navController.navigateToTopLevel(t.route) },
                            icon = { Icon(imageVector = t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // The "New message" FAB only exists on the chats home (TASK U1 part 3).
            if (currentRoute == ROUTE_CHATS) {
                ExtendedFloatingActionButton(
                    icon = { Icon(imageVector = MeshNewMessageIcon, contentDescription = null) },
                    text = { Text("New message") },
                    onClick = { showContactPicker = true },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Keep the compose bar (and any content) above the keyboard.
                .imePadding(),
        ) {
            AutoMeshHeader(
                bannerText = autoBannerText,
                enabled = autoMeshEnabled,
                onSetEnabled = onSetAutoMeshEnabled,
            )
            NavHost(
                navController = navController,
                startDestination = ROUTE_CHATS,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                composable(ROUTE_CHATS) {
                    // The WhatsApp-style home (TASK U1 part 3). Rows navigate to the chat
                    // route; the old MeshChatTab leaves the graph until the redesigned
                    // conversation screen replaces the placeholder in a later step.
                    ChatsHomeContent(
                        summaries = conversationSummaries,
                        contacts = contacts,
                        presence = presence,
                        nowMs = nowMs,
                        onOpenConversation = { navController.navigate(chatRoute(it)) },
                    )
                }
                composable(ROUTE_CHAT) { entry ->
                    // The id is an Int in the chat model; a malformed argument just lands on 0.
                    val conversationId = entry.arguments?.getString("conversationId")?.toIntOrNull() ?: 0
                    var chatMessages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
                    LaunchedEffect(conversationId) {
                        messagesFor(conversationId).collect { chatMessages = it }
                    }
                    ChatConversationContent(
                        conversationId = conversationId,
                        isMeshRoom = conversationId == MESH_CONVERSATION_ID,
                        messages = chatMessages,
                        nowMs = nowMs,
                        myLastFix = myLastFix,
                        contactById = remember(contacts) { contacts.associateBy { it.nodeId } },
                        profiles = profiles,
                        bannerText = chatBannerText(
                            conversationId,
                            presence[conversationId],
                            ChatsStatus.from(autoMeshState, neighbors.size).connected,
                        ),
                        showEncryptionNote = showEncryptionNote,
                        onDismissEncryptionNote = onDismissEncryptionNote,
                        // Already routed by the caller: broadcast in the room, direct otherwise.
                        onSendText = { text ->
                            if (conversationId == MESH_CONVERSATION_ID) onSendText(text) else onSendDirect(conversationId, text)
                        },
                        onShareLocation = onShareLocation,
                        shareStatus = shareStatus,
                        shareDeadlineMs = shareDeadlineMs,
                        onCancelShare = onCancelShare,
                        shareFailureMessage = shareFailureMessage,
                        myLatestShareWasCached = myLatestShareWasCached,
                        useTestLocation = useTestLocation,
                        showTestLocationToggle = showTestLocationToggle,
                        onShareTestLocation = onShareTestLocation,
                        onMarkRead = onMarkRead,
                        // Resending a failed direct message sends it again as a new one.
                        onResendDirect = { text -> onSendDirect(conversationId, text) },
                        onOpenInMaps = onOpenInMaps,
                    )
                }
                composable(ROUTE_NETWORK) {
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
                        airMeshPeers = airMeshPeers,
                        showTestLocationToggle = showTestLocationToggle,
                        useTestLocation = useTestLocation,
                        onSetUseTestLocation = onSetUseTestLocation,
                        messageNotificationsEnabled = messageNotificationsEnabled,
                        onSetMessageNotificationsEnabled = onSetMessageNotificationsEnabled,
                        mergeGroupsEnabled = mergeGroupsEnabled,
                        onSetMergeGroupsEnabled = onSetMergeGroupsEnabled,
                        profiles = profiles,
                    )
                }
                composable(ROUTE_LOG) { MeshLogTab(events = events, onClearLog = onClearLog) }
                composable(ROUTE_PROFILE) {
                    // Full-screen profile editor (TASK U1 part 4): the same content as the old
                    // bottom sheet; saving goes through service.setMyProfile and pops back.
                    ProfileScreen(
                        nodeId = nodeId,
                        myProfile = myProfile,
                        onSave = { name, colorIndex ->
                            onSaveProfile(name, colorIndex)
                            navController.popBackStack()
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }

    if (showContactPicker) {
        ContactPickerSheet(
            contacts = contacts,
            presence = presence,
            nowMs = nowMs,
            onOpenConversation = { id ->
                showContactPicker = false
                navController.navigate(chatRoute(id))
            },
            onDismiss = { showContactPicker = false },
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    if (showOnboarding) {
        OnboardingDialog(
            nodeId = nodeId,
            onSave = { name, colorIndex ->
                onSaveProfile(name, colorIndex)
                showOnboarding = false
                onOnboardingAnswered()
            },
            onSkip = {
                showOnboarding = false
                onOnboardingAnswered()
            },
        )
    }

    // Conversation overflow dialogs (TASK U2 part 1), keyed by conversation id.
    // LocalClipboardManager is deprecated in favor of LocalClipboard, but the new API
    // needs platform ClipData/ClipEntry construction for a one-line text write.
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    contactInfoConversation?.let { id ->
        ContactInfoDialog(
            conversationId = id,
            title = ConversationListBuilder.titleFor(id, contacts.firstOrNull { it.nodeId == id }),
            isMeshRoom = id == MESH_CONVERSATION_ID,
            onCopyNodeId = { clipboardManager.setText(AnnotatedString("$id")) },
            onDismiss = { contactInfoConversation = null },
        )
    }

    clearChatTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { clearChatTarget = null },
            title = { Text("Clear this conversation?") },
            text = { Text("All messages here will be deleted on this phone. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    clearChatTarget = null
                    onClearChat(id)
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { clearChatTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Top-bar avatar for chat/{id} (TASK U2 part 1): the peer's identity-colored initial with a
 * presence dot, or a neutral mesh icon for the broadcast room. Internal so previews can render it.
 */
@Composable
internal fun ChatHeaderAvatar(
    conversationId: Int,
    name: String,
    colorIndex: Int,
    presence: PresenceState?,
) {
    if (conversationId == MESH_CONVERSATION_ID) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = MeshNetworkIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
        }
    } else {
        Avatar(name = name, colorIndex = colorIndex, size = 32.dp, presence = presence)
    }
}

/**
 * Header shown above every tab: the auto-mesh status banner (when there is one)
 * and the "Auto connect" switch. The service persists the switch; manual Wi-Fi
 * Direct actions only pause auto-mesh temporarily until it is turned back on.
 * Internal (not private) so the design-time previews can render it directly.
 */
@Composable
internal fun AutoMeshHeader(bannerText: String?, enabled: Boolean, onSetEnabled: (Boolean) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (bannerText != null) AutoMeshBanner(bannerText)
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto connect", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Join or create a group automatically when other AirMesh phones are nearby.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onSetEnabled)
            }
        }
    }
}

/**
 * Auto-mesh status banner. The text is pure and JVM-tested; when it carries a
 * second newline-separated line (the discovery strategy detail), that line is
 * rendered smaller so the hint stays the prominent part.
 */
@Composable
fun AutoMeshBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val lines = text.split('\n', limit = 2)
        Column {
            Text(lines[0], style = MaterialTheme.typography.bodyMedium)
            if (lines.size > 1) {
                Text(
                    lines[1],
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Per-route top-bar title (TASK U1 part 3): the app name on chats, tab names elsewhere. */
private fun topBarTitle(route: String?): String = when {
    route == ROUTE_NETWORK -> "Network"
    route == ROUTE_LOG -> "Event log"
    else -> "AirMesh"
}

/**
 * Navigates to a top-level route without stacking duplicates: any detail screens
 * above the start destination are popped first, and re-tapping the current tab is
 * a no-op (launchSingleTop). Used by both the bottom bar and notification taps.
 */
private fun NavController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(ROUTE_CHATS) { inclusive = false }
        launchSingleTop = true
    }
}

/** Profile route (TASK U1 part 4): the sheet editor as a full screen; saving pops back. Internal so previews can render it. */
@Composable
internal fun ProfileScreen(
    nodeId: Int,
    myProfile: MyProfile,
    onSave: (name: String, colorIndex: Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ProfileSheetContent(
            nodeId = nodeId,
            initialName = myProfile.name ?: "",
            initialColorIndex = myProfile.colorIndex,
            onSave = onSave,
            onCancel = onBack,
        )
    }
}

