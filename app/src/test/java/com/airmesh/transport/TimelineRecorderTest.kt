package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for [TimelineRecorder] with a fake monotonic clock (Part 3). */
class TimelineRecorderTest {

    private var now = 1_000L
    private val recorder = TimelineRecorder { now }

    @Test
    fun `missing phases are reported as n_a`() {
        now = 1_250L
        assertEquals(
            "auto: timeline start->searching=n/a searching->firstScan=n/a " +
                "firstScan->firstPeer=n/a firstPeer->decision=n/a " +
                "decision->groupFormed=n/a groupFormed->neighborUp=n/a total=250ms",
            recorder.formatLine(),
        )
    }

    @Test
    fun `auto state changes log partial timeline with completed phases and n_a for later ones`() {
        now = 1_100L
        val enterSearching = recorder.onAutoStateChanged(
            previous = AutoMeshState.Idle,
            current = AutoMeshState.Searching(now),
        )
        assertEquals(
            "auto: timeline start->searching=100ms searching->firstScan=n/a " +
                "firstScan->firstPeer=n/a firstPeer->decision=n/a " +
                "decision->groupFormed=n/a groupFormed->neighborUp=n/a total=100ms",
            enterSearching,
        )

        // Unchanged state returns null so ticks that keep the same state do not spam logs.
        now = 1_500L
        assertNull(
            recorder.onAutoStateChanged(
                previous = AutoMeshState.Searching(1_100L),
                current = AutoMeshState.Searching(1_100L),
            ),
        )
    }

    @Test
    fun `full session records all phases and logs neighborUp only once`() {
        // Service start at 1_000 ms
        now = 1_200L
        recorder.onAutoStateChanged(AutoMeshState.Idle, AutoMeshState.Searching(now)) // +200ms

        now = 1_700L
        recorder.onFirstScan() // +500ms

        now = 2_400L
        recorder.onFirstPeer() // +700ms

        now = 2_450L
        val decisionLine = recorder.onAutoStateChanged(
            previous = AutoMeshState.Searching(1_200L),
            current = AutoMeshState.Creating,
        ) // +50ms
        assertEquals(
            "auto: timeline start->searching=200ms searching->firstScan=500ms " +
                "firstScan->firstPeer=700ms firstPeer->decision=50ms " +
                "decision->groupFormed=n/a groupFormed->neighborUp=n/a total=1450ms",
            decisionLine,
        )

        now = 4_000L
        val formedLine = recorder.onAutoStateChanged(
            previous = AutoMeshState.Creating,
            current = AutoMeshState.Owner,
        ) // +1550ms
        assertEquals(
            "auto: timeline start->searching=200ms searching->firstScan=500ms " +
                "firstScan->firstPeer=700ms firstPeer->decision=50ms " +
                "decision->groupFormed=1550ms groupFormed->neighborUp=n/a total=3000ms",
            formedLine,
        )

        now = 4_800L
        val neighborLine = recorder.onNeighborUp() // +800ms, total = 3800ms
        assertEquals(
            "auto: timeline start->searching=200ms searching->firstScan=500ms " +
                "firstScan->firstPeer=700ms firstPeer->decision=50ms " +
                "decision->groupFormed=1550ms groupFormed->neighborUp=800ms total=3800ms",
            neighborLine,
        )

        // A second neighbor or re-emission in the same session does not log a duplicate neighbor-up line.
        now = 6_000L
        assertNull(recorder.onNeighborUp())
    }

    @Test
    fun `each milestone keeps its first timestamp in the session`() {
        now = 1_100L; recorder.onSearching()
        now = 1_900L; recorder.onSearching() // ignored
        now = 1_600L; recorder.onFirstScan()
        now = 3_000L; recorder.onFirstScan() // ignored
        now = 2_000L; recorder.onFirstPeer()
        now = 4_000L; recorder.onFirstPeer() // ignored
        now = 2_100L; recorder.onDecision()
        now = 5_000L; recorder.onDecision()  // ignored
        now = 3_100L; recorder.onGroupFormed()
        now = 6_000L; recorder.onGroupFormed() // ignored
        now = 3_500L
        assertEquals(
            "auto: timeline start->searching=100ms searching->firstScan=500ms " +
                "firstScan->firstPeer=400ms firstPeer->decision=100ms " +
                "decision->groupFormed=1000ms groupFormed->neighborUp=400ms total=2500ms",
            recorder.onNeighborUp(),
        )
    }
}
