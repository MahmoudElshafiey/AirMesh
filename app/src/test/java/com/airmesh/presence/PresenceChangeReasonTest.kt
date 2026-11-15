package com.airmesh.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure tests for the presence transition log lines (TASK U5 part 4). */
class PresenceChangeReasonTest {
    private val noChange = PresenceChangeContext()

    @Test
    fun noLineWhenStateUnchanged() {
        assertNull(presenceChangeReason(PresenceState.ONLINE_DIRECT, PresenceState.ONLINE_DIRECT, noChange))
        assertNull(
            presenceTransitionLine(2, PresenceState.ONLINE_DIRECT, PresenceState.ONLINE_DIRECT, noChange),
        )
    }

    @Test
    fun ownLinkLostExplainsMassOutage() {
        val context = PresenceChangeContext(ownLinkWentDown = true)
        assertEquals(
            PresenceChangeReason.OWN_LINK_LOST,
            presenceChangeReason(PresenceState.ONLINE_DIRECT, PresenceState.NOT_CONNECTED, context),
        )
        assertEquals(
            "presence: #3 ONLINE_DIRECT -> NOT_CONNECTED reason=ownLinkLost",
            presenceTransitionLine(3, PresenceState.ONLINE_DIRECT, PresenceState.NOT_CONNECTED, context),
        )
    }

    @Test
    fun linkRestoredAfterOwnOutage() {
        val context = PresenceChangeContext(ownLinkCameUp = true)
        assertEquals(
            PresenceChangeReason.LINK_RESTORED,
            presenceChangeReason(PresenceState.NOT_CONNECTED, PresenceState.CHECKING, context),
        )
    }

    @Test
    fun directLinkDropBeatsFreshFrame() {
        // Both facts are true in the same round; the drop is the dominant cause.
        val context = PresenceChangeContext(directLinkDropped = true, freshFrameArrived = true)
        assertEquals(
            PresenceChangeReason.DIRECT_LINK_DROPPED,
            presenceChangeReason(PresenceState.ONLINE_DIRECT, PresenceState.OUT_OF_RANGE, context),
        )
        assertEquals(
            "presence: #2 ONLINE_DIRECT -> OUT_OF_RANGE reason=directLinkDropped",
            presenceTransitionLine(2, PresenceState.ONLINE_DIRECT, PresenceState.OUT_OF_RANGE, context),
        )
    }

    @Test
    fun ownLinkWentDownWinsOverDirectDrop() {
        // The whole set went empty: we cannot tell who left on their own, so the outage wins.
        val context = PresenceChangeContext(ownLinkWentDown = true, directLinkDropped = true)
        assertEquals(
            PresenceChangeReason.OWN_LINK_LOST,
            presenceChangeReason(PresenceState.ONLINE_DIRECT, PresenceState.NOT_CONNECTED, context),
        )
    }

    @Test
    fun freshFrameMovesStateForward() {
        val context = PresenceChangeContext(freshFrameArrived = true)
        assertEquals(
            PresenceChangeReason.FRESH_EVIDENCE,
            presenceChangeReason(PresenceState.LastSeen(1_000L), PresenceState.ONLINE_RELAYED, context),
        )
        assertEquals(
            "presence: #5 LastSeen -> ONLINE_RELAYED reason=freshEvidence",
            presenceTransitionLine(5, PresenceState.LastSeen(1_000L), PresenceState.ONLINE_RELAYED, context),
        )
    }

    @Test
    fun agingWithoutFramesIsEvidenceExpired() {
        assertEquals(
            PresenceChangeReason.EVIDENCE_EXPIRED,
            presenceChangeReason(PresenceState.CHECKING, PresenceState.LastSeen(50L), noChange),
        )
    }

    @Test
    fun lastSeenLogNameDropsTheTimestamp() {
        val context = PresenceChangeContext(directLinkDropped = true)
        assertEquals(
            "presence: #9 ONLINE_DIRECT -> LastSeen reason=directLinkDropped",
            presenceTransitionLine(9, PresenceState.ONLINE_DIRECT, PresenceState.LastSeen(123L), context),
        )
    }
}
