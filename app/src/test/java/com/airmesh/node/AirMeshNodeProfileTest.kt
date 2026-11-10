package com.airmesh.node

import com.airmesh.profile.InMemoryProfileStorage
import com.airmesh.profile.ProfileDirectory
import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.StoreAndForward
import com.airmesh.transport.TcpMeshTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for display names through the node: real localhost transports, same
 * harness as [AirMeshNodeLocationTest]. Covers exactly-once delivery, multi-hop
 * A-B-C propagation, name changes, the neighbor-up and 120s periodic
 * announcements (the latter on a virtual-time scheduler), the peer's 10s update
 * window, and malformed-payload drops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AirMeshNodeProfileTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val POLL_MS = 5L
    }

    /** Shared virtual clock for the real-dispatcher tests; advanced manually. */
    private var now = 1_700_000_000_000L
    private val clock: () -> Long = { now }

    private fun newNode(id: Int, storage: InMemoryProfileStorage? = null): AirMeshNode =
        AirMeshNode(
            localNodeId = id,
            transport = TcpMeshTransport(localNodeId = id),
            eventLog = EventLog(clock = clock),
            storeFactory = { send -> StoreAndForward(store = InMemoryPacketStore(), send = send) },
            clock = clock,
            profileStorage = storage ?: InMemoryProfileStorage(),
        )

    @Test
    fun nameAppearsInPeerDirectoryExactlyOnce() = runBlocking {
        val storage = InMemoryProfileStorage()
        val a = newNode(1, storage)
        val b = newNode(2)

        try {
            a.start()
            b.start()

            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("A and B handshake") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }

            assertTrue(a.setMyProfile("Alice", 3))
            assertEquals(MyProfile("Alice", 3), a.myProfile.value)
            // The profile is persisted under the documented keys.
            assertEquals("Alice", storage.getString(AirMeshNode.PROFILE_NAME_KEY))
            assertEquals("3", storage.getString(AirMeshNode.PROFILE_COLOR_KEY))

            awaitUntil("B learns A's name") { b.profiles.value[1]?.name == "Alice" }
            assertEquals(3, b.profiles.value[1]!!.colorIndex)

            val duplicated = awaitUntilOrNull(500) {
                b.events.value.count { it.text.contains("delivered PROFILE node=1") } > 1
            }
            assertFalse("B must accept A's profile exactly once", duplicated)
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun profileReachesTheEndOfALine() = runBlocking {
        val a = newNode(1)
        val b = newNode(2) // relay in the middle of the A-B-C line
        val c = newNode(3)

        try {
            a.start()
            b.start()
            c.start()

            // B listens once; both ends connect to it (the proven hub pattern).
            val portB = b.transport.listen(0)
            a.transport.connect(HOST, portB)
            c.transport.connect(HOST, portB)
            awaitUntil("A and C are connected through B") {
                b.neighbors.value == setOf(1, 3) &&
                    a.neighbors.value == setOf(2) &&
                    c.neighbors.value == setOf(2)
            }

            assertTrue(a.setMyProfile("Alice", 2))

            awaitUntil("C learns A's name via B") { c.profiles.value[1]?.name == "Alice" }
            assertEquals(2, c.profiles.value[1]!!.colorIndex)
        } finally {
            a.stop()
            b.stop()
            c.stop()
        }
    }

    @Test
    fun changedNameReplacesTheOldOne() = runBlocking {
        val a = newNode(1)
        val b = newNode(2)

        try {
            a.start()
            b.start()

            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("A and B handshake") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }

            assertTrue(a.setMyProfile("Alice", 3))
            awaitUntil("B shows Alice") { b.profiles.value[1]?.name == "Alice" }

            // Pass B's 10s update window, then change the name.
            now += ProfileDirectory.MIN_UPDATE_INTERVAL_MS + 1_000L
            assertTrue(a.setMyProfile("Alicia", 5))

            awaitUntil("B shows Alicia") { b.profiles.value[1]?.name == "Alicia" }
            assertEquals(5, b.profiles.value[1]!!.colorIndex)
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun profileIsAnnouncedWhenANeighborComesUp() = runBlocking {
        val a = newNode(1)
        val b = newNode(2)

        try {
            a.start()
            b.start()

            // Set the profile before anyone is connected: the frame lands in
            // store-and-forward and nothing is announced yet.
            assertTrue(a.setMyProfile("Alice", 3))
            assertEquals(1, a.events.value.count { it.text.contains("originated PROFILE") })

            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)

            // The neighbor-up trigger must originate a fresh PROFILE frame.
            awaitUntil("A re-announces its profile when B comes up") {
                a.events.value.count { it.text.contains("originated PROFILE") } >= 2
            }
            awaitUntil("B learns A's name after the link up") {
                b.profiles.value[1]?.name == "Alice"
            }
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun profileIsPeriodicallyReannouncedWhileANeighborLives() = runBlocking {
        // A's node logic runs on an unconfined test dispatcher (so setMyProfile,
        // which withContexts onto it, can be called directly) whose delays are
        // virtual: advancing the scheduler by 120s is what opens the re-announce
        // gate. B stays on real time.
        val scheduler = TestCoroutineScheduler()
        val a = AirMeshNode(
            localNodeId = 1,
            transport = TcpMeshTransport(localNodeId = 1),
            eventLog = EventLog(clock = { scheduler.currentTime }),
            storeFactory = { send -> StoreAndForward(store = InMemoryPacketStore(), send = send) },
            clock = { scheduler.currentTime },
            dispatcher = UnconfinedTestDispatcher(scheduler),
        )
        val b = newNode(2)

        try {
            a.start()
            b.start()

            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("A and B handshake") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }

            // Let A's (virtual-time) neighbor collector process the link up. No
            // profile is set yet, so this must not announce anything.
            scheduler.advanceTimeBy(1)
            awaitUntilAdvancing(scheduler, "A logs the neighbor up") {
                a.events.value.any { it.text == "neighbor up: 2" }
            }

            assertTrue(a.setMyProfile("Alice", 3))
            awaitUntilAdvancing(scheduler, "B learns A's name") {
                b.profiles.value[1]?.name == "Alice"
            }
            assertEquals(1, a.events.value.count { it.text.contains("originated PROFILE") })

            // Cross the 120s re-announce interval plus one more 5s tick of the
            // periodic loop: the gate opens at t_set + 120s, and only a tick
            // strictly after that can announce.
            scheduler.advanceTimeBy(
                AirMeshNode.DEFAULT_PROFILE_ANNOUNCE_INTERVAL_MS +
                    AirMeshNode.DEFAULT_REPLAY_INTERVAL_MS,
            )
            awaitUntilAdvancing(scheduler, "A re-announces periodically") {
                a.events.value.count { it.text.contains("originated PROFILE") } >= 2
            }
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun secondProfileUpdateWithinTenSecondsIsIgnoredByThePeer() = runBlocking {
        val a = newNode(1)
        val b = newNode(2)

        try {
            a.start()
            b.start()

            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("A and B handshake") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }

            assertTrue(a.setMyProfile("Alice", 3))
            awaitUntil("B shows Alice") { b.profiles.value[1]?.name == "Alice" }

            // Change the name again before B's 10s window has passed (the clock
            // is not advanced), so B must keep showing the first name.
            assertTrue(a.setMyProfile("Alicia", 5))
            assertEquals(MyProfile("Alicia", 5), a.myProfile.value)

            awaitUntil("B processes and ignores the second announcement") {
                b.events.value.any { it.text.contains("PROFILE from 1 ignored") }
            }
            assertEquals("Alice", b.profiles.value[1]!!.name)
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun malformedProfilePayloadIsDroppedAndTheNodeKeepsWorking() = runBlocking {
        val a = newNode(1)
        val b = newNode(2)

        try {
            a.start()
            b.start()

            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("A and B handshake") {
                a.neighbors.value == setOf(2) && b.neighbors.value == setOf(1)
            }

            // A frame with a valid CRC whose PROFILE payload has an unsupported
            // version byte: the frame decodes fine, but the payload must be
            // rejected. Sent raw so it bypasses A's own router (which would
            // never craft such a frame).
            val badFrame = FrameCodec.encode(
                Frame(
                    version = 1,
                    type = FrameType.PROFILE,
                    flags = 0,
                    nodeId = 1,
                    seq = 7,
                    ttl = AirMeshNode.INITIAL_TTL,
                    payload = byteArrayOf(2, 3) + "x".toByteArray(),
                ),
            )
            assertTrue(a.transport.trySend(2, badFrame))

            awaitUntil("B logs the drop") {
                b.events.value.any { it.text.contains("PROFILE from 1 dropped: UNSUPPORTED_VERSION") }
            }
            assertTrue(b.profiles.value.isEmpty())

            // The node is still alive and accepts a good profile afterwards.
            assertTrue(a.setMyProfile("Alice", 3))
            awaitUntil("B learns A's name after the malformed frame") {
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

    private suspend fun awaitUntilOrNull(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!condition()) delay(POLL_MS)
        true
    } ?: false

    /** Real-time wait that also advances [scheduler] so virtual coroutines run. */
    private suspend fun awaitUntilAdvancing(
        scheduler: TestCoroutineScheduler,
        description: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out after ${timeoutMs}ms waiting for $description")
            }
            scheduler.advanceTimeBy(1)
            delay(POLL_MS)
        }
    }
}
