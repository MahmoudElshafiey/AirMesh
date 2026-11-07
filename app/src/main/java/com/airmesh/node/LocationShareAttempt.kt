package com.airmesh.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Platform hooks one share attempt needs. [AndroidLocationProvider] implements
 * this over LocationManager; unit tests use a fake, so the whole attempt
 * pipeline (concurrency, timeout, cleanup) is testable on the JVM.
 */
interface LocationFixSource {
    fun permissionState(): LocationPermissionState

    fun locationServicesEnabled(): Boolean

    fun providerEnabled(provider: String): Boolean

    /**
     * Wait for one live fix from [provider]; null when none arrives before the
     * caller's deadline cancels this call. Implementations must release their
     * platform resources (e.g. removeUpdates) on cancellation as well as success.
     */
    suspend fun awaitLiveFix(provider: String): LocationCandidate?

    /** The provider's last stored fix, or null when it never fixed. */
    fun lastKnownFix(provider: String): LocationCandidate?
}

/**
 * One independent "share my location" attempt over a [LocationFixSource].
 *
 * Both enabled providers are asked for a live fix at once and the first answer
 * ends the wait — so an indoor phone gets a network fix in seconds instead of
 * waiting out the whole GPS timeout. When neither answers within [maxWaitMs],
 * last-known fixes decide via [selectLocationFix]. Every exit path (fix,
 * timeout, user cancellation) leaves no live request behind: the losing
 * provider is cancelled as soon as a fix arrives, the deadline or a user cancel
 * cancels everything still pending, and each source call removes its listener.
 */
class LocationShareAttempt(
    private val source: LocationFixSource,
    /** Injectable clock so tests run on a fake time base. */
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxWaitMs: Long = LOCATION_MAX_WAIT_MS,
    /** Optional sink for per-phase diagnostics; silent by default (tests). */
    private val log: (String) -> Unit = {},
) {

    suspend fun run(): LocationDecision {
        val startedAt = clock()
        val permission = source.permissionState()
        val servicesOn = source.locationServicesEnabled()
        val gpsOn = source.providerEnabled(LOCATION_GPS_PROVIDER)
        val networkOn = source.providerEnabled(LOCATION_NETWORK_PROVIDER)

        log(
            "location: preflight — permission=${permissionLabel(permission)}, services=" +
                "${if (servicesOn) "on" else "off"}, gps=${if (gpsOn) "enabled" else "disabled"}, " +
                "network=${if (networkOn) "enabled" else "disabled"}",
        )

        // Permission and the master switch gate everything: without them the
        // platform calls would throw anyway, so fail fast with a specific reason.
        if (permission != LocationPermissionState.PRECISE || !servicesOn) {
            return selectLocationFix(
                LocationAttemptSnapshot(
                    nowMs = startedAt,
                    permission = permission,
                    locationServicesEnabled = servicesOn,
                ),
            )
        }

        // Ask both enabled providers at once; the first live fix wins and the
        // other request is cancelled on scope exit (structured concurrency).
        val winner: Pair<String, LocationCandidate>? = withTimeoutOrNull(maxWaitMs) {
            coroutineScope {
                val firstFix = Channel<Pair<String, LocationCandidate>>(Channel.UNLIMITED)
                val jobs = buildList {
                    if (gpsOn) add(launch { safeAwait(LOCATION_GPS_PROVIDER)?.let { firstFix.send(LOCATION_GPS_PROVIDER to it) } })
                    if (networkOn) add(launch { safeAwait(LOCATION_NETWORK_PROVIDER)?.let { firstFix.send(LOCATION_NETWORK_PROVIDER to it) } })
                }
                // When every request finishes without a fix, close the channel so
                // the receive below returns instead of waiting past the timeout.
                if (jobs.isNotEmpty()) launch { jobs.forEach { it.join() }; firstFix.close() }

                val w = if (jobs.isEmpty()) null else firstFix.receiveCatching().getOrNull()
                // The winner is in hand: cancel the losing providers' pending waits so
                // this scope can exit now instead of waiting out the whole deadline.
                jobs.forEach { it.cancel() }
                w?.let { (provider, fix) ->
                    log(
                        "location: $provider fix acc=${fix.accuracyMeters}m" +
                            " age=${formatAgeMs(clock() - fix.timeMs)} after ${clock() - startedAt}ms",
                    )
                }
                w
            }
        }

        val nowMs = clock()
        if (winner == null) {
            log("location: no live fix in ${maxWaitMs / 1000}s — checking last known fixes")
        }
        return selectLocationFix(
            LocationAttemptSnapshot(
                nowMs = nowMs,
                waitedMs = nowMs - startedAt,
                permission = permission,
                locationServicesEnabled = servicesOn,
                gpsProviderEnabled = gpsOn,
                networkProviderEnabled = networkOn,
                liveGps = winner?.takeIf { it.first == LOCATION_GPS_PROVIDER }?.second,
                liveNetwork = winner?.takeIf { it.first == LOCATION_NETWORK_PROVIDER }?.second,
                lastKnownGps = source.lastKnownFix(LOCATION_GPS_PROVIDER),
                lastKnownNetwork = source.lastKnownFix(LOCATION_NETWORK_PROVIDER),
            ),
        )
    }

    /** One provider crashing must not kill the whole attempt. */
    private suspend fun safeAwait(provider: String): LocationCandidate? = try {
        source.awaitLiveFix(provider)
    } catch (error: CancellationException) {
        throw error // deadline or user cancel — propagate, never swallow
    } catch (error: Exception) {
        null
    }

    private fun permissionLabel(permission: LocationPermissionState): String = when (permission) {
        LocationPermissionState.NONE -> "none"
        LocationPermissionState.APPROXIMATE_ONLY -> "coarse"
        LocationPermissionState.PRECISE -> "fine"
    }
}
