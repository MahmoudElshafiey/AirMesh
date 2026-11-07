package com.airmesh.node

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake [LocationFixSource] that records every call so tests can assert each
 * share attempt makes fresh requests and cleans up on timeout/cancel.
 */
private class FakeFixSource : LocationFixSource {
    var permission = LocationPermissionState.PRECISE
    var servicesEnabled = true
    var gpsEnabled = true
    var networkEnabled = true

    /** What [awaitLiveFix] returns per provider; may suspend (simulates "no signal"). */
    var gpsResult: suspend () -> LocationCandidate? = { null }
    var networkResult: suspend () -> LocationCandidate? = { null }
    var lastKnownGps: LocationCandidate? = null
    var lastKnownNetwork: LocationCandidate? = null

    var gpsRequests = 0
        private set
    var networkRequests = 0
        private set

    override fun permissionState() = permission

    override fun locationServicesEnabled() = servicesEnabled

    override fun providerEnabled(provider: String) = when (provider) {
        LOCATION_GPS_PROVIDER -> gpsEnabled
        LOCATION_NETWORK_PROVIDER -> networkEnabled
        else -> false
    }

    override suspend fun awaitLiveFix(provider: String): LocationCandidate? =
        if (provider == LOCATION_GPS_PROVIDER) {
            gpsRequests++
            gpsResult()
        } else {
            networkRequests++
            networkResult()
        }

    override fun lastKnownFix(provider: String) = when (provider) {
        LOCATION_GPS_PROVIDER -> lastKnownGps
        LOCATION_NETWORK_PROVIDER -> lastKnownNetwork
        else -> null
    }
}

/**
 * Attempt pipeline on virtual time: the 45 s deadline is a real value, but
 * runTest's scheduler advances it instantly. The fake clock tracks the
 * scheduler so waitedMs/ages are meaningful in assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class) // TestScope.scheduler / advanceTimeBy
class LocationShareAttemptTest {

    private val baseTime = 1_000_000L

    private fun gpsFix(timeMs: Long) =
        LocationCandidate(LOCATION_GPS_PROVIDER, 30_000_000, 31_000_000, 8, timeMs)

    @Test
    fun `two consecutive shares both succeed with fresh requests`() = runTest {
        val source = FakeFixSource()
        // GPS fixes immediately (2 s old), every time.
        source.gpsResult = { gpsFix(baseTime + testScheduler.currentTime - 2_000L) }
        val attempt = LocationShareAttempt(source, { baseTime + testScheduler.currentTime })

        val first = attempt.run()
        val second = attempt.run()

        assertEquals(LocationDecision.Use(gpsFix(baseTime - 2_000L), cached = false), first)
        assertEquals(LocationDecision.Use(gpsFix(baseTime - 2_000L), cached = false), second)
        // A fresh request per share — no sticky state from the first attempt.
        assertEquals(2, source.gpsRequests)
    }

    @Test
    fun `second share succeeds after a timed-out first attempt`() = runTest {
        val source = FakeFixSource()
        var pendingWaitCancelled = false
        // GPS "never fixes": the wait suspends until the deadline cancels it.
        source.gpsResult = {
            suspendCancellableCoroutine<LocationCandidate?> { continuation ->
                continuation.invokeOnCancellation { pendingWaitCancelled = true }
            }
        }
        val attempt = LocationShareAttempt(source, { baseTime + testScheduler.currentTime })

        val firstDeferred = async { attempt.run() }
        advanceTimeBy(LOCATION_MAX_WAIT_MS + 1_000L)
        val first = firstDeferred.await()

        assertTrue(first is LocationDecision.Fail)
        assertEquals(
            listOf("no GPS signal in 45s — try near a window or outside", "no recent network or GPS fix"),
            (first as LocationDecision.Fail).reasons,
        )
        // The timed-out live request was actually cancelled — no leaked wait.
        assertTrue(pendingWaitCancelled)

        // Second attempt: GPS now fixes immediately and the share succeeds.
        source.gpsResult = { gpsFix(baseTime + testScheduler.currentTime - 1_000L) }
        val second = attempt.run()
        assertEquals(
            LocationDecision.Use(gpsFix(baseTime + LOCATION_MAX_WAIT_MS), cached = false),
            second,
        )
    }

    @Test
    fun `first live fix ends the wait and cancels the other provider`() = runTest {
        val source = FakeFixSource()
        var networkWaitCancelled = false
        // GPS fixes after 5 s of virtual time; network never answers.
        source.gpsResult = {
            delay(5_000L)
            gpsFix(baseTime + testScheduler.currentTime - 5_000L)
        }
        source.networkResult = {
            suspendCancellableCoroutine<LocationCandidate?> { continuation ->
                continuation.invokeOnCancellation { networkWaitCancelled = true }
            }
        }
        val attempt = LocationShareAttempt(source, { baseTime + testScheduler.currentTime })

        val decision = attempt.run()

        assertEquals(LocationDecision.Use(gpsFix(baseTime), cached = false), decision)
        // Structured concurrency cancelled the losing provider's pending wait.
        assertTrue(networkWaitCancelled)
    }

    @Test
    fun `after both providers give up the last known fix is shared as cached`() = runTest {
        val source = FakeFixSource()
        // Both live waits end without a fix (immediately here — same code path
        // as a timeout, just faster).
        source.gpsResult = { null }
        source.networkResult = { null }
        source.lastKnownNetwork = LocationCandidate(
            LOCATION_NETWORK_PROVIDER, 30_000_100, 31_000_100, 35, baseTime - 3 * 60_000L,
        )
        val attempt = LocationShareAttempt(source, { baseTime })

        val decision = attempt.run()

        assertEquals(LocationDecision.Use(source.lastKnownNetwork!!, cached = true), decision)
    }

    @Test
    fun `location services off fails fast without any live request`() = runTest {
        val source = FakeFixSource()
        source.servicesEnabled = false
        val attempt = LocationShareAttempt(source, { baseTime })

        // Returns immediately; a hang would fail the test via its real-time budget.
        val decision = attempt.run()

        assertEquals(LocationDecision.Fail(listOf("location services are turned off")), decision)
        assertEquals(0, source.gpsRequests + source.networkRequests)
    }
}
