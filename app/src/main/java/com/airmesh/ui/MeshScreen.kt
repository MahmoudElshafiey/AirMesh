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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import com.airmesh.chat.LiveConversationListBuilder
import com.airmesh.chat.MESH_CONVERSATION_ID
import com.airmesh.presence.PresenceState as LivePresenceState
import com.airmesh.presence.formatPresenceLabel
import com.airmesh.presence.liveChatBannerText
import com.airmesh.presence.presenceLabel
import com.airmesh.transport.AirMeshPeer
import com.airmesh.transport.AutoMeshState
import com.airmesh.transport.ConnectorState
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
    // Debuggable builds only: per-screen recomposition counts and UI state emissions are
    // reported to the event log every 30 s while the UI is visible (TASK U4 part 3).
    showPerfCounters: Boolean = false,
    // Whether every Wi-Fi Direct runtime permission is currently held; when not, the chats
    // empty state offers a "Grant permission" button instead of searching forever.
    permissionsGranted: Boolean = true,
    // UI self-check lines (radar indicator) land in the same event log as transport events.
    onUiEventLog: (String) -> Unit = {},
    // Display identity: my own profile, the names the mesh learned, and the save
    // action. promptOnboarding is true only on the first run, until the dialog
    // has been answered (saved or skipped) once.
    myProfile: MyProfile = MyProfile(name = null, colorIndex = 0),
    profiles: Map<Int, ProfileInfo> = emptyMap(),
    // Chats home data (TASK U1 part 3): conversation rollups and known contacts from the
    // store, plus per-node presence for the dots and "Online / Last seen" lines.
    conversationSummaries: List<ConversationSummary> = emptyList(),
    contacts: List<Contact> = emptyList(),
    livePresence: Map<Int, LivePresenceState> = emptyMap(),
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

    // Debug-only perf probes (TASK U4 part 3): null in release builds, so none of the
    // recording below runs there. The counters use plain fields and stay invisible to Compose.
    val perfCounters = remember(showPerfCounters) { if (showPerfCounters) PerfCounters() else null }
    val perf = perfCounters
    if (perf != null) {
        // Runs on the first composition and again exactly when any UI-facing value changes:
        // one count per distinct UI update, batched across flows that change in the same frame.
        TrackUiEmissions(
            counter = perf.uiEmissions,
            connectorState = connectorState,
            neighbors = neighbors,
            messages = messages,
            locations = locations,
            myProfile = myProfile,
            profiles = profiles,
            events = events,
            messageNotificationsEnabled = messageNotificationsEnabled,
            mergeGroupsEnabled = mergeGroupsEnabled,
            autoMeshState = autoMeshState,
            autoIdleReason = autoIdleReason,
            airMeshPeers = airMeshPeers,
            autoMeshEnabled = autoMeshEnabled,
            discoveryStrategy = discoveryStrategy,
            rawCallbackCount = rawCallbackCount,
            livePresence = livePresence,
            conversationSummaries = conversationSummaries,
            contacts = contacts,
        )
    }
    // Every 30 s while the UI is visible: report per-screen recompositions and how often any
    // UI-facing value changed. The line goes to the event log (its "perf:" prefix is protected).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, perfCounters) {
        val counters = perfCounters ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(PERF_REPORT_INTERVAL_MS)
                onUiEventLog(
                    perfLine(
                        counters.chats.drain(),
                        counters.chat.drain(),
                        counters.network.drain(),
                        counters.logTab.drain(),
                        counters.uiEmissions.drain(),
                    ),
                )
            }
        }
    }

    // Chat route header (TASK U2 part 1): the shared top bar shows avatar + name + presence
    // subtitle on chat/{id}, and its overflow menu offers Contact info / Clear chat. The
    // dialogs are keyed by conversation id so a stale flag can never show another chat's data.
    // baseRoute(): destination.route reports the registered pattern, which may carry a query.
    val chatConversationId: Int? = if (baseRoute(currentRoute) == ROUTE_CHAT) {
        backStackEntry?.arguments?.getString("conversationId")?.toIntOrNull() ?: 0
    } else null
    var contactInfoConversation by rememberSaveable { mutableStateOf<Int?>(null) }
    var clearChatTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    val chatContact = if (chatConversationId != null && chatConversationId != MESH_CONVERSATION_ID) {
        contacts.firstOrNull { it.nodeId == chatConversationId }
    } else null
    val chatTitle = chatConversationId?.let { ConversationListBuilder.titleFor(it, chatContact) }.orEmpty()
    // Room: live group size; direct chat: the peer's truthful presence line ("Not nearby" when unknown).
    val chatSubtitle: String? = when (chatConversationId) {
        null -> null
        MESH_CONVERSATION_ID -> "${neighbors.size} phones"
        else -> formatPresenceLabel(
            presenceLabel(livePresence[chatConversationId] ?: LivePresenceState.UNKNOWN, nowMs), nowMs,
        ).ifEmpty { "Not nearby" }
    }
    // Header avatar: the peer's identity color (deterministic fallback when unknown);
    // the room uses a neutral mesh icon instead. For direct chats the dot always shows —
    // green online, amber checking, gray for everything else including UNKNOWN (TASK U5 part 2).
    val chatColorIndex = if (chatConversationId != null && chatConversationId != MESH_CONVERSATION_ID) {
        (chatContact?.colorIndex ?: 0).takeIf { it != 0 } ?: avatarColorIndex(chatConversationId, profiles)
    } else 0
    val chatPresenceDot: LivePresenceState? = if (chatConversationId == null || chatConversationId == MESH_CONVERSATION_ID) {
        null
    } else livePresence[chatConversationId]
    // The subtitle is colored like the dot for direct chats; the room line stays neutral.
    val chatSubtitleColor: Color = if (chatPresenceDot != null) {
        livePresenceLabelColor(chatPresenceDot)
    } else MaterialTheme.colorScheme.onSurfaceVariant

    // A notification tap navigates to its target's top-level route, popping any detail
    // screens first so repeated taps never grow the stack. The chats home does not deep-link
    // into messages yet (the conversation screen is built in a later step), so every request
    // is consumed here after navigating.
    LaunchedEffect(navRequest?.seq) {
        val request = navRequest ?: return@LaunchedEffect
        navController.navigateToTopLevel(navTabToRoute(request.target.tab))
        onNavConsumed()
    }

    // Home banner (TASK U4 part 2): at most one, and only for actionable problems. The old
    // "found N devices but none advertises AirMesh" hint is gone from home — its content now
    // lives in the empty-state caption and the manual-connect button. The technical discovery
    // line (strategy / raw callbacks) never appears here; it belongs to the Network tab's
    // Debug card, which only exists in debuggable builds.
    val homeEmptyStateVisible = remember(conversationSummaries, contacts, livePresence, nowMs) {
        // Mirrors ChatsHomeContent: the empty state shows exactly when no non-room row exists.
        LiveConversationListBuilder.build(conversationSummaries, contacts, livePresence, nowMs)
            .none { it.conversationId != MESH_CONVERSATION_ID }
    }
    val homeBanner = homeBannerText(
        status = autoMeshState,
        permissionOk = permissionsGranted,
        neighborCount = neighbors.size,
        idleReason = autoIdleReason,
        nowMs = nowMs,
        emptyStateVisible = homeEmptyStateVisible,
    )

    // Chats empty state (TASK U4 part 1): pure mapping from the controller state, how long we
    // have been Searching, and the count of classic (non-AirMesh) Wi-Fi Direct devices. The timer
    // is fed on every nowMs tick so the 90 s manual-connect hint appears on time even though the
    // Searching object itself never changes while discovery runs.
    val searchTimer = remember { SearchTimer() }
    var searchingForMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(autoMeshState, nowMs) {
        searchingForMs = searchTimer.update(autoMeshState)
    }
    // The CONNECTING target id is derived from the state inside homeEmptyState.
    val homeEmptyState = homeEmptyState(
        status = autoMeshState,
        otherDevicesFound = connectorState.peers.size,
        searchingForMs = searchingForMs,
        permissionOk = permissionsGranted,
    )

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
                                livePresence = chatPresenceDot,
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
                                        color = chatSubtitleColor,
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
                    if (baseRoute(currentRoute) == ROUTE_CHATS) {
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
                    else if (baseRoute(currentRoute) == ROUTE_CHAT && chatConversationId != null) {
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
                            selected = baseRoute(currentRoute) == t.route,
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
            if (baseRoute(currentRoute) == ROUTE_CHATS) {
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
            // At most one banner, and only on home (TASK U4 part 2); the other tabs stay clean.
            if (baseRoute(currentRoute) == ROUTE_CHATS && homeBanner != null) {
                AutoMeshBanner(homeBanner, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            NavHost(
                navController = navController,
                startDestination = ROUTE_CHATS,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                composable(ROUTE_CHATS) {
                    perf?.chats?.record() // debug-only recomposition probe (TASK U4 part 3)
                    // The WhatsApp-style home (TASK U1 part 3). Rows navigate to the chat
                    // route; the old MeshChatTab leaves the graph until the redesigned
                    // conversation screen replaces the placeholder in a later step.
                    ChatsHomeContent(
                        summaries = conversationSummaries,
                        contacts = contacts,
                        livePresence = livePresence,
                        nowMs = nowMs,
                        onOpenConversation = { navController.navigate(chatRoute(it)) },
                        homeEmptyState = homeEmptyState,
                        onGrantPermission = onRequestPermissions,
                        // Escape hatch after a long search: jump to the Network tab with the
                        // Advanced card already open (TASK U4 part 1).
                        onManualConnect = {
                            navController.navigate(networkRouteExpandAdvanced()) {
                                popUpTo(ROUTE_CHATS) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onUiEventLog = onUiEventLog,
                    )
                }
                composable(ROUTE_CHAT) { entry ->
                    perf?.chat?.record() // debug-only recomposition probe (TASK U4 part 3)
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
                        // Room keeps the connection banner; direct chats warn by truthful state (TASK U5 part 2).
                        bannerText = if (conversationId == MESH_CONVERSATION_ID) {
                            chatBannerText(conversationId, null, ChatsStatus.from(autoMeshState, neighbors.size).connected)
                        } else {
                            liveChatBannerText(livePresence[conversationId])
                        },
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
                composable(ROUTE_NETWORK_PATTERN) { entry ->
                    perf?.network?.record() // debug-only recomposition probe (TASK U4 part 3)
                    // The optional argument is set by the chats home's "Manual connect" hint.
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
                        // The "Auto connect" switch moved here from the home header (TASK U4 part 2);
                        // same flows, so storage and behavior are unchanged.
                        autoMeshEnabled = autoMeshEnabled,
                        onSetAutoMeshEnabled = onSetAutoMeshEnabled,
                        // Discovery diagnostics for the Debug card (debuggable builds only).
                        discoveryStrategy = discoveryStrategy,
                        rawCallbackCount = rawCallbackCount,
                        profiles = profiles,
                        // Classic route parameters are Strings; "true" only comes from the hint.
                        advancedInitiallyExpanded = entry.arguments?.getString(ARG_EXPAND_ADVANCED) == "true",
                    )
                }
                composable(ROUTE_LOG) {
                    perf?.logTab?.record() // debug-only recomposition probe (TASK U4 part 3)
                    MeshLogTab(events = events, onClearLog = onClearLog)
                }
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
            livePresence = livePresence,
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
    livePresence: LivePresenceState?,
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
        Avatar(name = name, colorIndex = colorIndex, size = 32.dp, livePresence = livePresence)
    }
}

/**
 * Auto-mesh status banner. The text is pure and JVM-tested (TASK U4 part 2: home shows at most
 * one of these, only for actionable problems); when it carries a second newline-separated line,
 * that line is rendered smaller so the first stays the prominent part.
 */
@Composable
fun AutoMeshBanner(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
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
private fun topBarTitle(route: String?): String = when (baseRoute(route)) {
    ROUTE_NETWORK -> "Network"
    ROUTE_LOG -> "Event log"
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

/**
 * Counts UI state emissions for the 30 s perf line (TASK U4 part 3). Compose skips this body
 * while every parameter stays structurally equal, so it records exactly once per distinct UI
 * update — flows that change in the same frame count as one. The counter is invisible to
 * Compose (plain fields), so recording never invalidates anything.
 */
@Composable
private fun TrackUiEmissions(
    counter: RecompositionCounter,
    connectorState: ConnectorState,
    neighbors: Set<Int>,
    messages: List<MeshMessage>,
    locations: List<MeshLocation>,
    myProfile: MyProfile,
    profiles: Map<Int, ProfileInfo>,
    events: List<LogEntry>,
    messageNotificationsEnabled: Boolean,
    mergeGroupsEnabled: Boolean,
    autoMeshState: AutoMeshState,
    autoIdleReason: String?,
    airMeshPeers: List<AirMeshPeer>,
    autoMeshEnabled: Boolean,
    discoveryStrategy: Int,
    rawCallbackCount: Int,
    livePresence: Map<Int, LivePresenceState>,
    conversationSummaries: List<ConversationSummary>,
    contacts: List<Contact>,
) {
    counter.record()
}

