package com.airmesh.transport

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * THROWAWAY PROTOTYPE — hosts the Wi-Fi Direct + TCP experiment.
 * No protocol integration on purpose.
 */
class PrototypeActivity : ComponentActivity() {

    private lateinit var prototype: WifiDirectPrototype

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            prototype.onPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prototype = WifiDirectPrototype(this)
        prototype.onPermissionResult(hasP2pPermission())
        setContent {
            PrototypeScreen(
                prototype = prototype,
                onRequestPermissions = { requestP2pPermission() },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        prototype.start()
    }

    override fun onStop() {
        prototype.stop()
        super.onStop()
    }

    override fun onDestroy() {
        prototype.destroy()
        super.onDestroy()
    }

    private fun requestP2pPermission() {
        if (hasP2pPermission()) {
            prototype.onPermissionResult(true)
            return
        }
        permissionLauncher.launch(prototype.requiredPermission())
    }

    private fun hasP2pPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, prototype.requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED
}
