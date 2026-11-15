package com.airmesh.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.StrictMode
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airmesh.node.AndroidLocationProvider
import com.airmesh.node.LOCATION_MAX_WAIT_MS
import com.airmesh.node.LocationFix
import com.airmesh.node.SharedPreferencesTestLocationStore
import com.airmesh.node.TestLocationStore
import com.airmesh.service.AppForeground
import com.airmesh.service.MeshService
import com.airmesh.transport.PermissionRequirements
import com.airmesh.transport.PrototypeActivity
import com.airmesh.ui.theme.AirMeshTheme
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Real mesh UI. The mesh itself runs in [MeshService] (a foreground service), so
 * it keeps working when the screen is off or this activity goes to the background;
 * this activity only binds to the service, observes its StateFlows and forwards
 * user actions — state survives recreation and rotation because it lives in the
 * service. Kept separate from the throwaway [PrototypeActivity] so the prototype
 * keeps working as a fallback and can be opened from a button.
 */
class MeshActivity : ComponentActivity() {

    /** The bound mesh service; null until onServiceConnected (and before grant). */
    private val meshService = mutableStateOf<MeshService?>(null)
    private var serviceBound = false

    /** Wi-Fi Direct permissions held; drives the gate screen (button vs starting). */
    private val wifiDirectGranted = mutableStateOf(false)

    /** Pending navigation from a notification tap; consumed by the UI. The seq
     * number makes repeated taps of the same notification re-trigger the effect. */
    private val navRequest = mutableStateOf<NavRequest?>(null)
    private var navSeq = 0

    private lateinit var themeStore: ThemeModeStore
    private lateinit var onboardingStore: ProfileOnboardingStore
    private lateinit var chatNoticeStore: ChatNoticeStore
    /** One-time "not encrypted" note in conversations; persisted once dismissed (TASK U2 part 1). */
    private val encryptionNoteDismissed = mutableStateOf(false)
    private var locationProvider: AndroidLocationProvider? = null

    // Set in onCreate (applicationInfo is not available during construction).
    // BuildConfig is not generated for this module; the debuggable flag is the
    // reliable way to tell a debug build apart. The test-location toggle and its
    // persistence are debug features, so release builds never create the store.
    private var isDebugBuild = false
        private set
    private var testLocationStore: TestLocationStore? = null
        private set

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Progress of the current share request; IDLE means "nothing in flight". */
    private val shareStatus = mutableStateOf(LocationShareStatus.IDLE)
    /** Specific reason of the last failed attempt, shown above the compose bar. */
    private val shareFailureMessage = mutableStateOf<String?>(null)
    /** Deadline of the in-flight real-fix wait; drives the countdown (null: none). */
    private val shareDeadlineMs = mutableStateOf<Long?>(null)
    /** DEBUG builds only: when on, a fixed test point is shared instead of GPS. Persisted across restarts. */
    private val useTestLocation = mutableStateOf(false)
    /** Whether my latest shared fix was a cached last-known one (card label). */
    private val myLatestShareWasCached = mutableStateOf(false)
    /** My own last fix — used to show "X m from you" on received location cards. */
    private val myLastFix = mutableStateOf<LocationFix?>(null)

    /** The in-flight share coroutine, so the Cancel button can stop it mid-wait. */
    private var shareJob: Job? = null

    /** Wi-Fi Direct gate: once every required permission is held, start the service. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        wifiDirectGranted.value = PermissionRequirements.required(Build.VERSION.SDK_INT)
            .all { results[it] == true }
        if (wifiDirectGranted.value) {
            startMeshService()
            // Non-blocking: denying this only removes the per-message notifications.
            requestNotificationPermissionIfNeeded()
        }
        // Otherwise stay on the gate screen; the button remains available.
    }

    /** Android 13+ only, asked once after the Wi-Fi Direct grant; denial is fine. */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        log("notifications permission granted=${results[Manifest.permission.POST_NOTIFICATIONS] == true}")
    }

    /** Location permissions are requested only when the user taps "Share my location". */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Same per-permission lines as the Wi-Fi Direct connector, so a denied
        // dialog is visible in the event log.
        results.forEach { (permission, granted) ->
            log("runtime permission ${permission.substringAfterLast('.')} granted=$granted")
        }
        if (PermissionRequirements.locationPermissionsForSharing().all(::isPermissionGranted)) {
            shareMyLocation()
        } else {
            log("location: permission not granted — cannot share")
            shareStatus.value = LocationShareStatus.FAILED
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? MeshService.LocalBinder)?.service ?: return
            meshService.value = service
            serviceBound = true
            // Notifications posted while we were opening the app are redundant now.
            service.clearMessageNotifications()
            // Fresh permission snapshot so the connector's state is accurate on (re)bind.
            service.onPermissionResults(permissionStates())
            // Every phase of a share attempt lands in the service's event log.
            locationProvider = AndroidLocationProvider(this@MeshActivity, service.eventLog::log)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The service runs in this same process; this only fires at process
            // death, when we are recreated and rebind from onCreate anyway.
            meshService.value = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableStrictModeInDebugBuilds()

        themeStore = SharedPreferencesThemeModeStore(this)
        onboardingStore = SharedPreferencesProfileOnboardingStore(this)
        chatNoticeStore = SharedPreferencesChatNoticeStore(this)
        encryptionNoteDismissed.value = chatNoticeStore.isEncryptionNoteDismissed()
        isDebugBuild = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        testLocationStore = if (isDebugBuild) SharedPreferencesTestLocationStore(applicationContext) else null
        // The debug switch survives process death; release builds stay off.
        useTestLocation.value = testLocationStore?.load() ?: false

        wifiDirectGranted.value = wifiDirectPermissionsGranted()
        // The mesh runs in a foreground service, started from here once the Wi-Fi
        // Direct permissions are held — never at boot. A second start (e.g. after
        // process death) is a no-op inside the service.
        if (wifiDirectGranted.value) {
            startMeshService()
        }

        // A notification tap may have launched us; parse its navigation extras once.
        // On recreation (savedInstanceState != null) the pending request is kept as-is:
        // re-parsing would scroll to a stale message again after rotation.
        if (savedInstanceState == null) handleNavIntent(intent)

        setContent {
            val themeMode = remember { mutableStateOf(themeStore.load()) }
            val dark = resolveDarkTheme(themeMode.value, isSystemInDarkTheme())

            AirMeshTheme(darkTheme = dark) {
                when (val service = meshService.value) {
                    null -> ServiceGate(
                        starting = wifiDirectGranted.value,
                        onStart = ::requestPermissions,
                    )
                    else -> {
                        // collectAsStateWithLifecycle (TASK U4 part 4): collection pauses while the
                        // activity is below STARTED, so backgrounded UI costs nothing. The log tab
                        // reads the batched uiEvents view (at most one update per 250 ms).
                        val connectorState by service.connectorState.collectAsStateWithLifecycle()
                        val neighbors by service.neighbors.collectAsStateWithLifecycle()
                        val messages by service.messages.collectAsStateWithLifecycle()
                        val locations by service.locations.collectAsStateWithLifecycle()
                        val myProfile by service.myProfile.collectAsStateWithLifecycle()
                        val profiles by service.profiles.collectAsStateWithLifecycle()
                        val events by service.uiEvents.collectAsStateWithLifecycle()
                        val messageNotificationsEnabled by service.messageNotificationsEnabled.collectAsStateWithLifecycle()
                        val mergeGroupsEnabled by service.mergeGroupsEnabled.collectAsStateWithLifecycle()
                        val autoMeshState by service.autoMeshState.collectAsStateWithLifecycle()
                        val autoIdleReason by service.autoIdleReason.collectAsStateWithLifecycle()
                        val airMeshPeers by service.airMeshPeers.collectAsStateWithLifecycle()
                        val autoMeshEnabled by service.autoMeshEnabled.collectAsStateWithLifecycle()
                        val discoveryStrategy by service.discoveryStrategy.collectAsStateWithLifecycle()
                        val rawCallbackCount by service.rawCallbackCount.collectAsStateWithLifecycle()
                        // Chats home data (TASK U1 part 3): presence plus the store's
                        // conversation rollups and known contacts. The flows are cold, so
                        // they start empty until Room emits its first snapshot.
                        val livePresence by service.livePresence.collectAsStateWithLifecycle()
                        // Confirmed presence transitions per node for the chat timeline (TASK U5 part 3).
                        val presenceEventsByNode by service.presenceEventsByNode.collectAsStateWithLifecycle()
                        val conversationSummaries by service.chatStore.summaries.collectAsStateWithLifecycle(initialValue = emptyList())
                        val contacts by service.chatStore.contacts.collectAsStateWithLifecycle(initialValue = emptyList())

                        MeshScreen(
                            nodeId = service.nodeId,
                            connectorState = connectorState,
                            neighbors = neighbors,
                            messages = messages,
                            locations = locations,
                            myLastFix = myLastFix.value,
                            shareStatus = shareStatus.value,
                            events = events,
                            navRequest = navRequest.value,
                            onNavConsumed = { navRequest.value = null },
                            onRequestPermissions = ::requestPermissions,
                            onDiscover = service::discover,
                            onCreateGroup = service::createGroup,
                            onRetryTcp = service::retryConnect,
                            onDisconnect = service::disconnectFromGroup,
                            onConnectPeer = service::connect,
                            onSendText = { text ->
                                uiScope.launch { service.sendText(text) }
                            },
                            onShareLocation = ::requestLocationShare,
                            shareDeadlineMs = shareDeadlineMs.value,
                            onCancelShare = ::cancelLocationShare,
                            shareFailureMessage = shareFailureMessage.value,
                            myLatestShareWasCached = myLatestShareWasCached.value,
                            showTestLocationToggle = isDebugBuild,
                            // 30 s perf lines (recompositions + UI emissions) in debug builds only.
                            showPerfCounters = isDebugBuild,
                            useTestLocation = useTestLocation.value,
                            onSetUseTestLocation = { value ->
                                // Persist so the switch survives process death (debug builds only).
                                useTestLocation.value = value
                                testLocationStore?.save(value)
                            },
                            onShareTestLocation = ::shareTestLocationInstead,
                            onClearLog = service::clearLog,
                            onOpenPrototype = {
                                startActivity(Intent(this, PrototypeActivity::class.java))
                            },
                            themeMode = themeMode.value,
                            // Applies immediately (state change recomposes) and persists.
                            onSetThemeMode = { mode ->
                                themeMode.value = mode
                                themeStore.save(mode)
                            },
                            messageNotificationsEnabled = messageNotificationsEnabled,
                            onSetMessageNotificationsEnabled = service::setMessageNotificationsEnabled,
                            mergeGroupsEnabled = mergeGroupsEnabled,
                            onSetMergeGroupsEnabled = service::setMergeGroupsEnabled,
                            autoMeshState = autoMeshState,
                            autoIdleReason = autoIdleReason,
                            airMeshPeers = airMeshPeers,
                            autoMeshEnabled = autoMeshEnabled,
                            onSetAutoMeshEnabled = service::setAutoMeshEnabled,
                            discoveryStrategy = discoveryStrategy,
                            rawCallbackCount = rawCallbackCount,
                            permissionsGranted = wifiDirectGranted.value,
                            // Radar indicator self-check lines go to the same Log tab.
                            onUiEventLog = ::log,
                            myProfile = myProfile,
                            profiles = profiles,
                            conversationSummaries = conversationSummaries,
                            contacts = contacts,
                            livePresence = livePresence,
                            presenceEventsByNode = presenceEventsByNode,
                            onSaveProfile = { name, colorIndex ->
                                uiScope.launch { service.setMyProfile(name, colorIndex) }
                            },
                            // First run only: a stored name means the user already
                            // answered (or saved) once, even after an app update.
                            promptOnboarding = !onboardingStore.isDone() && myProfile.name == null,
                            onOnboardingAnswered = { onboardingStore.markDone() },
                            // Conversation screen wiring (TASK U2 part 1): per-conversation message flow from the
                            // store, direct sends, read marking, clearing and the location card's maps action.
                            messagesFor = { id -> service.chatStore.messages(id) },
                            onSendDirect = { destId, text -> uiScope.launch { service.sendDirect(destId, text) } },
                            onMarkRead = { id -> uiScope.launch { service.chatStore.markRead(id) } },
                            onClearChat = { id -> uiScope.launch { service.chatStore.clearConversation(id) } },
                            onOpenInMaps = ::openInMaps,
                            showEncryptionNote = !encryptionNoteDismissed.value,
                            onDismissEncryptionNote = {
                                encryptionNoteDismissed.value = true
                                chatNoticeStore.markEncryptionNoteDismissed()
                            },
                        )
                    }
                }
            }

            // Re-apply the system bar styles whenever the effective theme flips,
            // so status/navigation icons stay readable in both themes.
            LaunchedEffect(dark) { applySystemBars(dark) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        // singleTop: tapping a notification while this activity is at the top of its
        // task reuses it instead of stacking a copy; route its extras like a fresh launch.
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavIntent(intent)
    }

    /** Parses navigation extras into a [NavRequest] for the UI (the parsing itself is pure and JVM-tested). */
    private fun handleNavIntent(intent: Intent?) {
        navSeq += 1
        // The parser takes a plain map so it stays unit-testable without Android.
        val extras = intent?.extras?.let { bundle -> bundle.keySet().associateWith { bundle[it] } } ?: emptyMap()
        navRequest.value = NavRequest(navSeq, parseNavigationTarget(extras))
    }

    /**
     * Edge-to-edge: content draws behind both bars (the Scaffold insets it), and
     * the bar icon contrast is forced to match our theme — white icons on dark,
     * black on light. Transparent scrims keep the bars see-through; on API 35+
     * a non-automatic style would otherwise paint its scrim as the bar color.
     */
    private fun applySystemBars(dark: Boolean) {
        // Scrims are ARGB ints; transparent keeps the bars see-through (on API 35+
        // a non-automatic style would otherwise paint its scrim as the bar color).
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) {
            SystemBarStyle.dark(transparent)
        } else {
            SystemBarStyle.light(transparent, transparent)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onStart() {
        super.onStart()
        AppForeground.activityStarted()
        // The chat list already shows received items; dismiss their notifications.
        meshService.value?.clearMessageNotifications()
        // Keep the screen on only while this activity is in the foreground — Wi-Fi
        // Direct drops when the phone sleeps. The mesh itself survives sleep via
        // the foreground service; backgrounding must never stop it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        // A permission can be revoked from system settings while we run; pick that up so the
        // chats empty state can offer its "Grant permission" button again (TASK U3 part 2).
        wifiDirectGranted.value = wifiDirectPermissionsGranted()
    }

    override fun onStop() {
        // Backgrounding never stops the mesh: the foreground service owns it. We
        // only drop keep-screen-on and mark ourselves invisible, so received
        // content may post notifications while we are away.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppForeground.activityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        if (serviceBound) unbindService(serviceConnection)
        uiScope.cancel()
        super.onDestroy()
    }

    /** Starts the foreground mesh service and binds to it. Safe to call twice — the service ignores duplicate starts. */
    private fun startMeshService() {
        val intent = Intent(this, MeshService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /** Requests every runtime permission this device needs; skips the dialog when all are held. */
    private fun requestPermissions() {
        val required = PermissionRequirements.required(Build.VERSION.SDK_INT)
        if (required.all(::isPermissionGranted)) return
        permissionLauncher.launch(required.toTypedArray())
    }

    /** Android 13+: ask for POST_NOTIFICATIONS once, after the Wi-Fi Direct grant. Never blocks the mesh. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) return
        notificationPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** True when every Wi-Fi Direct runtime permission required on this device is held. */
    private fun wifiDirectPermissionsGranted(): Boolean =
        PermissionRequirements.required(Build.VERSION.SDK_INT).all(::isPermissionGranted)

    /** Current grant state of every permission required on this device. */
    private fun permissionStates(): Map<String, Boolean> =
        PermissionRequirements.required(Build.VERSION.SDK_INT).associateWith(::isPermissionGranted)

    /** Writes to the bound service's event log; a no-op before bind (nothing to show yet). */
    private fun log(message: String) { meshService.value?.eventLog?.log(message) }

    /** Tap handler: ask for location permissions first — only now, never at startup. */
    private fun requestLocationShare() {
        // One attempt at a time — the pin is disabled while waiting; this guards
        // against any other path re-entering mid-attempt and stacking requests.
        if (shareStatus.value == LocationShareStatus.WAITING_FOR_FIX) return
        val missing = PermissionRequirements.locationPermissionsForSharing().filterNot(::isPermissionGranted)
        if (missing.isEmpty()) shareMyLocation() else locationPermissionLauncher.launch(missing.toTypedArray())
    }

    /** Waits for a fix (or the debug test point), then floods it as a LOCATION frame. */
    private fun shareMyLocation() {
        log("location: share attempt started (source=${if (useTestLocation.value) "test-location" else "gps"})")
        shareFailureMessage.value = null
        myLatestShareWasCached.value = false
        shareStatus.value = LocationShareStatus.WAITING_FOR_FIX
        // The countdown only makes sense for a real fix wait; the test point is instant.
        shareDeadlineMs.value = if (useTestLocation.value) null else System.currentTimeMillis() + LOCATION_MAX_WAIT_MS
        shareJob = uiScope.launch {
            // Normally set in onServiceConnected before the UI can offer sharing;
            // reset the state instead of leaving a stuck countdown if it is not.
            val provider = locationProvider ?: run {
                shareDeadlineMs.value = null
                shareStatus.value = LocationShareStatus.FAILED
                return@launch
            }
            val fix = try {
                if (useTestLocation.value) {
                    // Debug builds only: a fixed, clearly-labeled point instead of GPS.
                    LocationFix(TEST_LATITUDE_E6, TEST_LONGITUDE_E6, 5, System.currentTimeMillis())
                } else {
                    provider.requestFix()
                }
            } catch (error: CancellationException) {
                throw error // user cancel or activity destroyed — do not swallow it
            } catch (error: Exception) {
                log("location: provider failed: ${error.javaClass.simpleName}: ${error.message}")
                null
            }
            if (fix == null) {
                shareDeadlineMs.value = null
                // The provider already logged "location: failed: <reasons>"; show
                // the same specific text in the UI instead of a generic one.
                shareFailureMessage.value = provider.lastFailureMessage
                    ?: "no usable fix — try again, preferably outdoors"
                shareStatus.value = LocationShareStatus.FAILED
                return@launch
            }
            myLatestShareWasCached.value = !useTestLocation.value && provider.lastFixWasCached
            myLastFix.value = fix
            val sent = meshService.value?.sendLocation(fix.latitudeE6, fix.longitudeE6, fix.accuracyMeters) ?: false
            if (sent) {
                // The outgoing card in the chat list is the success confirmation.
                shareDeadlineMs.value = null
                shareStatus.value = LocationShareStatus.IDLE
            } else {
                log("location: send failed")
                shareDeadlineMs.value = null
                shareStatus.value = LocationShareStatus.FAILED
            }
        }
    }

    /** Cancel button while waiting for a fix: stops the in-flight attempt now. */
    private fun cancelLocationShare() {
        // Cancelling the job unwinds the whole attempt (structured concurrency),
        // which removes any pending location listener; the state is reset here,
        // not inside the cancelled coroutine.
        shareJob?.cancel()
        shareDeadlineMs.value = null
        shareStatus.value = LocationShareStatus.IDLE
        log("location: share cancelled by user")
    }

    /** Debug builds only (offered on a failed share): arm the test point and share it. */
    private fun shareTestLocationInstead() {
        useTestLocation.value = true
        testLocationStore?.save(true)
        log("location: sharing the debug test location instead")
        shareMyLocation()
    }

    /**
     * Location card "Open in maps" (TASK U2 part 1): a geo: intent for any maps app, then a
     * web fallback; if nothing can handle either, copy the coordinates so they are not lost.
     */
    private fun openInMaps(latitudeE6: Int, longitudeE6: Int) {
        val lat = String.format(Locale.US, "%.5f", latitudeE6 / 1_000_000.0)
        val lon = String.format(Locale.US, "%.5f", longitudeE6 / 1_000_000.0)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?z=15")))
            return
        } catch (_: ActivityNotFoundException) {
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps?q=$lat,$lon")))
            return
        } catch (_: ActivityNotFoundException) {
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AirMesh location", "$lat, $lon"))
    }

    companion object {
        /** Fixed point for the debug "test location" toggle (Cairo, Egypt). */
        private const val TEST_LATITUDE_E6 = 30_000_000
        private const val TEST_LONGITUDE_E6 = 31_000_000
    }

    /**
     * Debug builds only: surface future main-thread network/disk violations in
     * logcat (penaltyLog, never penaltyDeath) instead of crashing the app.
     * Both checks live on ThreadPolicy; VmPolicy has no disk-read detector.
     */
    private fun enableStrictModeInDebugBuilds() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectNetwork()
                .detectDiskReads()
                .penaltyLog()
                .build(),
        )
    }
}

/**
 * Shown until the mesh service is bound: a permission button while the Wi-Fi
 * Direct permissions are missing, "starting" while the bind is in flight. The
 * full MeshScreen only exists once we observe the service's flows.
 */
@Composable
private fun ServiceGate(starting: Boolean, onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("AirMesh", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        if (starting) {
            Text(
                "Starting the mesh…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "AirMesh phones talk to each other over Wi-Fi Direct. Grant the permissions below so the mesh can run in the background.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onStart) { Text("Grant permissions & start") }
        }
    }
}

