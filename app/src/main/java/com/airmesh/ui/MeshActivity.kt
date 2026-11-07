package com.airmesh.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.StrictMode
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.airmesh.node.AirMeshNode
import com.airmesh.node.AndroidLocationProvider
import com.airmesh.node.EventLog
import com.airmesh.node.LOCATION_MAX_WAIT_MS
import com.airmesh.node.LocationFix
import com.airmesh.node.NodeIdProvider
import com.airmesh.node.SharedPreferencesNodeIdStore
import com.airmesh.node.SharedPreferencesTestLocationStore
import com.airmesh.node.TestLocationStore
import com.airmesh.node.createAirMeshNode
import com.airmesh.transport.PermissionRequirements
import com.airmesh.transport.PrototypeActivity
import com.airmesh.transport.WifiDirectConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Real mesh UI. Kept separate from the throwaway [PrototypeActivity] so the
 * prototype keeps working as a fallback and can be opened from a button.
 */
class MeshActivity : ComponentActivity() {

    private lateinit var eventLog: EventLog
    private lateinit var node: AirMeshNode
    private lateinit var connector: WifiDirectConnector
    private lateinit var themeStore: ThemeModeStore
    private lateinit var locationProvider: AndroidLocationProvider

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

    /** Requests every runtime permission AirMesh needs on this device at once. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        connector.onPermissionResults(results)
    }

    /** Location permissions are requested only when the user taps "Share my location". */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Same per-permission lines as the Wi-Fi Direct connector, so a denied
        // dialog is visible in the event log.
        results.forEach { (permission, granted) ->
            eventLog.log("runtime permission ${permission.substringAfterLast('.')} granted=$granted")
        }
        if (PermissionRequirements.locationPermissionsForSharing().all(::isPermissionGranted)) {
            shareMyLocation()
        } else {
            eventLog.log("location: permission not granted — cannot share")
            shareStatus.value = LocationShareStatus.FAILED
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableStrictModeInDebugBuilds()

        // Wi-Fi Direct drops when the phone sleeps, so keep the screen on while
        // this activity is in the foreground.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        eventLog = EventLog()
        val nodeId = NodeIdProvider(SharedPreferencesNodeIdStore(this)).getOrCreate()
        node = createAirMeshNode(this, nodeId, eventLog)
        connector = WifiDirectConnector(this, node.transport, eventLog)
        themeStore = SharedPreferencesThemeModeStore(this)
        // Every phase of a share attempt lands in the event log.
        locationProvider = AndroidLocationProvider(this, eventLog::log)
        isDebugBuild = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        testLocationStore = if (isDebugBuild) SharedPreferencesTestLocationStore(applicationContext) else null
        // The debug switch survives process death; release builds stay off.
        useTestLocation.value = testLocationStore?.load() ?: false
        connector.onPermissionResults(permissionStates())

        setContent {
            val connectorState by connector.state.collectAsState()
            val neighbors by node.neighbors.collectAsState()
            val messages by node.messages.collectAsState()
            val locations by node.locations.collectAsState()
            val events by eventLog.entries.collectAsState()
            // remember (not a bare mutableStateOf): the setContent body re-runs on
            // every recomposition, and the choice must survive it. Rotation is
            // covered because the store persists the value across process death.
            val themeMode = remember { mutableStateOf(themeStore.load()) }
            val dark = resolveDarkTheme(themeMode.value, isSystemInDarkTheme())

            AirMeshTheme(darkTheme = dark) {
                MeshScreen(
                    nodeId = nodeId,
                    connectorState = connectorState,
                    neighbors = neighbors,
                    messages = messages,
                    locations = locations,
                    myLastFix = myLastFix.value,
                    shareStatus = shareStatus.value,
                    events = events,
                    onRequestPermissions = ::requestPermissions,
                    onDiscover = connector::discover,
                    onCreateGroup = connector::createGroup,
                    onRetryTcp = connector::retryConnect,
                    onDisconnect = connector::disconnect,
                    onConnectPeer = connector::connect,
                    onSendText = { text ->
                        uiScope.launch { node.sendText(text) }
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
                    onClearLog = eventLog::clear,
                    onOpenPrototype = {
                        startActivity(Intent(this, PrototypeActivity::class.java))
                    },
                    themeMode = themeMode.value,
                    // Applies immediately (state change recomposes) and persists.
                    onSetThemeMode = { mode ->
                        themeMode.value = mode
                        themeStore.save(mode)
                    },
                )
            }

            // Re-apply the system bar styles whenever the effective theme flips,
            // so status/navigation icons stay readable in both themes.
            LaunchedEffect(dark) { applySystemBars(dark) }
        }
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
        node.start()
        connector.start()
    }

    override fun onStop() {
        connector.stop()
        node.stop()
        super.onStop()
    }

    override fun onDestroy() {
        connector.destroy()
        node.close()
        uiScope.cancel()
        super.onDestroy()
    }

    /** Requests every runtime permission this device needs; skips the dialog when all are held. */
    private fun requestPermissions() {
        val required = connector.requiredPermissions()
        if (required.all(::isPermissionGranted)) {
            // Nothing to ask for: just refresh the UI state without a dialog.
            connector.onPermissionResults(permissionStates())
            return
        }
        permissionLauncher.launch(required.toTypedArray())
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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
        eventLog.log("location: share attempt started (source=${if (useTestLocation.value) "test-location" else "gps"})")
        shareFailureMessage.value = null
        myLatestShareWasCached.value = false
        shareStatus.value = LocationShareStatus.WAITING_FOR_FIX
        // The countdown only makes sense for a real fix wait; the test point is instant.
        shareDeadlineMs.value = if (useTestLocation.value) null else System.currentTimeMillis() + LOCATION_MAX_WAIT_MS
        shareJob = uiScope.launch {
            val fix = try {
                if (useTestLocation.value) {
                    // Debug builds only: a fixed, clearly-labeled point instead of GPS.
                    LocationFix(TEST_LATITUDE_E6, TEST_LONGITUDE_E6, 5, System.currentTimeMillis())
                } else {
                    locationProvider.requestFix()
                }
            } catch (error: CancellationException) {
                throw error // user cancel or activity destroyed — do not swallow it
            } catch (error: Exception) {
                eventLog.log("location: provider failed: ${error.javaClass.simpleName}: ${error.message}")
                null
            }
            if (fix == null) {
                shareDeadlineMs.value = null
                // The provider already logged "location: failed: <reasons>"; show
                // the same specific text in the UI instead of a generic one.
                shareFailureMessage.value = locationProvider.lastFailureMessage
                    ?: "no usable fix — try again, preferably outdoors"
                shareStatus.value = LocationShareStatus.FAILED
                return@launch
            }
            myLatestShareWasCached.value = !useTestLocation.value && locationProvider.lastFixWasCached
            myLastFix.value = fix
            if (node.sendLocation(fix.latitudeE6, fix.longitudeE6, fix.accuracyMeters)) {
                // The outgoing card in the chat list is the success confirmation.
                shareDeadlineMs.value = null
                shareStatus.value = LocationShareStatus.IDLE
            } else {
                eventLog.log("location: send failed")
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
        eventLog.log("location: share cancelled by user")
    }

    /** Debug builds only (offered on a failed share): arm the test point and share it. */
    private fun shareTestLocationInstead() {
        useTestLocation.value = true
        testLocationStore?.save(true)
        eventLog.log("location: sharing the debug test location instead")
        shareMyLocation()
    }

    companion object {
        /** Fixed point for the debug "test location" toggle (Cairo, Egypt). */
        private const val TEST_LATITUDE_E6 = 30_000_000
        private const val TEST_LONGITUDE_E6 = 31_000_000
    }

    /** Current grant state of every permission required on this device. */
    private fun permissionStates(): Map<String, Boolean> =
        connector.requiredPermissions().associateWith(::isPermissionGranted)

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
