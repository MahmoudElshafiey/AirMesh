package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the post-loss freshness rules (TASK R3 part 2): plain clock values, no Android. */
class FreshnessRulesTest {

    private fun peer(seenAtMs: Long = 0L) =
        AirMeshPeer("AA:00:00:00:00:07", 7, true, 0x2000, lastSeenMs = seenAtMs)

    // --- owner advert gate ---------------------------------------------------------

    @Test
    fun withoutAnyLossEveryOwnerAdvertIsTrusted() {
        assertTrue(FreshnessRules.ownerAdvertTrustedForJoin(peer(seenAtMs = 1_000), nowMs = 90_000, unexpectedLossAtMs = null))
    }

    @Test
    fun anAdvertSeenBeforeTheLossIsNotTrustedUntilReconfirmed() {
        val lossAt = 30_000L
        assertFalse(FreshnessRules.ownerAdvertTrustedForJoin(peer(seenAtMs = 29_999), nowMs = 31_000, unexpectedLossAtMs = lossAt))
    }

    @Test
    fun anAdvertReconfirmedAfterTheLossIsTrusted() {
        val lossAt = 30_000L
        // Exactly at the loss instant counts as re-confirmed (a new scan ran).
        assertTrue(FreshnessRules.ownerAdvertTrustedForJoin(peer(seenAtMs = 30_000), nowMs = 31_000, unexpectedLossAtMs = lossAt))
        assertTrue(FreshnessRules.ownerAdvertTrustedForJoin(peer(seenAtMs = 45_000), nowMs = 46_000, unexpectedLossAtMs = lossAt))
    }

    @Test
    fun anAdvertOlderThan30sIsNotTrustedEvenAfterTheLoss() {
        val lossAt = 30_000L
        assertTrue(FreshnessRules.ownerAdvertTrustedForJoin(peer(seenAtMs = 40_000), nowMs = 70_000, unexpectedLossAtMs = lossAt)) // exactly 30 s: still trusted
        assertFalse(FreshnessRules.ownerAdvertTrustedForJoin(peer(seenAtMs = 40_000), nowMs = 70_001, unexpectedLossAtMs = lossAt)) // one ms past: stale
    }

    @Test
    fun anUnstampedPeerIsNeverGated() {
        val lossAt = 30_000L
        assertTrue(FreshnessRules.ownerAdvertTrustedForJoin(peer(seenAtMs = 0), nowMs = 120_000, unexpectedLossAtMs = lossAt))
    }

    // --- peer table ageing ---------------------------------------------------------

    @Test
    fun theStaleThresholdDependsOnOurGroupPresence() {
        assertEquals(PEER_STALE_MS, FreshnessRules.peerStaleThresholdMs(true))
        assertEquals(FreshnessRules.PEER_STALE_NO_GROUP_MS, FreshnessRules.peerStaleThresholdMs(false))
        assertEquals(60_000L, FreshnessRules.PEER_STALE_NO_GROUP_MS)
    }

    @Test
    fun peerExpiryUsesTheGroupAwareThreshold() {
        // With a group: 120 s. Exactly at the threshold is kept; one ms past expires.
        assertFalse(FreshnessRules.shouldExpirePeerNow(39_000, 158_999, true))
        assertFalse(FreshnessRules.shouldExpirePeerNow(39_000, 159_000, true))
        assertTrue(FreshnessRules.shouldExpirePeerNow(39_000, 159_001, true))
        // Without a group: 60 s.
        assertFalse(FreshnessRules.shouldExpirePeerNow(39_000, 98_999, false))
        assertFalse(FreshnessRules.shouldExpirePeerNow(39_000, 99_000, false))
        assertTrue(FreshnessRules.shouldExpirePeerNow(39_000, 99_001, false))
    }

    @Test
    fun theDesiredOwnerFlagIsTrueOnlyForALiveOwnedGroup() {
        assertTrue(FreshnessRules.desiredOwnerFlag(true, P2pRole.GROUP_OWNER))
        assertFalse(FreshnessRules.desiredOwnerFlag(true, P2pRole.CLIENT))
        assertFalse(FreshnessRules.desiredOwnerFlag(true, P2pRole.NONE))
        // Group gone: the ad must flip to o0 (clients=0) immediately.
        assertFalse(FreshnessRules.desiredOwnerFlag(false, P2pRole.GROUP_OWNER))
    }
}
