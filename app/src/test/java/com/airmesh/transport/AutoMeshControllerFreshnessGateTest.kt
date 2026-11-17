package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * JVM tests for the post-loss advert-freshness join gate (TASK R3 part 2): fake clock, seeded
 * Random. After an unexpected group loss, owner adverts seen before the loss are not joined until
 * a new scan re-confirms them; adverts older than 30 s are never trusted; forming a group clears
 * the gate; and election among non-owner peers is untouched by the filter.
 */
class AutoMeshControllerFreshnessGateTest {

    private var now = 0L
    private val clock: () -> Long = { now }

    private fun controller(nodeId: Int, token: Int = 0x1000, seed: Long = 1) =
        AutoMeshController(nodeId, token, clock, Random(seed), true)

    private fun peer(nodeId: Int, address: String, owner: Boolean = false, seenAtMs: Long? = null) =
        AirMeshPeer(address, nodeId, owner, 0x2000, lastSeenMs = seenAtMs ?: 0L)

    private fun start(c: AutoMeshController): List<AutoMeshAction> =
        c.onEvent(AutoMeshEvent.PermissionsChanged(true))

    /** Advances the fake clock and ticks. */
    private fun tick(c: AutoMeshController, ms: Long = 1_000L): List<AutoMeshAction> {
        now += ms
        return c.onEvent(AutoMeshEvent.Tick)
    }

    private fun feed(c: AutoMeshController, peers: List<AirMeshPeer>): List<AutoMeshAction> =
        c.onEvent(AutoMeshEvent.PeersChanged(peers))

    /** Fires an unexpected GroupGone at [now] and waits out the backoff into Searching. */
    private fun loseGroup(c: AutoMeshController) {
        c.onEvent(AutoMeshEvent.GroupGone)
        while (c.state is AutoMeshState.Backoff) tick(c)
        assertTrue("expected Searching after the group-gone backoff, got ${c.state}", c.state is AutoMeshState.Searching)
    }

    /** Joins owner #7, forms as client, then loses the group unexpectedly at t = 3 s. */
    private fun setUpLoss(c: AutoMeshController) {
        start(c) // Searching
        now = 1_000L
        assertEquals(
            listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
            feed(c, listOf(peer(7, "BB:00:00:00:00:02", owner = true, seenAtMs = 1_000L))),
        )
        now = 2_000L
        c.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))
        now = 3_000L
        loseGroup(c) // loss recorded at 3 s; we are Searching inside the post-loss creation grace
    }

    @Test
    fun anOwnerAdvertSeenBeforeTheLossIsNotJoined() {
        val c = controller(5)
        setUpLoss(c)
        val actions = feed(c, listOf(peer(7, "BB:00:00:00:00:02", owner = true, seenAtMs = 2_500L)))
        assertTrue("pre-loss advert must not be joined: $actions", actions.none { it is AutoMeshAction.ConnectTo })
        assertTrue("still inside the grace: no create either: $actions", actions.none { it is AutoMeshAction.CreateGroup })
        assertTrue(c.state is AutoMeshState.Searching)
    }

    @Test
    fun anOwnerAdvertReconfirmedAfterTheLossIsJoined() {
        val c = controller(5)
        setUpLoss(c)
        assertEquals(
            listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
            feed(c, listOf(peer(7, "BB:00:00:00:00:02", owner = true, seenAtMs = now))), // a new scan re-confirmed it
        )
    }

    @Test
    fun anOwnerAdvertOlderThan30sIsNotJoinedEvenAfterTheLoss() {
        val c = controller(5)
        setUpLoss(c) // loss at 3 s
        now += 40_000L // well past the grace; no peers fed since, so we stayed Searching
        val actions = feed(c, listOf(peer(7, "BB:00:00:00:00:02", owner = true, seenAtMs = 8_000L)))
        assertTrue("a ~36 s old advert must not be joined: $actions", actions.none { it is AutoMeshAction.ConnectTo })
        assertTrue(c.state is AutoMeshState.Searching)
    }

    @Test
    fun aFreshStartupJoinsAnOldOwnerAdvertWithoutAnyLoss() {
        val c = controller(5)
        start(c)
        now = 50_000L
        assertEquals(
            listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
            feed(c, listOf(peer(7, "BB:00:00:00:00:02", owner = true, seenAtMs = 1_000L))), // no loss ever: gate off
        )
    }

    @Test
    fun theGateDoesNotChangeElectionAmongNonOwners() {
        val c = controller(5) // not the highest id once #9 is visible
        setUpLoss(c)
        now += POST_LOSS_CREATION_GRACE_MS + 2_000L // past the grace: rule (c) applies on the FULL list
        val actions = feed(c, listOf(
            peer(7, "BB:00:00:00:00:02", owner = true, seenAtMs = 2_500L), // untrusted owner: not joined
            peer(9, "DD:00:00:00:00:04", seenAtMs = 2_600L),               // pre-loss non-owner: still counts for election
            peer(3, "EE:00:00:00:00:05", seenAtMs = now),                   // a fresh non-owner
        ))
        assertTrue("untrusted owner must not be joined: $actions", actions.none { it is AutoMeshAction.ConnectTo })
        assertTrue("#9 outranks us, so no takeover create yet: $actions", actions.none { it is AutoMeshAction.CreateGroup })
        assertTrue(c.state is AutoMeshState.Searching)
    }

    @Test
    fun formingAGroupClearsTheLossTimeAndALaterLossRearmsIt() {
        val c = controller(5)
        start(c)
        now = 1_000L
        feed(c, listOf(peer(7, "BB:00:00:00:00:02", owner = true, seenAtMs = 1_000L))) // Joining
        now = 2_000L
        c.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))
        now = 3_000L
        loseGroup(c) // first loss at T1 = 3 s

        // Rejoin the re-confirmed owner; forming the group clears the gate.
        assertEquals(
            listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
            feed(c, listOf(peer(7, "BB:00:00:00:00:02", owner = true, seenAtMs = now))),
        )
        now += 1_000L
        c.onEvent(AutoMeshEvent.GroupFormed(AutoMeshRole.CLIENT))

        // Second unexpected loss at T2.
        val t2 = now + 1_000L
        now = t2
        loseGroup(c)

        // An advert stamped between T1 and T2 was fresh for the first gate but is pre-loss for the
        // second: it must NOT be joined (proves the field moved to T2, not stuck at T1).
        val actions = feed(c, listOf(peer(7, "BB:00:00:00:00:02", owner = true, seenAtMs = t2 - 500L)))
        assertTrue("a pre-T2 advert must not be joined after the second loss: $actions", actions.none { it is AutoMeshAction.ConnectTo })

        // Re-confirmed again by a new scan: joined.
        assertEquals(
            listOf<AutoMeshAction>(AutoMeshAction.ConnectTo("BB:00:00:00:00:02")),
            feed(c, listOf(peer(7, "BB:00:00:00:00:02", owner = true, seenAtMs = now))),
        )
    }
}
