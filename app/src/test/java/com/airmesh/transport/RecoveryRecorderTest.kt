package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the recovery-episode recorder (TASK R3 part 1): fake clock, captured log lines.
 * Covers the start/complete line formats, the completion-path rules (join/create/reconnectTcp/
 * merge/supervisor), the 120 s + every-30 s diagnostic cadence and its full context rendering,
 * transition/timer recording, and abort semantics.
 */
class RecoveryRecorderTest {

    private var now = 0L
    private val lines = mutableListOf<String>()
    private val recorder = RecoveryRecorder(clock = { now }) { lines.add(it) }

    private fun snapshot(
        stateLine: String = "Searching",
        peers: List<RecoveryPeerInfo> = emptyList(),
        discoveryActive: Boolean = true,
        lastScanAgoMs: Long? = 5_000L,
        advertOwner: Boolean = false,
        advertClients: Int = 0,
        groupFormed: Boolean = false,
        tcpRetryRunning: Boolean = false,
    ) = RecoverySnapshot(
        stateLine = stateLine,
        peers = peers,
        discoveryActive = discoveryActive,
        lastScanAgoMs = lastScanAgoMs,
        serviceAdvertOwner = advertOwner,
        serviceAdvertClients = advertClients,
        groupFormed = groupFormed,
        tcpRetryRunning = tcpRetryRunning,
    )

    @Test
    fun theStartLineCarriesCauseStateAndGroupFormed() {
        now = 1_000L
        assertTrue(recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_GROUP_GONE, "Backoff(group gone)", false, P2pRole.NONE))
        assertEquals(listOf("recovery: started cause=groupGone state=Backoff(group gone) groupFormed=false"), lines)
        assertTrue(recorder.active)
        assertEquals(RecoveryRecorder.CAUSE_GROUP_GONE, recorder.cause)
    }

    @Test
    fun aSecondStartWhileActiveIsIgnored() {
        now = 0L
        assertTrue(recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_OWN_LINK_LOST, "Client", true, P2pRole.CLIENT))
        now = 5_000L
        assertFalse(recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_P2P_DISABLED, "Idle", false, P2pRole.NONE))
        // The first loss wins: one start line, original cause kept.
        assertEquals(1, lines.size)
        assertEquals(RecoveryRecorder.CAUSE_OWN_LINK_LOST, recorder.cause)
    }

    @Test
    fun completionLogsElapsedAndJoinPath() {
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_GROUP_GONE, "Searching", false, P2pRole.NONE)
        now = 15_000L
        recorder.onNeighborUp(P2pRole.CLIENT)
        assertEquals("recovery: complete after 15000ms path=join", lines[1])
        assertFalse(recorder.active)
        assertNull(recorder.cause)
    }

    @Test
    fun completionPathIsCreateWhenWeBecomeOwner() {
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_GROUP_GONE, "Searching", false, P2pRole.NONE)
        now = 30_000L
        recorder.onNeighborUp(P2pRole.GROUP_OWNER)
        assertEquals("recovery: complete after 30000ms path=create", lines[1])
    }

    @Test
    fun completionPathIsReconnectTcpWhenTheSameGroupSurvived() {
        // The episode started with our group still formed (a link dropped inside it); the same
        // role comes back, so this was a plain TCP reconnect, not a join.
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_OWN_LINK_LOST, "Client", true, P2pRole.CLIENT)
        now = 8_000L
        recorder.onNeighborUp(P2pRole.CLIENT)
        assertEquals("recovery: complete after 8000ms path=reconnectTcp", lines[1])
    }

    @Test
    fun aGroupLostDuringTheEpisodePreventsReconnectTcp() {
        // Link dropped first (group still formed), then the group itself went away and we joined
        // someone else: that is a join, not a reconnect of the old group.
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_OWN_LINK_LOST, "Client", true, P2pRole.CLIENT)
        now = 3_000L
        recorder.noteGroupLost()
        now = 41_000L
        recorder.onNeighborUp(P2pRole.CLIENT)
        assertEquals("recovery: complete after 41000ms path=join", lines[1])
    }

    @Test
    fun aMergeAttemptWinsOverCreate() {
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_GROUP_GONE, "Searching", false, P2pRole.NONE)
        recorder.noteMergeAttempted()
        now = 5_000L
        // Even though we end up as owner (which would read as create), the merge attempt wins.
        recorder.onNeighborUp(P2pRole.GROUP_OWNER)
        assertEquals("recovery: complete after 5000ms path=merge", lines[1])
    }

    @Test
    fun anAbandonedMergeFallsBackToTheUsualPath() {
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_GROUP_GONE, "Searching", false, P2pRole.NONE)
        recorder.noteMergeAttempted()
        recorder.noteMergeAbandoned() // the yield failed; we left Yielding without joining
        now = 12_000L
        recorder.onNeighborUp(P2pRole.CLIENT)
        assertEquals("recovery: complete after 12000ms path=join", lines[1])
    }

    @Test
    fun aPathOverrideWinsOverTheComputedOne() {
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_OWN_LINK_LOST, "Client", true, P2pRole.CLIENT)
        now = 9_000L
        recorder.onNeighborUp(P2pRole.CLIENT, RecoveryRecorder.PATH_SUPERVISOR)
        assertEquals("recovery: complete after 9000ms path=supervisor", lines[1])
    }

    @Test
    fun noCompletionIsLoggedWithoutAnActiveEpisode() {
        now = 5_000L
        recorder.onNeighborUp(P2pRole.CLIENT)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun theFirstDiagnosticAppearsAt120sAndThenEvery30s() {
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_GROUP_GONE, "Searching", false, P2pRole.NONE)

        now = 60_000L
        recorder.tick(snapshot())
        assertEquals(1, lines.size) // still only the start line

        now = 119_999L
        recorder.tick(snapshot())
        assertEquals(1, lines.size)

        now = 120_000L
        recorder.tick(snapshot())
        assertTrue(lines[1], lines[1].startsWith("recovery: STILL NOT CONNECTED after 120s:"))

        now = 149_999L
        recorder.tick(snapshot())
        assertEquals(2, lines.size)

        now = 150_000L
        recorder.tick(snapshot())
        assertTrue(lines[2], lines[2].startsWith("recovery: STILL NOT CONNECTED after 150s:"))

        now = 179_999L
        recorder.tick(snapshot())
        assertEquals(3, lines.size)

        now = 180_000L
        recorder.tick(snapshot())
        assertTrue(lines[3], lines[3].startsWith("recovery: STILL NOT CONNECTED after 180s:"))
    }

    @Test
    fun theDiagnosticLineShowsTheFullContext() {
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_GROUP_GONE, "Backoff(group gone)", false, P2pRole.NONE)
        recorder.noteTimers(mapOf("backoff" to 122_000L, "tcpProbe" to null))

        now = 120_000L
        recorder.tick(
            snapshot(
                stateLine = "Backoff(group gone)",
                peers = listOf(RecoveryPeerInfo(nodeId = 7, owner = true, clients = 2, lastSeenAgoMs = 3_000L)),
                discoveryActive = false,
                lastScanAgoMs = null,
                advertOwner = true,
                advertClients = 3,
                groupFormed = true,
                tcpRetryRunning = true,
            ),
        )

        assertEquals(
            "recovery: STILL NOT CONNECTED after 120s: state=Backoff(group gone), " +
                "peers=[#7 owner clients=2 lastSeen=3s], timers=[backoff:2s, tcpProbe:running], " +
                "discovery=inactive lastScanAgo=never, serviceAdvert=o1 c=3, groupFormed=true, tcpRetry=running",
            lines[1],
        )
    }

    @Test
    fun theDiagnosticLineShowsEmptyPeersTimersAndNeverScanned() {
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_P2P_DISABLED, "Idle (Wi-Fi Direct is off)", false, P2pRole.NONE)

        now = 120_000L
        recorder.tick(snapshot(stateLine = "Idle (Wi-Fi Direct is off)", discoveryActive = true, lastScanAgoMs = null))

        assertEquals(
            "recovery: STILL NOT CONNECTED after 120s: state=Idle (Wi-Fi Direct is off), peers=[], timers=[], " +
                "discovery=active lastScanAgo=never, serviceAdvert=o0 c=0, groupFormed=false, tcpRetry=stopped",
            lines[1],
        )
    }

    @Test
    fun transitionsAreRecordedWithElapsedOffsetsAndBounded() {
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_GROUP_GONE, "Idle", false, P2pRole.NONE)

        now = 3_000L
        recorder.noteControllerTransition("Idle", "Searching")
        now = 7_000L
        recorder.noteControllerTransition("Searching", "Joining(#2 x)")
        assertEquals(listOf("+3s Idle -> Searching", "+7s Searching -> Joining(#2 x)"), recorder.transitions)

        // Overflow: the record is bounded (oldest dropped), so a long episode cannot grow it.
        repeat(60) { i ->
            now += 1_000L
            recorder.noteControllerTransition("A$i", "B$i")
        }
        assertEquals(50, recorder.transitions.size)
        assertFalse(recorder.transitions.contains("+3s Idle -> Searching"))
    }

    @Test
    fun timersAreReplacedNotAccumulated() {
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_GROUP_GONE, "Searching", false, P2pRole.NONE)
        recorder.noteTimers(mapOf("backoff" to 130_000L))
        recorder.noteTimers(mapOf("joinTimeout" to null)) // a later transition replaces the record

        now = 120_000L
        recorder.tick(snapshot())
        assertTrue(lines[1], lines[1].contains("timers=[joinTimeout:running]"))
        assertFalse(lines[1], lines[1].contains("backoff:"))
    }

    @Test
    fun anAbortedEpisodeStopsDiagnosticsAndCompletion() {
        now = 0L
        recorder.onRecoveryStarted(RecoveryRecorder.CAUSE_GROUP_GONE, "Searching", false, P2pRole.NONE)
        recorder.onRecoveryAborted() // e.g. the user paused auto-mesh: manual mode owns recovery

        assertFalse(recorder.active)
        assertNull(recorder.cause)

        now = 130_000L
        recorder.tick(snapshot())
        now = 200_000L
        recorder.onNeighborUp(P2pRole.CLIENT)
        assertEquals(1, lines.size) // only the start line; nothing after the abort
    }

    @Test
    fun thePurePathFunctionCoversTheRoleTable() {
        val p = RecoveryRecorder::recoveryCompletionPath
        // A merge attempt always wins.
        assertEquals(RecoveryRecorder.PATH_MERGE, p.invoke(false, P2pRole.NONE, true, P2pRole.GROUP_OWNER, true))
        // Same group survived with the same role: a plain TCP reconnect (client and owner).
        assertEquals(RecoveryRecorder.PATH_RECONNECT_TCP, p.invoke(true, P2pRole.CLIENT, true, P2pRole.CLIENT, false))
        assertEquals(RecoveryRecorder.PATH_RECONNECT_TCP, p.invoke(true, P2pRole.GROUP_OWNER, true, P2pRole.GROUP_OWNER, false))
        // Group lost mid-episode: joining someone else is a join even with the same role.
        assertEquals(RecoveryRecorder.PATH_JOIN, p.invoke(true, P2pRole.CLIENT, false, P2pRole.CLIENT, false))
        // No group at start: owner now created one, client now joined one.
        assertEquals(RecoveryRecorder.PATH_CREATE, p.invoke(false, P2pRole.NONE, true, P2pRole.GROUP_OWNER, false))
        assertEquals(RecoveryRecorder.PATH_JOIN, p.invoke(false, P2pRole.NONE, true, P2pRole.CLIENT, false))
    }
}
