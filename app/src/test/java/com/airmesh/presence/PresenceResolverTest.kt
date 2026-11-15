package com.airmesh.presence

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fake-clock tests for the truthful presence rules (TASK U5 part 1). */
class PresenceResolverTest {

    private var now = 1_000_000L
    private val resolver = PresenceResolver(clock = { now }) // production thresholds
    private val connected = OwnLink(liveNeighborCount = 3)

    @Test
    fun zeroNeighborsMeansNotConnectedForEverybody() {
        val offline = OwnLink(liveNeighborCount = 0, linkLostAt = now - 1_000L)
        // Even a live direct neighbor with fresh activity is unreachable while we are off.
        assertEquals(
            PresenceState.NOT_CONNECTED,
            resolver.resolve(offline, ContactEvidence(isDirectNeighbor = true, lastActivityAt = now)),
        )
        assertEquals(
            PresenceState.NOT_CONNECTED,
            resolver.resolve(offline, ContactEvidence(lastActivityAt = now - 1_000L)),
        )
    }

    @Test
    fun aLiveDirectNeighborIsOnlineDirect() {
        // Fresh or stale activity does not matter: the socket is open.
        assertEquals(PresenceState.ONLINE_DIRECT, resolver.resolve(connected, ContactEvidence(isDirectNeighbor = true)))
        assertEquals(
            PresenceState.ONLINE_DIRECT,
            resolver.resolve(connected, ContactEvidence(isDirectNeighbor = true, lastActivityAt = now - 3_600_000L)),
        )
    }

    @Test
    fun neverSeenAndNotANeighborIsUnknown() {
        assertEquals(PresenceState.UNKNOWN, resolver.resolve(connected, ContactEvidence(isDirectNeighbor = false)))
    }

    // --- Relayed evidence age buckets ----------------------------------------

    @Test
    fun freshRelayedEvidenceIsOnlineRelayed() {
        val evidence = ContactEvidence(lastActivityAt = now - 10_000L)
        assertEquals(PresenceState.ONLINE_RELAYED, resolver.resolve(connected, evidence))
    }

    @Test
    fun theSixtySecondBoundaryMovesFromOnlineToChecking() {
        // 59.999 s old is still online; exactly 60 s is already checking.
        assertEquals(PresenceState.ONLINE_RELAYED, resolver.resolve(connected, ContactEvidence(lastActivityAt = now - 59_999L)))
        assertEquals(PresenceState.CHECKING, resolver.resolve(connected, ContactEvidence(lastActivityAt = now - 60_000L)))
    }

    @Test
    fun theHundredSecondBoundaryMovesFromCheckingToLastSeen() {
        // Exactly 100 s is still checking; one millisecond older is LastSeen.
        assertEquals(PresenceState.CHECKING, resolver.resolve(connected, ContactEvidence(lastActivityAt = now - 100_000L)))
        val lastSeen = resolver.resolve(connected, ContactEvidence(lastActivityAt = now - 100_001L))
        assertEquals(PresenceState.LastSeen(now - 100_001L), lastSeen)
    }

    @Test
    fun oldRelayedEvidenceIsLastSeenWithItsTimestamp() {
        val atMs = now - 5 * 60_000L
        assertEquals(PresenceState.LastSeen(atMs), resolver.resolve(connected, ContactEvidence(lastActivityAt = atMs)))
    }

    // --- Direct link drop -----------------------------------------------------

    @Test
    fun aDirectDropWithoutLaterRelayedActivityIsOutOfRangeImmediately() {
        // The last frame arrived exactly when the link dropped: nothing proves they are still here.
        val lostAt = now - 2_000L
        assertEquals(
            PresenceState.OUT_OF_RANGE,
            resolver.resolve(connected, ContactEvidence(directLostAt = lostAt, lastActivityAt = lostAt)),
        )
        // And the same when their only frames predate the drop.
        assertEquals(
            PresenceState.OUT_OF_RANGE,
            resolver.resolve(connected, ContactEvidence(directLostAt = lostAt, lastActivityAt = lostAt - 60_000L)),
        )
    }

    @Test
    fun aDirectNeighborThatNeverSentAFramesIsOutOfRangeAfterTheDrop() {
        // We held the socket, then lost it; they never sent anything. That is "out of range",
        // not "unknown" — we know the link went away.
        val lostAt = now - 2_000L
        assertEquals(
            PresenceState.OUT_OF_RANGE,
            resolver.resolve(connected, ContactEvidence(directLostAt = lostAt)),
        )
    }

    @Test
    fun aDirectDropWithLaterRelayedActivityFallsBackToTheAgeBuckets() {
        // The drop is older than every frame below, so each one counts as relayed-after-drop.
        val lostAt = now - 5 * 60_000L
        // A relayed frame strictly after the drop: bucket by its age.
        assertEquals(PresenceState.ONLINE_RELAYED, resolver.resolve(connected, ContactEvidence(directLostAt = lostAt, lastActivityAt = now - 10_000L)))
        assertEquals(PresenceState.CHECKING, resolver.resolve(connected, ContactEvidence(directLostAt = lostAt, lastActivityAt = now - 80_000L)))
        val atMs = now - 3 * 60_000L
        assertEquals(PresenceState.LastSeen(atMs), resolver.resolve(connected, ContactEvidence(directLostAt = lostAt, lastActivityAt = atMs)))
    }

    // --- Own link loss and restoration ----------------------------------------

    @Test
    fun staleEvidenceAfterOwnLinkRestoreChecksForOneAnnounceCycleThenLastSeen() {
        // Our own link was down; the peer's only evidence predates the restore. They were
        // online before the loss, so we get announceInterval + 5 s of grace as CHECKING...
        val restoredAt = now - 49_000L // 49 s ago: inside the 50 s grace window
        val ownLink = OwnLink(liveNeighborCount = 2, linkLostAt = restoredAt - 60_000L, linkRestoredAt = restoredAt)
        val evidence = ContactEvidence(lastActivityAt = restoredAt - 30_000L, previousStateWasOnline = true)
        assertEquals(PresenceState.CHECKING, resolver.resolve(ownLink, evidence))

        // ...and once the grace window has passed the same evidence is simply LastSeen.
        now += 2_001L // 51 s after the restore: outside announceInterval + 5 s
        assertEquals(PresenceState.LastSeen(restoredAt - 30_000L), resolver.resolve(ownLink, evidence))
    }

    @Test
    fun theRestoreGraceWindowEndsExactlyAfterAnnounceIntervalPlusFiveSeconds() {
        val restoredAt = now - (PresenceResolver.DEFAULT_ANNOUNCE_INTERVAL_MS + PresenceResolver.RESTORE_GRACE_SLACK_MS)
        val ownLink = OwnLink(liveNeighborCount = 1, linkLostAt = restoredAt - 10_000L, linkRestoredAt = restoredAt)
        val evidence = ContactEvidence(lastActivityAt = restoredAt - 1_000L, previousStateWasOnline = true)
        // Exactly at the edge is no longer inside the window.
        assertEquals(PresenceState.LastSeen(restoredAt - 1_000L), resolver.resolve(ownLink, evidence))
    }

    @Test
    fun theRestoreGraceRequiresThatTheyWereOnlineBeforeTheLoss() {
        val restoredAt = now - 10_000L
        val ownLink = OwnLink(liveNeighborCount = 2, linkLostAt = restoredAt - 30_000L, linkRestoredAt = restoredAt)
        // They were already LastSeen before our outage: no grace, straight to LastSeen.
        val evidence = ContactEvidence(lastActivityAt = restoredAt - 120_000L, previousStateWasOnline = false)
        assertEquals(PresenceState.LastSeen(restoredAt - 120_000L), resolver.resolve(ownLink, evidence))
    }

    @Test
    fun freshEvidenceAfterOwnLinkRestoreIsOnlineAgain() {
        // A frame arrived AFTER the restore: it is not stale, so the normal buckets apply.
        val restoredAt = now - 30_000L
        val ownLink = OwnLink(liveNeighborCount = 2, linkLostAt = restoredAt - 60_000L, linkRestoredAt = restoredAt)
        val evidence = ContactEvidence(lastActivityAt = now - 5_000L, previousStateWasOnline = true)
        assertEquals(PresenceState.ONLINE_RELAYED, resolver.resolve(ownLink, evidence))
    }

    // --- Thresholds are constructor parameters ---------------------------------

    @Test
    fun theThresholdsAreConstructorParameters() {
        val fast = PresenceResolver(clock = { now }, relayedOnlineMs = 1_000L, checkingWindowEndMs = 2_000L)
        assertEquals(PresenceState.ONLINE_RELAYED, fast.resolve(connected, ContactEvidence(lastActivityAt = now - 999L)))
        assertEquals(PresenceState.CHECKING, fast.resolve(connected, ContactEvidence(lastActivityAt = now - 1_500L)))
        assertEquals(PresenceState.LastSeen(now - 2_500L), fast.resolve(connected, ContactEvidence(lastActivityAt = now - 2_500L)))
    }

    @Test
    fun theDefaultThresholdsAreSixtyHundredAndFortyFiveSeconds() {
        assertEquals(60_000L, PresenceResolver.DEFAULT_RELAYED_ONLINE_MS)
        assertEquals(100_000L, PresenceResolver.DEFAULT_CHECKING_WINDOW_END_MS)
        assertEquals(45_000L, PresenceResolver.DEFAULT_ANNOUNCE_INTERVAL_MS)
    }
}
