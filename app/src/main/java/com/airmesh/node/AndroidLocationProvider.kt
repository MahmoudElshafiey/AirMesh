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
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Real [LocationProvider] backed by the platform LocationManager (no Google
 * Play Services).
 *
 * Each share is one independent attempt ([LocationShareAttempt]): both enabled
 * providers are asked for a live fix at once and the first answer wins, so an
 * indoor phone gets a network fix in seconds instead of waiting out the whole
 * GPS timeout. If no live fix arrives within [LOCATION_MAX_WAIT_MS], the newest
 * last-known fix up to [LOCATION_LAST_KNOWN_MAX_AGE_MS] old is shared (flagged
 * as cached). The selection itself lives in the pure, unit-tested
 * [selectLocationFix]; this class only adapts LocationManager.
 */
class AndroidLocationProvider(
    private val context: Context,
    /** Event-log sink for share diagnostics; silent by default (tests). */
    private val log: (String) -> Unit = {},
) : LocationProvider {

    private val attempt = LocationShareAttempt(AndroidSource(context), System::currentTimeMillis, LOCATION_MAX_WAIT_MS, log)

    /** Specific reason of the last failed attempt; null after a success. The UI shows it verbatim. */
    var lastFailureMessage: String? = null
        private set

    /** Whether the last successful attempt used a last-known (cached) fix. */
    var lastFixWasCached: Boolean = false
        private set

    override suspend fun requestFix(): LocationFix? = withContext(Dispatchers.Main) {
        val startedAt = System.currentTimeMillis()
        when (val decision = attempt.run()) {
            is LocationDecision.Use -> {
                lastFailureMessage = null
                lastFixWasCached = decision.cached
                log(useLine(decision, System.currentTimeMillis() - startedAt))
                decision.candidate.toFix()
            }
            is LocationDecision.Fail -> {
                lastFailureMessage = decision.reasons.joinToString(", ")
                log("location: failed: ${lastFailureMessage}")
                null
            }
        }
    }

    private fun useLine(decision: LocationDecision.Use, elapsedMs: Long): String {
        val candidate = decision.candidate
        val age = formatAgeMs(System.currentTimeMillis() - candidate.timeMs)
        return if (decision.cached) {
            "location: used last known ${candidate.source.uppercase()} fix acc=${candidate.accuracyMeters}m" +
                " age=$age (cached) after ${elapsedMs}ms"
        } else {
            "location: used ${candidate.source.uppercase()} fix acc=${candidate.accuracyMeters}m" +
                " age=$age after ${elapsedMs}ms"
        }
    }

    /** [LocationFixSource] over the platform LocationManager. */
    private class AndroidSource(private val context: Context) : LocationFixSource {

        private val manager: LocationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        override fun permissionState(): LocationPermissionState {
            // Precise (fine) is required to share; coarse alone means the user
            // chose "approximate only", which we refuse with a specific message.
            return when {
                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationPermissionState.PRECISE
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationPermissionState.APPROXIMATE_ONLY
                else -> LocationPermissionState.NONE
            }
        }

        /** Location master switch; deprecated since API 30 but the only check without Play Services. */
        @Suppress("DEPRECATION")
        override fun locationServicesEnabled(): Boolean =
            runCatching { manager.isLocationEnabled }.getOrDefault(false)

        override fun providerEnabled(provider: String): Boolean =
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)

        override suspend fun awaitLiveFix(provider: String): LocationCandidate? =
            awaitSingleUpdate(manager, provider)

        override fun lastKnownFix(provider: String): LocationCandidate? =
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { toCandidate(provider, it) }

        /** Waits for one fix from [provider]; the attempt's deadline cancels us. */
        private suspend fun awaitSingleUpdate(manager: LocationManager, provider: String): LocationCandidate? =
            suspendCancellableCoroutine { continuation ->
                // Single-shot: the listener must unregister itself on success — the old
                // code only removed it on timeout/cancel, leaking one GPS registration
                // after every successful share. A local val cannot reference its own
                // initializer, so it holds a self-reference in [self].
                var self: LocationListener? = null
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) {
                            self?.let { runCatching { manager.removeUpdates(it) } }
                            continuation.resume(toCandidate(provider, location))
                        }
                    }
                }
                self = listener
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
                    // Provider not available on this device.
                    if (continuation.isActive) continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                // Timeout / user-cancel path — removeUpdates is a no-op when the fix
                // already unregistered itself, so both paths are safe to run.
                continuation.invokeOnCancellation {
                    runCatching { manager.removeUpdates(listener) }
                }
            }

        private fun hasPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        private fun toCandidate(provider: String, location: Location): LocationCandidate {
            // accuracy is a Float in meters; NaN means "unknown" — report 0 then.
            val rawAccuracy = location.accuracy
            return LocationCandidate(
                source = provider,
                latitudeE6 = degreesToE6(location.latitude),
                longitudeE6 = degreesToE6(location.longitude),
                accuracyMeters = if (rawAccuracy.isFinite()) rawAccuracy.roundToInt() else 0,
                timeMs = location.time,
            )
        }
    }
}
