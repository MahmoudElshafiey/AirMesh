package com.airmesh.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.airmesh.node.AndroidLocationProvider
import com.airmesh.node.LOCATION_MAX_WAIT_MS
import com.airmesh.node.LocationFix
import com.airmesh.node.SharedPreferencesTestLocationStore
import com.airmesh.node.TestLocationStore
import com.airmesh.service.AppForeground
import com.airmesh.service.MeshService
import com.airmesh.transport.PermissionRequirements
import com.airmesh.transport.PrototypeActivity
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
                        val connectorState by service.connectorState.collectAsState()
                        val neighbors by service.neighbors.collectAsState()
                        val messages by service.messages.collectAsState()
                        val locations by service.locations.collectAsState()
                        val myProfile by service.myProfile.collectAsState()
                        val profiles by service.profiles.collectAsState()
                        val events by service.events.collectAsState()
                        val messageNotificationsEnabled by service.messageNotificationsEnabled.collectAsState()
                        val autoMeshState by service.autoMeshState.collectAsState()
                        val autoIdleReason by service.autoIdleReason.collectAsState()
                        val airMeshPeers by service.airMeshPeers.collectAsState()
                        val autoMeshEnabled by service.autoMeshEnabled.collectAsState()
                        val discoveryStrategy by service.discoveryStrategy.collectAsState()
                        val rawCallbackCount by service.rawCallbackCount.collectAsState()

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
                            autoMeshState = autoMeshState,
                            autoIdleReason = autoIdleReason,
                            airMeshPeers = airMeshPeers,
                            autoMeshEnabled = autoMeshEnabled,
                            onSetAutoMeshEnabled = service::setAutoMeshEnabled,
                            discoveryStrategy = discoveryStrategy,
                            rawCallbackCount = rawCallbackCount,
                            myProfile = myProfile,
                            profiles = profiles,
                            onSaveProfile = { name, colorIndex ->
                                uiScope.launch { service.setMyProfile(name, colorIndex) }
                            },
                            // First run only: a stored name means the user already
                            // answered (or saved) once, even after an app update.
                            promptOnboarding = !onboardingStore.isDone() && myProfile.name == null,
                            onOnboardingAnswered = { onboardingStore.markDone() },
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

