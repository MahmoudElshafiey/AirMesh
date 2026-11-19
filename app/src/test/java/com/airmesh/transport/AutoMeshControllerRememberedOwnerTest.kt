package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * TASK T1 part C2: the remembered-owner re-join inside [AutoMeshController] — the exact scenario
 * of evidence (1) (peer visible at +7 s, silent DNS-SD, attempt at +12 s), suppression while a
 * manual pause holds or auto is off, no interference with normal DNS-SD joins, 45 s pacing after
 * a failure, and the three-attempt cap.
 */
class AutoMeshControllerRememberedOwnerTest {

    private class InMemoryStorage : RememberedOwnerStorage {
        @Volatile var raw: String? = null
            private set
        override fun readRaw(): String? = raw
        override fun write(raw: String) {
            this.raw = raw
        }
    }

    private var now = 0L
    private val clock: () -> Long = { now }
    private lateinit var store: RememberedOwnerStore
    private lateinit var controller: AutoMeshController

    private fun setup(seed: Long = 1L, autoEnabled: Boolean = true, pausePolicy: ManualPausePolicy? = null) {
        val storage = InMemoryStorage()
        store = RememberedOwnerStore(storage) { now }
        val policy = RememberedOwnerPolicy(store, { now }, Random(seed))
        controller = AutoMeshController(
            myNodeId = 9000,
            tieBreakToken = 0x1000,
            clock = clock,
            random = Random(seed),
            autoEnabled = autoEnabled,
            manualPausePolicy = pausePolicy,
            rememberedOwnerPolicy = policy,
        )
    }

    private val ownerAddress = "aa:bb:cc:dd:ee:ff"
    private fun radioPeer(status: Int = RadioPeer.AVAILABLE) = RadioPeer(ownerAddress, status)

    /** Starts auto-mesh (permissions granted) at the current fake time. */
    private fun startSearching() {
        controller.onEvent(AutoMeshEvent.PermissionsChanged(true))
        assertTrue("granted permissions enter Searching", controller.state is AutoMeshState.Searching)
    }

    private fun tick(): List<AutoMeshAction> = controller.onEvent(AutoMeshEvent.Tick)

    /** Feeds the raw requestPeers list. */
    private fun feedRadio(vararg peers: RadioPeer) {
        controller.onEvent(AutoMeshEvent.RadioPeersChanged(peers.toList()))
    }

    @Test
    fun evidenceScenario_peerVisibleAtSevenSeconds_silentDnsSd_attemptAtTwelve() {
        setup()
        store.record(ownerAddress, 53802) // the owner we joined before the restart (A55's node id)
        startSearching()                  // searching since t=0: the app just restarted

        for (t in 1L..11L) {
            now = t * 1000L
            if (t == 7L) feedRadio(radioPeer()) // A54 saw A55 as AVAILABLE from +7 s on
            tick()
            assertFalse("no attempt before the 12 s minimum (t=${t}s)", controller.state is AutoMeshState.Joining)
        }

        now = 12_000L
        val actions = tick()
        assertEquals("exactly one ConnectTo at +12 s", listOf(AutoMeshAction.ConnectTo(ownerAddress)), actions)
        assertTrue(controller.state is AutoMeshState.Joining)
        assertEquals(
            "the attempt is logged with the spec wording (the service adds the 'auto: ' prefix)",
            "remembered-owner attempt #1 to #53802 $ownerAddress reason=dnssd-silent",
            controller.takeRememberedOwnerLogLine(),
        )
    }

    @Test
    fun noAttemptWhileManuallyPaused_attemptAfterResume() {
        setup(pausePolicy = ManualPausePolicy())
        store.record(ownerAddress, 53802)
        startSearching()
        feedRadio(radioPeer())

        // A manual connect request pauses auto-connect (and holds it while in flight).
        now = 1_000L
        controller.onManualAction(ManualPauseAction.CONNECT)
        assertTrue("a manual action unwinds Searching into Idle", controller.state is AutoMeshState.Idle)

        for (t in 2L..20L) {
            now = t * 1000L
            tick()
            assertFalse("paused: no attempt at t=${t}s", controller.state is AutoMeshState.Joining)
        }

        // The user resumes; searching restarts from this moment, so the 12 s minimum applies again.
        now = 20_000L
        controller.resumeManualPause()
        assertTrue(controller.state is AutoMeshState.Searching)

        for (t in 21L..31L) {
            now = t * 1000L
            tick()
            assertFalse("the 12 s minimum restarted with the resume", controller.state is AutoMeshState.Joining)
        }
        now = 32_000L
        assertEquals(
            "after resume + 12 s of searching the attempt goes out",
            listOf(AutoMeshAction.ConnectTo(ownerAddress)),
            tick(),
        )
    }

    @Test
    fun aVisibleDnsSdOwnerJoinsNormally_andDoesNotTouchTheRecord() {
        setup()
        store.record(ownerAddress, 53802)
        startSearching()
        feedRadio(radioPeer())

        now = RememberedOwnerPolicy.MIN_SEARCHING_MS
        val dnsPeer = AirMeshPeer("11:22:33:44:55:66", 777, isGroupOwner = true, tieBreakToken = 0x2000)
        val actions = controller.onEvent(AutoMeshEvent.PeersChanged(listOf(dnsPeer)))
        assertEquals("rule (b) joins the visible owner instead of consulting the policy", listOf(AutoMeshAction.ConnectTo("11:22:33:44:55:66")), actions)
        assertNull("no remembered-owner log line for a DNS-SD join", controller.takeRememberedOwnerLogLine())

        // That join fails: it must NOT count against the remembered record.
        now += 1_000L
        controller.onEvent(AutoMeshEvent.ConnectFailed(AutoFailureReason.OTHER))
        assertEquals("the record is untouched by a DNS-SD failure", 0, store.current()?.failedAttempts)
    }

    @Test
    fun failedAttemptPacesTheNextOneByFortyFiveSecondsPlusJitter() {
        setup(seed = 7L)
        store.record(ownerAddress, 53802)
        startSearching()
        feedRadio(radioPeer())

        now = RememberedOwnerPolicy.MIN_SEARCHING_MS
        assertEquals("attempt #1 goes out", listOf(AutoMeshAction.ConnectTo(ownerAddress)), tick())
        assertEquals(
            "the first attempt is logged as #1",
            "remembered-owner attempt #1 to #53802 $ownerAddress reason=dnssd-silent",
            controller.takeRememberedOwnerLogLine(),
        )

        // The attempt fails one second later.
        now += 1_000L
        controller.onEvent(AutoMeshEvent.ConnectFailed(AutoFailureReason.OTHER))
        assertTrue("backoff after the failure", controller.state is AutoMeshState.Backoff)

        // Only the policy's Random drew so far (the first failure delay has no jitter), so a
        // fresh Random(7) reproduces exactly the jitter stamped on attempt #1.
        val expectedJitter = Random(7).nextInt(RememberedOwnerPolicy.SPACING_JITTER_MAX_MS.toInt() + 1)
        val nextAllowed = RememberedOwnerPolicy.MIN_SEARCHING_MS +
            RememberedOwnerPolicy.ATTEMPT_SPACING_MS + expectedJitter

        // Tick through the backoff and the whole pacing window: nothing may connect.
        var t = now + 1_000L
        while (t < nextAllowed) {
            now = t
            val actions = tick()
            assertFalse("no re-attempt before the pacing stamp elapses (t=${t}ms)", actions.contains(AutoMeshAction.ConnectTo(ownerAddress)))
            t += 1_000L
        }

        now = nextAllowed
        assertEquals(
            "at exactly the stamped time attempt #2 goes out",
            listOf(AutoMeshAction.ConnectTo(ownerAddress)),
            tick(),
        )
        assertEquals(
            "the second attempt is logged as #2",
            "remembered-owner attempt #2 to #53802 $ownerAddress reason=dnssd-silent",
            controller.takeRememberedOwnerLogLine(),
        )
    }

    @Test
    fun threeFailedAttemptsAbandonTheRecord() {
        setup()
        store.record(ownerAddress, 53802)
        startSearching()
        feedRadio(radioPeer())

        var attempts = 0
        now = RememberedOwnerPolicy.MIN_SEARCHING_MS
        while (attempts < 4 && now < 10 * 60_000L) {
            val actions = tick()
            if (actions == listOf(AutoMeshAction.ConnectTo(ownerAddress))) {
                attempts++
                assertEquals(
                    "attempt #N is logged",
                    "remembered-owner attempt #$attempts to #53802 $ownerAddress reason=dnssd-silent",
                    controller.takeRememberedOwnerLogLine(),
                )
                // The attempt fails one second later.
                now += 1_000L
                controller.onEvent(AutoMeshEvent.ConnectFailed(AutoFailureReason.OTHER))
            } else {
                now += 1_000L
            }
        }
        assertEquals("exactly three attempts, then the record is abandoned", 3, attempts)
        assertNull("the store cleared itself after the third failure", store.current())
    }

    @Test
    fun aSuccessfulJoinRefreshesTheRecord() {
        setup()
        store.record(ownerAddress, 53802)
        startSearching()
        feedRadio(radioPeer())

        now = RememberedOwnerPolicy.MIN_SEARCHING_MS
        assertEquals("attempt #1 goes out", listOf(AutoMeshAction.ConnectTo(ownerAddress)), tick())
        assertEquals(
            "the first attempt is logged as #1",
            "remembered-owner attempt #1 to #53802 $ownerAddress reason=dnssd-silent",
            controller.takeRememberedOwnerLogLine(),
        )

        // The join lands: we become a client.
        now += 2_000L
        controller.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))
        assertEquals("success refreshes the record's timestamp", now, store.current()?.recordedAtMs)
        assertEquals(0, store.current()?.failedAttempts)

        // The group later dies; after the backoff and a fresh 12 s of searching we may try again.
        now += 5_000L
        controller.onEvent(AutoMeshEvent.GroupGone)
        assertTrue(controller.state is AutoMeshState.Backoff)
        now += 4_000L // past the group-gone backoff (1 s + up to 2 s jitter)
        tick()
        val searchingSince = (controller.state as? AutoMeshState.Searching)?.sinceMs
        assertNotNull("backoff expired into Searching", searchingSince)

        now = searchingSince!! + RememberedOwnerPolicy.MIN_SEARCHING_MS
        assertEquals(
            "a fresh attempt #1 after the refresh",
            listOf(AutoMeshAction.ConnectTo(ownerAddress)),
            tick(),
        )
        assertEquals(
            "the numbering restarted with the refreshed record",
            "remembered-owner attempt #1 to #53802 $ownerAddress reason=dnssd-silent",
            controller.takeRememberedOwnerLogLine(),
        )
    }

    @Test
    fun noAttemptWhenAutoIsOff() {
        setup(autoEnabled = false)
        store.record(ownerAddress, 53802)
        feedRadio(radioPeer())
        now = RememberedOwnerPolicy.MIN_SEARCHING_MS + 60_000L
        tick() // onTick is gated by autoEnabled
        assertNull("auto off: the policy is never consulted", store.current()?.nextAllowedAttemptAtMs)
    }

    @Test
    fun aPeerThatIsNotAvailableNeverTriggersAnAttempt() {
        setup()
        store.record(ownerAddress, 53802)
        startSearching()
        feedRadio(radioPeer(status = RadioPeer.CONNECTED)) // e.g. stale CONNECTED entry

        now = RememberedOwnerPolicy.MIN_SEARCHING_MS + 60_000L
        tick()
        assertFalse("a non-AVAILABLE status never triggers", controller.state is AutoMeshState.Joining)
    }
}
