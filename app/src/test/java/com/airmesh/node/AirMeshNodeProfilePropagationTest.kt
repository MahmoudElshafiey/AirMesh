package com.airmesh.node

import com.airmesh.profile.InMemoryProfileStorage
import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.StoreAndForward
import com.airmesh.transport.TcpMeshTransport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for profile propagation through the planner wiring (TASK R2 part 4): real
 * localhost transports, same harness as [AirMeshNodeProfileTest], but with SHORT planner
 * delays passed through the node constructor so the neighbor-up burst and the new-origin
 * re-announce land within milliseconds on real time. Covers: a peer learns the name inside
 * the burst window; in an A-B-C line where C joins later, C learns A's name through A's
 * new-origin re-announce; a changed name replaces the old one immediately; and no PROFILE
 * frames are announced while no name is set or while the node is isolated (no neighbor).
 */
class AirMeshNodeProfilePropagationTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val POLL_MS = 5L
        /** Short burst so "within the window" assertions stay fast on real time. */
        val BURST_DELAYS_MS = listOf(50L, 150L)
        /** Short jitter for the new-origin re-announce (same reason). */
        val NEW_ORIGIN_JITTER_RANGE_MS = 30L..80L
    }

    private fun newNode(
        id: Int,
        replayIntervalMs: Long = AirMeshNode.DEFAULT_REPLAY_INTERVAL_MS,
        profileAnnounceIntervalMs: Long = AirMeshNode.DEFAULT_PROFILE_ANNOUNCE_INTERVAL_MS,
    ): AirMeshNode =
        AirMeshNode(
            localNodeId = id,
            transport = TcpMeshTransport(localNodeId = id),
            eventLog = EventLog(clock = System::currentTimeMillis),
            storeFactory = { send -> StoreAndForward(store = InMemoryPacketStore(), send = send) },
            clock = System::currentTimeMillis,
            profileStorage = InMemoryProfileStorage(),
            // TASK R2 part 4: short planner delays through the constructor parameters.
            profileBurstDelaysMs = BURST_DELAYS_MS,
            profileNewOriginJitterRangeMs = NEW_ORIGIN_JITTER_RANGE_MS,
            replayIntervalMs = replayIntervalMs,
            profileAnnounceIntervalMs = profileAnnounceIntervalMs,
        )

    @Test
    fun aPeerLearnsTheNameWithinTheBurstWindow() = runBlocking {
        val a = newNode(1)
        val b = newNode(2)

        try {
            a.start()
            b.start()

            // A's name is set before the link exists: nothing can be on the wire yet.
            assertTrue(a.setMyProfile("Alice", 3))

            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("A and B handshake") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }

            // The neighbor-up burst (50/150ms here) must deliver the name well inside
            // the 2s bound — the periodic re-announce is 120s away, so anything B
            // learns this fast came from the burst.
            val startedAt = System.currentTimeMillis()
            awaitUntil("B learns A's name") { b.profiles.value[1]?.name == "Alice" }
            val elapsedMs = System.currentTimeMillis() - startedAt
            assertTrue(
                "B learned A's name in ${elapsedMs}ms; expected within the 50/150ms burst",
                elapsedMs < 2_000L,
            )
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun aLateJoinerLearnsAnExistingNameThroughTheNewOriginTrigger() = runBlocking {
        val a = newNode(1)
        val b = newNode(2) // relay in the middle of the A-B-C line
        val c = newNode(3)

        try {
            a.start()
            b.start()

            assertTrue(a.setMyProfile("Alice", 2))

            // B listens once; A connects first (the proven hub pattern). C joins later.
            val portB = b.transport.listen(0)
            a.transport.connect(HOST, portB)
            awaitUntil("A and B handshake") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }
            awaitUntil("B learns Alice") { b.profiles.value[1]?.name == "Alice" }

            // Let A's whole burst settle, then count its announces: whatever C triggers
            // on top of this is the new-origin re-announce.
            delay(500L)
            val announcesBeforeC = a.events.value.count { it.text.contains("originated PROFILE") }

            c.start()
            // C carries its own name so that joining floods traffic from an origin A does
            // not know yet — that is what trips A's new-origin trigger.
            assertTrue(c.setMyProfile("Carol", 4))
            c.transport.connect(HOST, portB)
            awaitUntil("C and B handshake") {
                b.neighbors.value == setOf(1, 3) && c.neighbors.value == setOf(2)
            }

            // C cannot learn Alice from any stored frame (profiles are never stored): the
            // only path is A re-announcing after it sees Carol's frames.
            awaitUntil("C learns Alice through A's new-origin re-announce") {
                c.profiles.value[1]?.name == "Alice"
            }
            val announcesAfterC = a.events.value.count { it.text.contains("originated PROFILE") }
            assertTrue(
                "A must have re-announced after seeing C (before=$announcesBeforeC, after=$announcesAfterC)",
                announcesAfterC > announcesBeforeC,
            )
        } finally {
            a.stop()
            b.stop()
            c.stop()
        }
    }

    @Test
    fun aChangedNameReplacesTheOldOneImmediately() = runBlocking {
        val a = newNode(1)
        val b = newNode(2)

        try {
            a.start()
            b.start()

            assertTrue(a.setMyProfile("Alice", 3))

            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("A and B handshake") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }
            awaitUntil("B shows Alice") { b.profiles.value[1]?.name == "Alice" }

            // Rename right away (no window has passed anywhere): the change is accepted
            // immediately and B must show it without any periodic re-announce.
            assertTrue(a.setMyProfile("Alicia", 5))
            awaitUntil("B shows Alicia") { b.profiles.value[1]?.name == "Alicia" }
            assertEquals(5, b.profiles.value[1]!!.colorIndex)
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun noProfileIsAnnouncedWhileNoNameIsSet() = runBlocking {
        val a = newNode(1)
        val b = newNode(2)

        try {
            a.start()
            b.start()

            // No name is set on either side. The link comes up and the whole burst window
            // elapses — every announce attempt must be a no-op, so nothing is originated.
            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("A and B handshake") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }
            delay(500L) // covers the 50/150ms burst plus scheduling slack

            assertEquals(
                "no PROFILE may be originated while no name is set",
                0,
                a.events.value.count { it.text.contains("originated PROFILE") },
            )
            assertTrue(b.profiles.value.isEmpty())

            // Setting the name now announces immediately and B learns it.
            assertTrue(a.setMyProfile("Alice", 3))
            awaitUntil("B learns Alice after the late set") { b.profiles.value[1]?.name == "Alice" }
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun anIsolatedNodeDoesNotPeriodicallyAnnounce() = runBlocking {
        // Short replay/periodic cadence so several periodic ticks would fit in the wait.
        val a = newNode(1, replayIntervalMs = 100L, profileAnnounceIntervalMs = 200L)
        val b = newNode(2)

        try {
            a.start()
            b.start()

            // A is alone: setting the name originates exactly one frame (the set trigger),
            // which goes to zero neighbors.
            assertTrue(a.setMyProfile("Alice", 3))
            assertEquals(1, a.events.value.count { it.text.contains("originated PROFILE") })

            // The periodic loop ticks every 100ms with a 200ms interval: while no neighbor
            // is live it must stay silent.
            delay(700L)
            assertEquals(
                "no periodic announce may fire while isolated",
                1,
                a.events.value.count { it.text.contains("originated PROFILE") },
            )

            // Once B connects, the name reaches it (burst or first periodic tick).
            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("B learns Alice after A was isolated") {
                b.profiles.value[1]?.name == "Alice"
            }
        } finally {
            a.stop()
            b.stop()
        }
    }

    private suspend fun awaitUntil(
        description: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(timeoutMs) {
                while (!condition()) delay(POLL_MS)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("timed out after ${timeoutMs}ms waiting for $description", timeout)
        }
    }
}
