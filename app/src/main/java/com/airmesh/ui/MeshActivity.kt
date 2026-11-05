package com.airmesh.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            connector.onPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wi-Fi Direct drops when the phone sleeps, so keep the screen on while
        // this activity is in the foreground.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        eventLog = EventLog()
        val nodeId = NodeIdProvider(SharedPreferencesNodeIdStore(this)).getOrCreate()
        node = createAirMeshNode(this, nodeId, eventLog)
        connector = WifiDirectConnector(this, node.transport, eventLog)
        connector.onPermissionResult(hasP2pPermission())

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
                    onRequestPermissions = ::requestP2pPermission,
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

    private fun requestP2pPermission() {
        if (hasP2pPermission()) {
            connector.onPermissionResult(true)
            return
        }
        permissionLauncher.launch(connector.requiredPermission())
    }

    private fun hasP2pPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, connector.requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED
}
