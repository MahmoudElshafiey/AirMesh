package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * JVM tests for the temporary manual pauses in [AutoMeshController] (TASK S1 part 2): a manual
 * Wi-Fi Direct action pauses auto-connect for a bounded window instead of forever, an in-flight
 * manual connect extends it, and the Auto switch stays authoritative. Fake clock via `var now`.
 */
class AutoMeshControllerManualPauseTest {

    private fun makeController(policy: ManualPausePolicy?, nowMs: () -> Long): AutoMeshController =
        AutoMeshController(
            myNodeId = 6469,
            tieBreakToken = 0x1111,
            clock = { nowMs() },
            random = Random(42L),
            autoEnabled = true,
            manualPausePolicy = policy,
        )

    private fun ownerPeer() = AirMeshPeer("BB:00:00:00:00:02", 38010, true, 0x2222)

    @Test
    fun `discover pauses for 45 seconds and resumes by timeout`() {
        var now = 0L
        val policy = ManualPausePolicy()
        val c = makeController(policy) { now }
        c.onEvent(AutoMeshEvent.PermissionsChanged(true))
        assertTrue(c.state is AutoMeshState.Searching)

        now = 5_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), c.onManualAction(ManualPauseAction.DISCOVER))
        assertTrue("unwound to Idle", c.state is AutoMeshState.Idle)
        assertEquals("pause line queued", "manual pause until +45s (action=discover)", c.takePauseLogLine())

        // A visible owner must NOT trigger a join while the pause runs.
        now = 20_000L
        assertTrue(c.onEvent(AutoMeshEvent.PeersChanged(listOf(ownerPeer()))).isEmpty())
        assertTrue("still Idle during the pause", c.state is AutoMeshState.Idle)

        // Boundary: the pause started at +5 s, so it ends exactly at +50 s.
        now = 49_999L
        assertTrue(c.onEvent(AutoMeshEvent.Tick).isEmpty())
        assertTrue("still held at -1 ms", c.state is AutoMeshState.Idle)

        now = 50_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), c.onEvent(AutoMeshEvent.Tick))
        assertTrue("searching again after the timeout", c.state is AutoMeshState.Searching)
        assertEquals("resume line queued", "resumed (reason=timeout)", c.takePauseLogLine())
    }

    @Test
    fun `an in-flight manual connect holds the pause and settles when its group forms`() {
        var now = 0L
        val policy = ManualPausePolicy()
        val c = makeController(policy) { now }
        c.onEvent(AutoMeshEvent.PermissionsChanged(true))

        now = 1_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), c.onManualAction(ManualPauseAction.CONNECT))
        assertEquals("manual pause until +45s (action=connect)", c.takePauseLogLine())
        assertTrue("connect is in flight", policy.connectInFlight)

        // The base 45 s deadline (+46 s) has passed, but the connect is still pending: no resume.
        now = 50_000L
        assertTrue(c.onEvent(AutoMeshEvent.Tick).isEmpty())
        assertTrue("still held by the in-flight connect", c.state is AutoMeshState.Idle)

        // The manual connect succeeds at +60 s: group reality is tracked even while paused.
        now = 60_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), c.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT)))
        assertTrue("landed in Client", c.state is AutoMeshState.Client)

        // Settle delay: auto resumes 10 s after the group formed, at +70 s.
        now = 69_999L
        assertTrue(c.onEvent(AutoMeshEvent.Tick).isEmpty())
        now = 70_000L
        assertTrue("in a group there is nothing to restart", c.onEvent(AutoMeshEvent.Tick).isEmpty())
        assertEquals("resume line says connected", "resumed (reason=connected)", c.takePauseLogLine())
        assertFalse(policy.isPaused(71_000L))
    }

    @Test
    fun `a failed manual connect settles and resumes searching after 10 seconds`() {
        var now = 0L
        val policy = ManualPausePolicy()
        val c = makeController(policy) { now }
        c.onEvent(AutoMeshEvent.PermissionsChanged(true))

        now = 2_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), c.onManualAction(ManualPauseAction.CONNECT))
        assertEquals("manual pause until +45s (action=connect)", c.takePauseLogLine())

        // The manual connect fails at +25 s while we sit in Idle: the failure is ignored by the
        // state machine, but it settles the pause.
        now = 25_000L
        assertTrue(c.onEvent(AutoMeshEvent.ConnectFailed(AutoFailureReason.OTHER)).isEmpty())
        assertFalse("no longer in flight", policy.connectInFlight)

        now = 34_999L
        assertTrue(c.onEvent(AutoMeshEvent.Tick).isEmpty())
        now = 35_000L // settle +10 s
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), c.onEvent(AutoMeshEvent.Tick))
        assertTrue("searching again", c.state is AutoMeshState.Searching)
        assertEquals("resume line says failed", "resumed (reason=failed)", c.takePauseLogLine())
    }

    @Test
    fun `disconnect pauses for 120 seconds`() {
        var now = 0L
        val policy = ManualPausePolicy()
        val c = makeController(policy) { now }
        c.onEvent(AutoMeshEvent.PermissionsChanged(true))

        now = 3_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), c.onManualAction(ManualPauseAction.DISCONNECT))
        assertEquals("manual pause until +120s (action=disconnect)", c.takePauseLogLine())

        // Boundary: the pause started at +3 s, so it ends exactly at +123 s.
        now = 122_999L
        assertTrue(c.onEvent(AutoMeshEvent.Tick).isEmpty())
        now = 123_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), c.onEvent(AutoMeshEvent.Tick))
        assertEquals("resumed (reason=timeout)", c.takePauseLogLine())
    }

    @Test
    fun `the auto switch stays authoritative over a running pause`() {
        var now = 0L
        val policy = ManualPausePolicy()
        val c = makeController(policy) { now }
        now = 1_000L
        c.onEvent(AutoMeshEvent.PermissionsChanged(true))

        now = 2_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), c.onManualAction(ManualPauseAction.DISCOVER))
        assertEquals("manual pause until +45s (action=discover)", c.takePauseLogLine())

        // The user turns the switch off, then back on while the pause is still running.
        now = 10_000L
        assertTrue(c.setAutoEnabled(false).isEmpty())
        assertFalse(c.autoEnabled)
        now = 20_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), c.setAutoEnabled(true))
        assertTrue("searching again immediately", c.state is AutoMeshState.Searching)
        assertEquals("resume line says userToggle", "resumed (reason=userToggle)", c.takePauseLogLine())
        assertFalse("the old 45 s deadline is gone", policy.isPaused(46_999L))
    }

    @Test
    fun `the resume button ends the pause immediately and a second press is a no-op`() {
        var now = 0L
        val policy = ManualPausePolicy()
        val c = makeController(policy) { now }
        now = 1_000L
        c.onEvent(AutoMeshEvent.PermissionsChanged(true))

        now = 2_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), c.onManualAction(ManualPauseAction.DISCOVER))
        assertEquals("manual pause until +45s (action=discover)", c.takePauseLogLine())

        // The banner's Resume button at +30 s: the remaining 15 s are skipped.
        now = 30_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), c.resumeManualPause())
        assertTrue("searching again immediately", c.state is AutoMeshState.Searching)
        assertEquals("resumed (reason=userToggle)", c.takePauseLogLine())

        now = 31_000L
        assertTrue("second press does nothing", c.resumeManualPause().isEmpty())
        assertNull("no second resume line", c.takePauseLogLine())
    }

    @Test
    fun `a group loss during a pause enters backoff but the pause governs the search`() {
        var now = 0L
        val policy = ManualPausePolicy()
        val c = makeController(policy) { now }
        c.onEvent(AutoMeshEvent.PermissionsChanged(true))

        // Join an owner and become a client.
        now = 1_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")), c.onEvent(AutoMeshEvent.PeersChanged(listOf(ownerPeer()))))
        assertTrue(c.state is AutoMeshState.Joining)
        now = 2_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), c.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT)))
        assertTrue(c.state is AutoMeshState.Client)

        // The user disconnects manually at +5 s: a 120 s pause, until +125 s.
        now = 5_000L
        assertTrue("a client has nothing to unwind", c.onManualAction(ManualPauseAction.DISCONNECT).isEmpty())
        assertEquals("manual pause until +120s (action=disconnect)", c.takePauseLogLine())

        // The group drops at +30 s: backoff as usual, but the search must wait for the pause.
        now = 30_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), c.onEvent(AutoMeshEvent.GroupGone))
        assertTrue("in backoff", c.state is AutoMeshState.Backoff)

        // The backoff window (1-3 s) expired long ago; the pause still holds at +124.999 s.
        now = 124_999L
        assertTrue(c.onEvent(AutoMeshEvent.Tick).isEmpty())
        assertTrue("still in backoff while paused", c.state is AutoMeshState.Backoff)

        // The pause ends exactly at +125 s and releases the search.
        now = 125_000L
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), c.onEvent(AutoMeshEvent.Tick))
        assertTrue("searching again", c.state is AutoMeshState.Searching)
        assertEquals("resumed (reason=timeout)", c.takePauseLogLine())
    }

    @Test
    fun `without a policy the manual action is a no-op`() {
        var now = 0L
        val c = makeController(null) { now }
        c.onEvent(AutoMeshEvent.PermissionsChanged(true))

        now = 1_000L
        assertTrue("null policy keeps today's behavior", c.onManualAction(ManualPauseAction.DISCOVER).isEmpty())
        assertTrue("state untouched", c.state is AutoMeshState.Searching)
        assertNull(c.takePauseLogLine())
    }
}
