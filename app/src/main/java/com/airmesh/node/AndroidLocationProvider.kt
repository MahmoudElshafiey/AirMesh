package com.airmesh.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.airmesh.protocol.degreesToE6
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Real [LocationProvider] backed by the platform LocationManager (no Google
 * Play Services).
 *
 * Strategy: wait up to [LOCATION_MAX_WAIT_MS] for a live GPS fix; if that
 * times out, fall back to the newest last-known fix — network provider first
 * because it fixes indoors, then GPS — provided it is younger than
 * [LOCATION_STALE_AFTER_MS]. Indoors GPS may never fix, so null is a normal
 * outcome and callers must show a failure message.
 */
class AndroidLocationProvider(private val context: Context) : LocationProvider {

    override suspend fun requestFix(): LocationFix? = withContext(Dispatchers.Main) {
        // LocationManager calls throw SecurityException without a location
        // permission; the UI only calls us after granting, but stay defensive.
        if (!hasLocationPermission()) return@withContext null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 1) Prefer a live GPS fix (most accurate source we have).
        runCatching { awaitSingleUpdate(manager, LocationManager.GPS_PROVIDER) }
            .getOrNull()
            ?.let { return@withContext toFix(it) }

        // 2) Fallback: the newest last-known fix that is still fresh.
        val candidates = listOf(
            manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER),
        ).filterNotNull().filter { isFresh(it) }
        candidates.maxByOrNull { it.time }?.let { return@withContext toFix(it) }

        null
    }

    /** Waits up to [LOCATION_MAX_WAIT_MS] for one fix from [provider]. */
    private suspend fun awaitSingleUpdate(manager: LocationManager, provider: String): Location? =
        withTimeoutOrNull(LOCATION_MAX_WAIT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) continuation.resume(location)
                    }
                }
                try {
                    // requestSingleUpdate is the simplest single-shot API and is
                    // still supported; it needs a Looper, hence Dispatchers.Main.
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (error: SecurityException) {
                    // Permission revoked between the check above and this call.
                    if (continuation.isActive) continuation.resume(null)
                    return@suspendCancellableCoroutine
                } catch (error: IllegalArgumentException) {
                    // GPS provider not available on this device.
                    if (continuation.isActive) continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    runCatching { manager.removeUpdates(listener) }
                }
            }
        }

    private fun isFresh(location: Location): Boolean =
        System.currentTimeMillis() - location.time < LOCATION_STALE_AFTER_MS

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun toFix(location: Location): LocationFix {
        // accuracy is a Float in meters; NaN means "unknown" — report 0 then.
        val rawAccuracy = location.accuracy
        return LocationFix(
            latitudeE6 = degreesToE6(location.latitude),
            longitudeE6 = degreesToE6(location.longitude),
            accuracyMeters = if (rawAccuracy.isFinite()) rawAccuracy.roundToInt() else 0,
            timeMs = location.time,
        )
    }
}
