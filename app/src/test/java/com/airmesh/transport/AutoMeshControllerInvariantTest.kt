package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * JVM invariant tests for [AutoMeshController] with a fake clock and seeded [Random] (Part 4).
 */
class AutoMeshControllerInvariantTest {

    private fun peer(nodeId: Int, address: String, owner: Boolean = false, token: Int = 0x2000) =
        AirMeshPeer(address, nodeId, owner, token)

    @Test
    fun `(i) highest-ranked phone creates a group immediately when it sees a peer regardless of visibility on the other side`() {
        var now = 0L
        val lower = AutoMeshController(
            myNodeId = 6469,
            tieBreakToken = 0x1111,
            clock = { now },
            random = Random(42L),
        )
        val higher = AutoMeshController(
            myNodeId = 38010,
            tieBreakToken = 0x2222,
            clock = { now },
            random = Random(42L),
        )

        lower.onEvent(AutoMeshEvent.PermissionsChanged(true))
        higher.onEvent(AutoMeshEvent.PermissionsChanged(true))

        // Higher-ranked phone sees lower-ranked phone at t=500 ms while lower-ranked phone sees nothing.
        now = 500L
        val lowerActions = lower.onEvent(AutoMeshEvent.PeersChanged(emptyList()))
        val higherActions = higher.onEvent(
            AutoMeshEvent.PeersChanged(listOf(peer(6469, "AA:00:00:00:00:01", owner = false, token = 0x1111))),
        )

        assertTrue(lowerActions.isEmpty())
        assertTrue(lower.state is AutoMeshState.Searching)
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.CreateGroup), higherActions)
        assertEquals(AutoMeshState.Creating, higher.state)

        // Same invariant when nodeIds are equal and rank is decided by the tie-break token.
        val tieLow = AutoMeshController(7777, 0x1000, { now }, Random(7L)).apply {
            onEvent(AutoMeshEvent.PermissionsChanged(true))
        }
        val tieHigh = AutoMeshController(7777, 0x9000, { now }, Random(7L)).apply {
            onEvent(AutoMeshEvent.PermissionsChanged(true))
        }
        assertEquals(
            listOf<AutoMeshAction>(AutoMeshAction.CreateGroup),
            tieHigh.onEvent(AutoMeshEvent.PeersChanged(listOf(peer(7777, "AA:00:00:00:00:01", token = 0x1000)))),
        )
        assertTrue(tieLow.state is AutoMeshState.Searching)
    }

    @Test
    fun `(ii) lower-ranked phone that sees only a non-owner higher-ranked peer does NOT create a group before 20 s`() {
        for (seed in 1L..50L) {
            var now = 1_000L
            val lower = AutoMeshController(
                myNodeId = 6469,
                tieBreakToken = 0x1111,
                clock = { now },
                random = Random(seed),
            )
            lower.onEvent(AutoMeshEvent.PermissionsChanged(true))

            // Sighting at t = 1_000 ms; 20 s base window ends at 21_000 ms.
            val firstSightingMs = now
            val initialActions = lower.onEvent(
                AutoMeshEvent.PeersChanged(listOf(peer(38010, "BB:00:00:00:00:02", owner = false, token = 0x2222))),
            )
            assertTrue("seed $seed must not create on initial sighting", initialActions.isEmpty())

            // Step through every second up to +19,999 ms: must NEVER emit CreateGroup before 20 s.
            var t = firstSightingMs + 1_000L
            while (t < firstSightingMs + AUTO_TAKEOVER_BASE_MS) {
                now = t
                val actions = lower.onEvent(AutoMeshEvent.Tick)
                assertFalse("seed $seed created early at +${t - firstSightingMs}ms", actions.contains(AutoMeshAction.CreateGroup))
                assertTrue(lower.state is AutoMeshState.Searching)
                t += 1_000L
            }
            now = firstSightingMs + AUTO_TAKEOVER_BASE_MS - 1L // +19,999 ms exact boundary
            assertFalse(
                "seed $seed created at 19_999 ms",
                lower.onEvent(AutoMeshEvent.Tick).contains(AutoMeshAction.CreateGroup),
            )
            assertTrue(lower.state is AutoMeshState.Searching)

            // Between +20,000 ms and +25,000 ms (base 20 s + 0..5 s jitter), takeover creates once.
            var createdAtDeltaMs: Long? = null
            for (delta in AUTO_TAKEOVER_BASE_MS..(AUTO_TAKEOVER_BASE_MS + AUTO_JITTER_MAX_MS)) {
                now = firstSightingMs + delta
                if (lower.onEvent(AutoMeshEvent.Tick).contains(AutoMeshAction.CreateGroup)) {
                    createdAtDeltaMs = delta
                    break
                }
            }
            assertTrue(
                "seed $seed takeover delta $createdAtDeltaMs must be in 20_000..25_000",
                createdAtDeltaMs != null && createdAtDeltaMs in 20_000L..25_000L,
            )
        }
    }

    @Test
    fun `(iii) asymmetric visibility A sees B at 15 s and B sees A at 42 s ends with exactly one group and one owner`() {
        for (seed in 1L..25L) {
            var now = 0L
            // A is lower-ranked (6469), B is higher-ranked (38010).
            val a = AutoMeshController(6469, 0x1111, { now }, Random(seed))
            val b = AutoMeshController(38010, 0x2222, { now }, Random(seed + 100L))
            a.onEvent(AutoMeshEvent.PermissionsChanged(true))
            b.onEvent(AutoMeshEvent.PermissionsChanged(true))

            var totalCreates = 0

            // 0..14 s: neither sees the other.
            while (now < 15_000L) {
                now += 1_000L
                totalCreates += a.onEvent(AutoMeshEvent.Tick).count { it is AutoMeshAction.CreateGroup }
                totalCreates += b.onEvent(AutoMeshEvent.Tick).count { it is AutoMeshAction.CreateGroup }
            }

            // At t = 15 s: A sees B (non-owner), B still sees nothing.
            now = 15_000L
            val aAt15 = a.onEvent(
                AutoMeshEvent.PeersChanged(listOf(peer(38010, "BB:00:00:00:00:02", owner = false, token = 0x2222))),
            )
            totalCreates += aAt15.count { it is AutoMeshAction.CreateGroup }
            assertEquals("A waits for takeover at 15 s", 0, totalCreates)

            // Tick from 16 s to 41 s: A's takeover fires in [35 s..40 s] and A becomes Owner.
            var aIsOwner = false
            while (now < 41_000L) {
                now += 1_000L
                val aTick = a.onEvent(AutoMeshEvent.Tick)
                val bTick = b.onEvent(AutoMeshEvent.Tick)
                totalCreates += aTick.count { it is AutoMeshAction.CreateGroup }
                totalCreates += bTick.count { it is AutoMeshAction.CreateGroup }
                if (aTick.contains(AutoMeshAction.CreateGroup)) {
                    a.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.OWNER))
                    aIsOwner = true
                }
            }
            assertTrue("A must have taken over by 40 s", aIsOwner)
            assertEquals(1, totalCreates)

            // At t = 42 s: B finally sees A (now advertising owner = true). B joins A instead of creating!
            now = 42_000L
            val bAt42 = b.onEvent(
                AutoMeshEvent.PeersChanged(listOf(peer(6469, "AA:00:00:00:00:01", owner = true, token = 0x1111))),
            )
            totalCreates += bAt42.count { it is AutoMeshAction.CreateGroup }
            assertEquals(listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("AA:00:00:00:00:01")), bAt42)
            b.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))

            assertEquals("seed $seed must create exactly one group", 1, totalCreates)
            val owners = listOf(a.state, b.state).count { it == AutoMeshState.Owner }
            val clients = listOf(a.state, b.state).count { it == AutoMeshState.Client }
            assertEquals(1, owners)
            assertEquals(1, clients)
        }
    }

    @Test
    fun `(iv) new startup shortcuts never cause a group creation earlier than before`() {
        for (seed in 1L..25L) {
            var nowBaseline = 0L
            var nowShortcut = 0L

            val baseline = AutoMeshController(6469, 0x1111, { nowBaseline }, Random(seed))
            val shortcut = AutoMeshController(6469, 0x1111, { nowShortcut }, Random(seed))

            baseline.onEvent(AutoMeshEvent.PermissionsChanged(true))
            // Shortcut controller goes through stale group -> IntentionalGroupGone at startup.
            shortcut.onEvent(AutoMeshEvent.PermissionsChanged(true))
            shortcut.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.OWNER))
            shortcut.onEvent(AutoMeshEvent.IntentionalGroupGone)

            // Peer arrives at t = 1_500 ms for both.
            val peerSightingMs = 1_500L
            nowBaseline = peerSightingMs
            nowShortcut = peerSightingMs
            val nonOwnerHigher = listOf(peer(38010, "BB:00:00:00:00:02", owner = false, token = 0x2222))
            assertTrue(baseline.onEvent(AutoMeshEvent.PeersChanged(nonOwnerHigher)).isEmpty())
            assertTrue(shortcut.onEvent(AutoMeshEvent.PeersChanged(nonOwnerHigher)).isEmpty())

            var baselineCreatedMs: Long? = null
            var shortcutCreatedMs: Long? = null
            for (t in (peerSightingMs + 1L)..(peerSightingMs + 26_000L)) {
                nowBaseline = t
                nowShortcut = t
                if (baselineCreatedMs == null && baseline.onEvent(AutoMeshEvent.Tick).contains(AutoMeshAction.CreateGroup)) {
                    baselineCreatedMs = t
                }
                if (shortcutCreatedMs == null && shortcut.onEvent(AutoMeshEvent.Tick).contains(AutoMeshAction.CreateGroup)) {
                    shortcutCreatedMs = t
                }
                if (baselineCreatedMs != null && shortcutCreatedMs != null) break
            }

            assertEquals("seed $seed shortcut must not create earlier than baseline", baselineCreatedMs, shortcutCreatedMs)
            assertTrue(shortcutCreatedMs!! >= peerSightingMs + AUTO_TAKEOVER_BASE_MS)
        }
    }

    @Test
    fun `(v) stale-group cleanup followed by discovery does not create a group until a peer is seen`() {
        var now = 0L
        val controller = AutoMeshController(
            myNodeId = 38010, // even as a high nodeId, must never create without a visible peer
            tieBreakToken = 0x2222,
            clock = { now },
            random = Random(99L),
        )
        val backend = object : DiscoveryBackend {
            var scans = 0
            override fun clearServiceRequests() = Unit
            override fun addServiceRequest() = Unit
            override fun discoverServices() { scans++ }
            override fun discoverPeers() = Unit
        }
        val coordinator = DiscoveryCoordinator(
            backend = backend,
            strategySelector = DiscoveryStrategySelector.forService(),
            clock = { now },
        )

        // Startup with a stale group that is intentionally removed at t = 200 ms.
        controller.onEvent(AutoMeshEvent.PermissionsChanged(true))
        controller.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.OWNER))
        now = 200L
        val cleanupActions = controller.onEvent(AutoMeshEvent.IntentionalGroupGone)
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.StartDiscovery), cleanupActions)
        assertTrue(controller.state is AutoMeshState.Searching)
        coordinator.onEnterSearching(nowMs = now)

        // Run through the entire burst (+0.5 s, +6 s, +14 s) and 3 minutes of discovery with no peers seen.
        while (now < 180_000L) {
            now += 500L
            coordinator.tick(nowMs = now, serviceWanted = true)
            val tickActions = controller.onEvent(AutoMeshEvent.Tick)
            assertFalse("must never create a group while no peer is seen (t=$now)", tickActions.contains(AutoMeshAction.CreateGroup))
            assertTrue(controller.state is AutoMeshState.Searching)
        }
        assertTrue("discovery scans ran during the search window", backend.scans >= 4)

        // Only when an AirMesh peer is finally seen does the controller create a group.
        now = 181_000L
        val peerSeenActions = controller.onEvent(
            AutoMeshEvent.PeersChanged(listOf(peer(6469, "AA:00:00:00:00:01", owner = false, token = 0x1111))),
        )
        assertEquals(listOf<AutoMeshAction>(AutoMeshAction.CreateGroup), peerSeenActions)
        assertEquals(AutoMeshState.Creating, controller.state)
    }
}
