package com.airmesh.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.airmesh.chat.Contact
import com.airmesh.chat.ConversationSummary
import com.airmesh.chat.NodePresence
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

    // A notification tap navigates to its target's top-level route, popping any detail
    // screens first so repeated taps never grow the stack. The chats home does not deep-link
    // into messages yet (the conversation screen is built in a later step), so every request
    // is consumed here after navigating.
    LaunchedEffect(navRequest?.seq) {
        val request = navRequest ?: return@LaunchedEffect
        navController.navigateToTopLevel(navTabToRoute(request.target.tab))
        onNavConsumed()
    }

    // The banner's "Retrying in Ns" and join-waiting texts need a live clock;
    // the state flows alone won't re-emit an unchanged state object each second.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
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
                title = { Text(topBarTitle(currentRoute)) },
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
                    ChatPlaceholderScreen(conversationId = conversationId)
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
                composable(ROUTE_PROFILE) { ProfilePlaceholderScreen(nodeId = nodeId, myProfile = myProfile) }
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

/** Placeholder for chat/{conversationId}; TASK U1 part 3 builds the real screen. */
@Composable
private fun ChatPlaceholderScreen(conversationId: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AirMeshSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Conversation #$conversationId", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(AirMeshSpacing.sm))
        Text(
            "This screen is built in a later step.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Placeholder for the profile route; TASK U1 part 4 builds the full editor. */
@Composable
private fun ProfilePlaceholderScreen(nodeId: Int, myProfile: MyProfile) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AirMeshSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Avatar(name = myProfile.name ?: "User #$nodeId", colorIndex = myProfile.colorIndex, size = 72.dp)
        Spacer(modifier = Modifier.height(AirMeshSpacing.sm))
        Text("Node #$nodeId", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(AirMeshSpacing.xs))
        Text(
            "Profile editing is built in a later step.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

