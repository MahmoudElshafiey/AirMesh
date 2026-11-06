package com.airmesh.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.StrictMode
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.airmesh.node.AirMeshNode
import com.airmesh.node.EventLog
import com.airmesh.node.NodeIdProvider
import com.airmesh.node.SharedPreferencesNodeIdStore
import com.airmesh.node.createAirMeshNode
import com.airmesh.transport.PrototypeActivity
import com.airmesh.transport.WifiDirectConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Requests every runtime permission AirMesh needs on this device at once. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        connector.onPermissionResults(results)
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
        connector.onPermissionResults(permissionStates())

        setContent {
            val connectorState by connector.state.collectAsState()
            val neighbors by node.neighbors.collectAsState()
            val messages by node.messages.collectAsState()
            val events by eventLog.entries.collectAsState()

            MaterialTheme {
                MeshScreen(
                    nodeId = nodeId,
                    connectorState = connectorState,
                    neighbors = neighbors,
                    messages = messages,
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
                    onClearLog = eventLog::clear,
                    onOpenPrototype = {
                        startActivity(Intent(this, PrototypeActivity::class.java))
                    },
                )
            }
        }
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
