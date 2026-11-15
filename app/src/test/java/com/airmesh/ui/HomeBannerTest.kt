package com.airmesh.ui

import com.airmesh.transport.AutoMeshState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the single home banner mapping (TASK U4 part 2). */
class HomeBannerTest {

    private val searching = AutoMeshState.Searching(sinceMs = 0L)
    private val yielding = AutoMeshState.Yielding(
        targetAddress = "192.168.49.1", targetNodeId = 7, targetPhones = 3, sinceMs = 0L,
    )

    // --- Non-actionable states never get a banner ---------------------------------

    @Test
    fun searchingShowsNoBannerEvenWithClassicDevicesAndALongWait() {
        // The old "found N devices but none advertises AirMesh" hint is gone from home:
        // its content now lives in the empty-state caption and the manual-connect button.
        for (devices in intArrayOf(0, 1, 5)) {
            for (waitMs in longArrayOf(0L, 45_000L, HOME_MANUAL_BUTTON_AFTER_MS + 30_000L)) {
                assertNull(homeBannerText(searching, true, devices, null, waitMs, false))
            }
        }
    }

    @Test
    fun creatingJoiningOwnerClientAndIdleShowNoBanner() {
        val states = listOf<AutoMeshState>(
            AutoMeshState.Creating,
            AutoMeshState.Joining("192.168.49.1", 42, 0L),
            AutoMeshState.Owner,
            AutoMeshState.Client,
            AutoMeshState.Idle,
        )
        for (status in states) {
            assertNull(homeBannerText(status, true, 2, "some idle reason", 0L, false))
        }
    }

    // --- The two always-allowed banners -------------------------------------------

    @Test
    fun yieldingShowsTheMergeLine() {
        val text = homeBannerText(yielding, true, 2, null, 0L, emptyStateVisible = true)
        assertEquals("Merging with a nearby network (#7, 3 phones)…", text)
    }

    @Test
    fun backoffShowsTheRetryCountdown() {
        val now = 1_000_000L
        val until = now + 5_000L
        assertEquals(
            "Retrying in 6s — join failed",
            homeBannerText(AutoMeshState.Backoff(untilMs = until, reason = "join failed"), true, 0, null, now, false),
        )
    }

    @Test
    fun backoffCountdownNeverGoesBelowOneSecond() {
        // The deadline already passed: the frozen helper clamps to 1 s.
        val text = homeBannerText(
            AutoMeshState.Backoff(untilMs = 0L, reason = "x"), true, 0, null, nowMs = 5_000L, emptyStateVisible = false,
        )
        assertEquals("Retrying in 1s — x", text)
    }

    // --- Missing permissions: the banner only speaks when the empty state is absent --

    @Test
    fun missingPermissionsWithEmptyStateVisibleShowsNoBanner() {
        // The empty state already shows the headline and a Grant button; a banner would repeat it.
        assertNull(homeBannerText(searching, permissionOk = false, neighborCount = 0, idleReason = null, nowMs = 0L, emptyStateVisible = true))
    }

    @Test
    fun missingPermissionsWithoutEmptyStateShowsThePermissionLine() {
        // With conversations on screen the banner is the only signal left. It reuses the
        // frozen empty-state wording; the two are never on screen at once.
        val text = homeBannerText(AutoMeshState.Idle, permissionOk = false, neighborCount = 0, idleReason = null, nowMs = 0L, emptyStateVisible = false)
        assertEquals("Wi-Fi Direct permissions are missing", text)
        assertEquals(emptyStateText(EmptyStateKind.PERMISSION_MISSING, null), text)
    }

    @Test
    fun permissionOutranksTheMergeLine() {
        val text = homeBannerText(yielding, permissionOk = false, neighborCount = 2, idleReason = null, nowMs = 0L, emptyStateVisible = false)
        assertEquals("Wi-Fi Direct permissions are missing", text)
    }

    // --- Invariants -----------------------------------------------------------------

    @Test
    fun noBannerCarriesTheTechnicalDiscoveryLine() {
        // The "Discovery strategy S, raw callbacks R" line belongs to the Network tab's Debug
        // card (debuggable builds only), never to a home banner.
        val states = listOf<AutoMeshState>(
            searching, AutoMeshState.Creating, yielding,
            AutoMeshState.Joining("192.168.49.1", 42, 0L),
            AutoMeshState.Owner, AutoMeshState.Client, AutoMeshState.Idle,
            AutoMeshState.Backoff(untilMs = 5_000L, reason = "x"),
        )
        for (status in states) {
            for (permissionOk in booleanArrayOf(true, false)) {
                for (emptyStateVisible in booleanArrayOf(true, false)) {
                    val text = homeBannerText(status, permissionOk, 3, "idle", 45_000L, emptyStateVisible) ?: continue
                    assertFalse("banner carries a second line: $text", '\n' in text)
                    assertFalse("banner leaks diagnostics: $text", "Discovery strategy" in text)
                }
            }
        }
    }

    @Test
    fun theBannerNeverRepeatsTheEmptyStateHeadline() {
        // For every state where a banner exists, its text must differ from what the empty
        // state shows at the same moment — each message appears exactly once on screen.
        val states = listOf<AutoMeshState>(searching, yielding, AutoMeshState.Backoff(untilMs = 5_000L, reason = "x"))
        for (status in states) {
            val empty = homeEmptyState(status, otherDevicesFound = 1, searchingForMs = 45_000L, permissionOk = true)
            for (emptyStateVisible in booleanArrayOf(true, false)) {
                val banner = homeBannerText(status, true, 2, null, 45_000L, emptyStateVisible) ?: continue
                assertFalse("banner repeats the headline: $banner", banner == empty.headline)
                assertFalse("banner repeats the caption: $banner", banner == empty.caption)
            }
        }
    }
}
