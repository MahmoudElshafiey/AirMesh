package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** JVM tests for the auto-mesh state machine: election, takeover, backoff, pause. */
class AutoMeshControllerTest {

    private var now = 0L
    private val clock: () -> Long = { now }

    private fun controller(nodeId: Int, token: Int = 0x1000, seed: Long = 1, enabled: Boolean = true) =
        AutoMeshController(nodeId, token, clock, Random(seed), enabled)

    private fun peer(nodeId: Int, address: String, owner: Boolean = false, token: Int = 0x2000) =
        AirMeshPeer(address, nodeId, owner, token)

    private fun start(c: AutoMeshController): List<AutoMeshAction> =
        c.onEvent(AutoMeshEvent.PermissionsChanged(true))

    /** Advances the fake clock and ticks. */
    private fun tick(c: AutoMeshController, ms: Long = 1_000L): List<AutoMeshAction> {
        now += ms
        return c.onEvent(AutoMeshEvent.Tick)
    }

    private fun feed(c: AutoMeshController, peers: List<AirMeshPeer>): List<AutoMeshAction> =
        c.onEvent(AutoMeshEvent.PeersChanged(peers))

    // --- election ------------------------------------------------------------------

    @Test
    fun `two phones elect the higher node id as owner exactly once`() {
        val a = controller(5, 0x1000)
        val b = controller(9, 0x2000)
        start(a)
        start(b)

        now += 1_000
        var creates = 0
        val aActions = feed(a, listOf(peer(9, "BB:00:00:00:00:02"))) // A sees B (not an owner yet)
        val bActions = feed(b, listOf(peer(5, "AA:00:00:00:00:01"))) // B sees A
        creates += aActions.count { it is AutoMeshAction.CreateGroup } +
            bActions.count { it is AutoMeshAction.CreateGroup }

        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.CreateGroup), bActions) // higher id creates
        assertTrue(aActions.isEmpty()) // lower id waits for the takeover window
        assertTrue(b.state is AutoMeshState.Creating)
        assertTrue(a.state is AutoMeshState.Searching)

        now += 1_000
        b.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.OWNER))
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
            feed(a, listOf(peer(9, "BB:00:00:00:00:02", owner = true)))) // A joins the visible owner

        now += 1_000
        a.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))

        // Long ticks afterwards must not create a second group.
        repeat(30) { tick(a); tick(b) }
        assertEquals(1, creates)
        assertTrue(b.state == AutoMeshState.Owner)
        assertTrue(a.state == AutoMeshState.Client)
    }

    @Test
    fun `the lowest node id owner wins with a token tie-break`() {
        val peers = listOf(
            peer(9, "BB:00:00:00:00:02"),
            peer(3, "AA:00:00:00:00:01", owner = true),
            peer(7, "CC:00:00:00:00:03", owner = true),
        )
        assertEquals("AA:00:00:00:00:01", chooseAutoMeshOwner(peers)?.deviceAddress)

        val tied = listOf(
            peer(4, "BB:00:00:00:00:02", owner = true, token = 0x90),
            peer(4, "AA:00:00:00:00:01", owner = true, token = 0x10),
        )
        assertEquals("AA:00:00:00:00:01", chooseAutoMeshOwner(tied)?.deviceAddress)

        assertFalse(iAmAutoMeshCreator(5, 0x2000, listOf(peer(9, "CC:00:00:00:00:03"))))
        assertTrue(iAmAutoMeshCreator(9, 0x1000, listOf(peer(5, "BB:00:00:00:00:02"))))
    }

    @Test
    fun `equal node ids fall back to the tie-break token`() {
        val low = controller(5, 0x1000)
        val high = controller(5, 0x2000) // same id, larger token → creator
        start(low)
        start(high)

        now += 1_000
        assertTrue(feed(low, listOf(peer(5, "BB:00:00:00:00:02", token = 0x2000))).isEmpty())
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.CreateGroup),
            feed(high, listOf(peer(5, "AA:00:00:00:00:01", token = 0x1000))))

        // Symmetric: with reversed tokens the other phone wins.
        assertTrue(iAmAutoMeshCreator(5, 0x3000, listOf(peer(5, "BB:00:00:00:00:02", token = 0x2000))))
        assertFalse(iAmAutoMeshCreator(5, 0x2000, listOf(peer(5, "CC:00:00:00:00:03", token = 0x3000))))
    }

    @Test
    fun `two phones with unknown own addresses still converge to one group`() {
        // Reproduces the Samsung field case (Galaxy A54/A55): neither phone ever
        // learns its own P2P address — the system reports a placeholder. The election
        // must not depend on it: both sides rank by (node id, token) only.
        val a54 = controller(55_885, 0x1111)
        val a55 = controller(58_081, 0x2222) // higher id → creator
        start(a54)
        start(a55)

        now += 1_000
        var creates = 0
        val aActions = feed(a54, listOf(peer(58_081, "BB:00:00:00:00:02", token = 0x2222)))
        val bActions = feed(a55, listOf(peer(55_885, "AA:00:00:00:00:01", token = 0x1111)))
        creates += aActions.count { it is AutoMeshAction.CreateGroup } +
            bActions.count { it is AutoMeshAction.CreateGroup }

        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.CreateGroup), bActions) // higher id creates
        assertTrue(aActions.isEmpty()) // lower id waits for the takeover window

        now += 1_000
        a55.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.OWNER))
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
            feed(a54, listOf(peer(58_081, "BB:00:00:00:00:02", owner = true, token = 0x2222))))

        now += 1_000
        a54.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))

        repeat(30) { tick(a54); tick(a55) }
        assertEquals("exactly one create", 1, creates)
        assertTrue(a55.state == AutoMeshState.Owner)
        assertTrue(a54.state == AutoMeshState.Client)
    }

    @Test
    fun `three phones starting at different times converge to one group`() {
        val a = controller(2, 0x1000)
        val b = controller(7, 0x2000)
        val c = controller(5, 0x3000)

        start(b) // t=0
        now += 3_000; start(a) // t=3 s
        now += 3_000; start(c) // t=6 s

        now += 1_000
        var creates = 0
        val bActions = feed(b, listOf(peer(2, "AA:00:00:00:00:01"), peer(5, "CC:00:00:00:00:03")))
        creates += bActions.count { it is AutoMeshAction.CreateGroup }
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.CreateGroup), bActions) // highest id (7) creates

        assertTrue(feed(a, listOf(peer(7, "BB:00:00:00:00:02"), peer(5, "CC:00:00:00:00:03"))).isEmpty())
        assertTrue(feed(c, listOf(peer(2, "AA:00:00:00:00:01"), peer(7, "BB:00:00:00:00:02"))).isEmpty())

        now += 1_000
        b.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.OWNER))
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
            feed(a, listOf(peer(7, "BB:00:00:00:00:02", owner = true), peer(5, "CC:00:00:00:00:03"))))
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
            feed(c, listOf(peer(2, "AA:00:00:00:00:01"), peer(7, "BB:00:00:00:00:02", owner = true))))

        now += 1_000
        a.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))
        c.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))

        repeat(20) { tick(a); tick(b); tick(c) }
        assertEquals(1, creates)
    }

    // --- takeover ------------------------------------------------------------------

    @Test
    fun `takeover creates a group when the elected creator never appears`() {
        val a = controller(5, 0x1000)
        start(a)

        now += 1_000
        // B has the higher id but never creates (auto off / manual mode).
        assertTrue(feed(a, listOf(peer(9, "BB:00:00:00:00:02"))).isEmpty())
        assertTrue(a.state is AutoMeshState.Searching)

        var createdTick = -1
        for (i in 1..30) {
            if (tick(a).contains(AutoMeshAction.CreateGroup)) {
                createdTick = i
                break
            }
        }
        // Base 20 s + jitter 0–5 s, measured from the first sighting at t=1 s.
        assertTrue("created on tick $createdTick", createdTick in 20..25)
        assertTrue(a.state is AutoMeshState.Creating)
    }

    @Test
    fun `flapping peers do not cause repeated creates`() {
        val a = controller(5, 0x1000) // B (id 9) flaps; A is never the max while B is visible
        start(a)

        var creates = 0
        fun feedAndCount(peers: List<AirMeshPeer>) {
            creates += feed(a, peers).count { it is AutoMeshAction.CreateGroup }
        }

        // Three flap cycles: present ~5 s, absent ~1 s. Each gap resets the takeover deadline.
        repeat(3) {
            now += 1_000; feedAndCount(listOf(peer(9, "BB:00:00:00:00:02")))
            repeat(4) { tick(a) }
            now += 1_000; feedAndCount(emptyList()) // disappears → deadline reset
        }
        assertEquals("no create while the peer flaps", 0, creates)

        // B stays gone: rule (d) — keep searching, never create.
        repeat(30) { tick(a) }
        assertEquals(0, creates)
        assertTrue(a.state is AutoMeshState.Searching)

        // If B reappears and stays without creating, A takes over exactly once after the window.
        now += 1_000; feedAndCount(listOf(peer(9, "BB:00:00:00:00:02")))
        for (i in 1..30) {
            val actions = tick(a) // takeover fires from a Tick, so count its actions too
            creates += actions.count { it is AutoMeshAction.CreateGroup }
            if (actions.contains(AutoMeshAction.CreateGroup)) break
        }
        assertEquals("exactly one create overall", 1, creates)
    }

    // --- group loss and re-election --------------------------------------------------

    @Test
    fun `when the owner leaves the remaining phones re-elect exactly one new owner`() {
        for (seed in 1L..20L) {
            now = 0L
            val a = controller(2, 0x1000, seed)
            val c = controller(5, 0x3000, seed) // B (id 7, owner) is not a controller here
            start(a)
            start(c)

            now += 1_000
            assertEquals(listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
                feed(a, listOf(peer(7, "BB:00:00:00:00:02", owner = true), peer(5, "CC:00:00:00:00:03"))))
            assertEquals(listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
                feed(c, listOf(peer(2, "AA:00:00:00:00:01"), peer(7, "BB:00:00:00:00:02", owner = true))))

            now += 1_000
            a.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))
            c.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))

            // The owner leaves; both wait out their (seed-dependent) 1–3 s backoff.
            now += 5_000
            a.onEvent(AutoMeshEvent.GroupGone)
            c.onEvent(AutoMeshEvent.GroupGone)
            assertTrue(a.state is AutoMeshState.Backoff)
            assertTrue(c.state is AutoMeshState.Backoff)

            var creates = 0
            while (a.state is AutoMeshState.Backoff || c.state is AutoMeshState.Backoff) {
                tick(a); tick(c)
            }
            assertTrue(a.state is AutoMeshState.Searching)
            assertTrue(c.state is AutoMeshState.Searching)

            // B is gone: A sees only C, C sees only A. C (id 5) is the new max → creates once.
            creates += feed(a, listOf(peer(5, "CC:00:00:00:00:03"))).count { it is AutoMeshAction.CreateGroup }
            val cActions = feed(c, listOf(peer(2, "AA:00:00:00:00:01")))
            creates += cActions.count { it is AutoMeshAction.CreateGroup }
            assertEquals(listOf<AutoMeshAction>(AutoMeshAction.CreateGroup), cActions)

            now += 1_000
            c.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.OWNER))
            val aJoin = feed(a, listOf(peer(5, "CC:00:00:00:00:03", owner = true)))
            creates += aJoin.count { it is AutoMeshAction.CreateGroup }
            assertEquals(listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("CC:00:00:00:00:03")), aJoin)

            now += 1_000
            a.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))
            repeat(25) { tick(a); tick(c) }
            assertEquals("seed $seed created twice", 1, creates)
        }
    }

    @Test
    fun `a lost group waits one to three seconds before searching again`() {
        for (seed in 1L..10L) {
            now = 0L
            val a = controller(5, 0x1000, seed)
            start(a)
            now += 1_000
            feed(a, listOf(peer(9, "BB:00:00:00:00:02", owner = true)))
            a.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))

            now += 1_000
            assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), a.onEvent(AutoMeshEvent.GroupGone))
            val backoff = a.state as AutoMeshState.Backoff
            assertTrue("seed $seed: ${backoff.untilMs}", backoff.untilMs in (now + 1_000)..(now + 3_000))

            var waitedTicks = 0
            var lastActions = emptyList<AutoMeshAction>()
            while (a.state is AutoMeshState.Backoff) {
                lastActions = tick(a)
                waitedTicks++
            }
            assertTrue("seed $seed: waited $waitedTicks ticks", waitedTicks in 1..3)
            assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), lastActions)
            assertTrue(a.state is AutoMeshState.Searching)
        }
    }

    // --- failures and backoff ---------------------------------------------------------

    @Test
    fun `failure delays escalate 2s 4s 8s 16s then cap at 30s plus jitter`() {
        val r = Random(42)
        assertEquals(2_000L, autoMeshFailureDelayMs(1, r))
        assertEquals(4_000L, autoMeshFailureDelayMs(2, r))
        assertEquals(8_000L, autoMeshFailureDelayMs(3, r))
        assertEquals(16_000L, autoMeshFailureDelayMs(4, r))
        repeat(50) { i ->
            val delay = autoMeshFailureDelayMs(7, Random(i.toLong()))
            assertTrue("seed $i: $delay", delay in 30_000L..35_000L)
        }
    }

    @Test
    fun `repeated failures escalate the backoff`() {
        val a = controller(5, 0x1000)
        start(a)
        now += 1_000
        feed(a, listOf(peer(9, "BB:00:00:00:00:02", owner = true))) // Joining

        now += 1_000
        a.onEvent(AutoMeshEvent.ConnectFailed(AutoFailureReason.OTHER))
        val first = a.state as AutoMeshState.Backoff
        assertEquals(now + 2_000L, first.untilMs)

        tick(a) // +1 s: still inside the 2 s backoff
        assertTrue(a.state is AutoMeshState.Backoff)
        tick(a) // +1 s: due → search again
        assertTrue(a.state is AutoMeshState.Searching)

        feed(a, listOf(peer(9, "BB:00:00:00:00:02", owner = true))) // rejoin
        a.onEvent(AutoMeshEvent.ConnectFailed(AutoFailureReason.OTHER))
        val second = a.state as AutoMeshState.Backoff
        assertEquals(now + 4_000L, second.untilMs) // escalated to 4 s

        // A group formed by other means resets the failure counter (group-gone then uses its own wait).
        a.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))
        now += 10_000
        a.onEvent(AutoMeshEvent.GroupGone)
        val gone = a.state as AutoMeshState.Backoff
        assertTrue(gone.untilMs in (now + 1_000)..(now + 3_000)) // not the escalated failure delay
    }

    @Test
    fun `BUSY triggers removeGroup and exactly one retry`() {
        val a = controller(5, 0x1000)
        start(a)
        now += 1_000
        feed(a, listOf(peer(9, "BB:00:00:00:00:02", owner = true))) // Joining(B)

        now += 1_000
        val first = a.onEvent(AutoMeshEvent.ConnectFailed(AutoFailureReason.BUSY))
        assertEquals(
            listOf<AutoMeshAction>(AutoMeshAction.RemoveGroup, AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
            first,
        )
        assertTrue(a.state is AutoMeshState.Joining) // still joining the same target

        val second = a.onEvent(AutoMeshEvent.ConnectFailed(AutoFailureReason.BUSY)) // retry also BUSY → normal path
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), second)
        assertTrue(a.state is AutoMeshState.Backoff)
    }

    @Test
    fun `a join that never forms a group is cancelled after the timeout`() {
        val a = controller(5, 0x1000)
        start(a)
        now += 1_000
        feed(a, listOf(peer(9, "BB:00:00:00:00:02", owner = true))) // Joining at t=1 s

        var actions = emptyList<AutoMeshAction>()
        for (i in 1..45) {
            actions = tick(a)
            if (a.state is AutoMeshState.Backoff) break
            assertTrue("tick $i acted early", i < 40 || actions.isEmpty())
        }
        val backoff = a.state as? AutoMeshState.Backoff
        assertNotNull(backoff)
        assertTrue(backoff!!.reason.contains("timed out"))
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), actions)
    }

    // --- permissions and manual pause ---------------------------------------------------

    @Test
    fun `missing permissions keep the controller idle with a visible reason`() {
        val a = controller(5, 0x1000)
        assertTrue(a.onEvent(AutoMeshEvent.PermissionsChanged(false, "NEARBY_WIFI_DEVICES not granted")).isEmpty())
        assertTrue(a.state is AutoMeshState.Idle)
        assertEquals("NEARBY_WIFI_DEVICES not granted", a.idleReason)

        // Peers and ticks do nothing while idle.
        now += 1_000
        assertTrue(feed(a, listOf(peer(9, "BB:00:00:00:00:02", owner = true))).isEmpty())
        tick(a)

        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), a.onEvent(AutoMeshEvent.PermissionsChanged(true)))
        assertTrue(a.state is AutoMeshState.Searching)
        assertNull(a.idleReason)
    }

    @Test
    fun `losing permissions mid-search cancels the search`() {
        val a = controller(5, 0x1000)
        start(a)
        now += 1_000
        feed(a, listOf(peer(9, "BB:00:00:00:00:02"))) // Searching with a takeover deadline pending

        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery),
            a.onEvent(AutoMeshEvent.PermissionsChanged(false, "location not granted")))
        assertTrue(a.state is AutoMeshState.Idle)
    }

    @Test
    fun `manual mode pauses the controller until auto is re-enabled`() {
        val a = controller(5, 0x1000)
        start(a)
        now += 1_000
        assertTrue(feed(a, listOf(peer(9, "BB:00:00:00:00:02"))).isEmpty()) // waiting for takeover (not the max id)

        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), a.setAutoEnabled(false))
        assertTrue(a.state is AutoMeshState.Idle)

        // While paused: peers and ticks are ignored, even past the takeover window.
        now += 1_000
        assertTrue(feed(a, listOf(peer(9, "BB:00:00:00:00:02", owner = true))).isEmpty())
        repeat(30) { tick(a) }
        assertTrue(a.state is AutoMeshState.Idle)

        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), a.setAutoEnabled(true))
        assertTrue(a.state is AutoMeshState.Searching)

        now += 1_000
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
            feed(a, listOf(peer(9, "BB:00:00:00:00:02", owner = true))))
    }

    @Test
    fun `a group formed while paused is still accepted`() {
        val a = controller(5, 0x1000)
        start(a) // Searching
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery), a.setAutoEnabled(false)) // pause stops discovery

        now += 1_000
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StopDiscovery),
            a.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))) // manual connect succeeded while auto was off
        assertTrue(a.state is AutoMeshState.Client)
    }

    // --- banner text --------------------------------------------------------------------

    @Test
    fun `banner text matches each state`() {
        assertNull(autoMeshBannerText(AutoMeshState.Idle, 0, null, 0))
        assertEquals("Wi-Fi is disabled", autoMeshBannerText(AutoMeshState.Idle, 0, "Wi-Fi is disabled", 0))
        assertEquals("Searching for nearby AirMesh phones…",
            autoMeshBannerText(AutoMeshState.Searching(0), 0, null, 5_000))
        assertEquals("Creating a group — waiting for another phone to join",
            autoMeshBannerText(AutoMeshState.Creating, 0, null, 0))

        val joining = AutoMeshState.Joining("BB:00:00:00:00:02", 9, 1_000)
        assertEquals("Connecting to #9…", autoMeshBannerText(joining, 0, null, 3_000)) // elapsed 2 s ≤ 3 s
        assertEquals("Waiting for the other phone to accept the connection request",
            autoMeshBannerText(joining, 0, null, 5_001)) // elapsed 4.01 s > 3 s

        assertEquals("Connected · 2 neighbors", autoMeshBannerText(AutoMeshState.Owner, 2, null, 0))
        assertEquals("Connected · 0 neighbors", autoMeshBannerText(AutoMeshState.Client, 0, null, 0))

        val backoff = AutoMeshState.Backoff(14_500, "connect failed (other)")
        assertEquals("Retrying in 5s — connect failed (other)", autoMeshBannerText(backoff, 0, null, 10_000)) // ceil(4.5 s)
    }

    // --- "no AirMesh peers" hint banner ---------------------------------------------

    @Test
    fun `hint appears only after 45s of searching with classic devices but no AirMesh peers`() {
        val searching = AutoMeshState.Searching(0)
        assertNull(noAirMeshPeersBannerText(searching, 44_999, 0, 3)) // not yet 45 s
        assertEquals("Found 3 Wi-Fi Direct devices but none advertises AirMesh. Open Advanced to connect manually.",
            noAirMeshPeersBannerText(searching, NO_AIRMESH_PEERS_AFTER_MS, 0, 3))
    }

    @Test
    fun `hint uses the singular form for one device`() {
        assertEquals("Found 1 Wi-Fi Direct device but none advertises AirMesh. Open Advanced to connect manually.",
            noAirMeshPeersBannerText(AutoMeshState.Searching(0), NO_AIRMESH_PEERS_AFTER_MS, 0, 1))
    }

    @Test
    fun `hint is suppressed when not searching, when an AirMesh peer exists, or when nothing is visible`() {
        val t = NO_AIRMESH_PEERS_AFTER_MS
        assertNull(noAirMeshPeersBannerText(AutoMeshState.Idle, t, 0, 3))
        assertNull(noAirMeshPeersBannerText(AutoMeshState.Creating, t, 0, 3))
        assertNull(noAirMeshPeersBannerText(AutoMeshState.Searching(0), t, 1, 3)) // an AirMesh peer is visible
        assertNull(noAirMeshPeersBannerText(AutoMeshState.Searching(0), t, 0, 0)) // nothing at all: plain searching banner
    }
}
