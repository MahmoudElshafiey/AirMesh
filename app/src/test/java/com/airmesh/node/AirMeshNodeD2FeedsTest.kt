package com.airmesh.node

import com.airmesh.protocol.FrameType
import com.airmesh.routing.InMemoryPacketStore
import com.airmesh.routing.StoreAndForward
import com.airmesh.transport.TcpMeshTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the TASK D2 part 3 node additions: the nodeActivity /
 * deliveredContent / sendResults event feeds, the "User #id" anonymous profile
 * fallback (announceWithoutName), and the rule that PROFILE frames are forwarded
 * but never stored. Real localhost transports, same harness as
 * [AirMeshNodeProfileTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AirMeshNodeD2FeedsTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val POLL_MS = 5L
    }

    /** Shared virtual clock for the real-dispatcher tests; advanced manually. */
    private var now = 1_700_000_000_000L
    private val clock: () -> Long = { now }

    private fun newNode(id: Int): AirMeshNode =
        AirMeshNode(
            localNodeId = id,
            transport = TcpMeshTransport(localNodeId = id),
            eventLog = EventLog(clock = clock),
            storeFactory = { send -> StoreAndForward(store = InMemoryPacketStore(), send = send) },
            clock = clock,
        )

    @Test
    fun profileFramesAreForwardedButNeverStored() = runBlocking {
        // Capture the packet store so we can inspect what got queued.
        var captured: InMemoryPacketStore? = null
        val a = AirMeshNode(
            localNodeId = 1,
            transport = TcpMeshTransport(localNodeId = 1),
            eventLog = EventLog(clock = clock),
            storeFactory = { send ->
                val store = InMemoryPacketStore()
                captured = store
                StoreAndForward(store = store, send = send)
            },
            clock = clock,
        )
        val b = newNode(2)

        try {
            a.start()
            b.start()

            // No neighbors: the PROFILE frame has nobody to reach and must NOT be
            // queued for replay (a stale name would re-flood later).
            assertTrue(a.setMyProfile("Alice", 3))
            assertTrue(captured!!.all.isEmpty())

            // Forwarding still works: when B appears, A announces fresh and B learns.
            val port = a.transport.listen(0)
            b.transport.connect(HOST, port)
            awaitUntil("B learns A's name after the link up") {
                b.profiles.value[1]?.name == "Alice"
            }
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun nodeActivityIsKeyedByTheOriginNodeNotTheRelay() = runBlocking {
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

            // Collect on both ends before sending; the collector is subscribed as
            // soon as launch returns (runBlocking starts it up to first suspend).
            val seenByC = ArrayList<NodeActivity>()
            val jobC = launch { c.nodeActivity.collect { seenByC += it } }
            val seenByB = ArrayList<NodeActivity>()
            val jobB = launch { b.nodeActivity.collect { seenByB += it } }
            collectorsReady()

            assertTrue(a.sendText("hello through B"))

            awaitUntil("C saw activity from origin 1") { seenByC.any { it.nodeId == 1 } }
            // Every frame C decoded carries origin 1 in its header, even the ones
            // that physically arrived over B's socket.
            assertTrue(seenByC.all { it.nodeId == 1 })
            awaitUntil("B also saw activity from origin 1") { seenByB.any { it.nodeId == 1 } }

            jobC.cancel()
            jobB.cancel()
        } finally {
            a.stop()
            b.stop()
            c.stop()
        }
    }

    @Test
    fun deliveredContentCarriesTheRealSeqForTextLocationAndDirect() = runBlocking {
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

            val seen = ArrayList<DeliveredContent>()
            val job = launch { b.deliveredContent.collect { seen += it } }
            collectorsReady()

            val textSeq = a.sendTextWithSeq("hi mesh")
            assertTrue(textSeq > 0)
            awaitUntil("B got the MSG content") { seen.any { it.type == FrameType.MSG } }
            val msg = seen.first { it.type == FrameType.MSG }
            assertEquals(1, msg.originNode)
            assertEquals(textSeq, msg.seq)
            assertEquals("hi mesh", msg.text)

            val locSeq = a.sendLocationWithSeq(latitudeE6 = 30_000_000, longitudeE6 = 31_250_000, accuracyMeters = 7)
            assertTrue(locSeq > 0)
            awaitUntil("B got the LOCATION content") { seen.any { it.type == FrameType.LOCATION } }
            val loc = seen.first { it.type == FrameType.LOCATION }
            assertEquals(locSeq, loc.seq)
            assertNull(loc.text)
            assertEquals(30_000_000, loc.latitudeE6)
            assertEquals(31_250_000, loc.longitudeE6)
            assertEquals(7, loc.accuracyMeters)

            val dm = a.sendDirect(destNodeId = 2, text = "psst")!!
            awaitUntil("B got the DIRECT content") { seen.any { it.type == FrameType.DIRECT } }
            val direct = seen.first { it.type == FrameType.DIRECT }
            assertEquals(dm.seq, direct.seq)
            assertEquals(1, direct.originNode)
            assertEquals("psst", direct.text)

            job.cancel()
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun sendResultsReportHandedForDirectAndLocation() = runBlocking {
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

            val seen = ArrayList<SendResult>()
            val job = launch { a.sendResults.collect { seen += it } }
            collectorsReady()

            val dm = a.sendDirect(destNodeId = 2, text = "hi")!!
            awaitUntil("A reported the DIRECT send") { seen.any { it.type == FrameType.DIRECT } }
            val directResult = seen.first { it.type == FrameType.DIRECT }
            assertEquals(2, directResult.destNode)
            assertEquals(dm.seq, directResult.seq)
            assertTrue(directResult.handed)

            a.sendLocation(latitudeE6 = 1, longitudeE6 = 2, accuracyMeters = 3)
            awaitUntil("A reported the LOCATION send") { seen.any { it.type == FrameType.LOCATION } }
            val locResult = seen.first { it.type == FrameType.LOCATION }
            assertNull(locResult.destNode) // locations belong to the mesh room
            assertTrue(locResult.handed)

            job.cancel()
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun queuedSendsReportNotHanded() = runBlocking {
        val c = newNode(3) // no neighbors at all: every send is queued for replay

        try {
            c.start()

            val seen = ArrayList<SendResult>()
            val job = launch { c.sendResults.collect { seen += it } }
            collectorsReady()

            val dm = c.sendDirect(destNodeId = 9, text = "later")!!
            awaitUntil("C reported the queued DIRECT send") { seen.any { it.type == FrameType.DIRECT } }
            assertFalse(seen.first { it.type == FrameType.DIRECT }.handed)

            c.sendLocation(latitudeE6 = 1, longitudeE6 = 2, accuracyMeters = 3)
            awaitUntil("C reported the queued LOCATION send") { seen.any { it.type == FrameType.LOCATION } }
            assertFalse(seen.first { it.type == FrameType.LOCATION }.handed)

            job.cancel()
        } finally {
            c.stop()
        }
    }

    @Test
    fun announceWithoutNameAnnouncesTheUserFallbackAndHonorsTheInterval() = runBlocking {
        // A's node logic runs on an unconfined test dispatcher (so its collectors
        // can be driven by the virtual scheduler); B stays on real time.
        val scheduler = TestCoroutineScheduler()
        val a = AirMeshNode(
            localNodeId = 1,
            transport = TcpMeshTransport(localNodeId = 1),
            eventLog = EventLog(clock = { scheduler.currentTime }),
            storeFactory = { send -> StoreAndForward(store = InMemoryPacketStore(), send = send) },
            clock = { scheduler.currentTime },
            dispatcher = UnconfinedTestDispatcher(scheduler),
            profileAnnounceIntervalMs = 30_000L,
            announceWithoutName = true,
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

            // The neighbor-up trigger announces the anonymous fallback right away.
            awaitUntilAdvancing(scheduler, "B learns the User #1 fallback name") {
                b.profiles.value[1]?.name == "User #1"
            }
            assertEquals(1, a.events.value.count { it.text.contains("originated PROFILE") })

            // One replay tick (5s) is far inside the 30s interval: no re-announce.
            scheduler.advanceTimeBy(AirMeshNode.DEFAULT_REPLAY_INTERVAL_MS)
            assertEquals(1, a.events.value.count { it.text.contains("originated PROFILE") })

            // Cross the interval plus one more tick: the gate opens and A re-announces.
            scheduler.advanceTimeBy(30_000L + AirMeshNode.DEFAULT_REPLAY_INTERVAL_MS)
            awaitUntilAdvancing(scheduler, "A re-announces periodically") {
                a.events.value.count { it.text.contains("originated PROFILE") } >= 2
            }
        } finally {
            a.stop()
            b.stop()
        }
    }

    @Test
    fun withoutTheFlagANamelessNodeNeverAnnounces() = runBlocking {
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

            // Advance well past the default 120s interval: with no name and no
            // fallback flag, nothing may be announced.
            scheduler.advanceTimeBy(AirMeshNode.DEFAULT_PROFILE_ANNOUNCE_INTERVAL_MS + 60_000L)
            assertEquals(0, a.events.value.count { it.text.contains("originated PROFILE") })
            assertTrue(b.profiles.value.isEmpty())
        } finally {
            a.stop()
            b.stop()
        }
    }

    /**
     * Pumps the runBlocking event loop once so a just-launched collector is
     * subscribed before we trigger emissions: launch only enqueues its task, and
     * a fast IO-thread tryEmit with no subscriber yet would be dropped (replay=0).
     */
    private suspend fun collectorsReady() = delay(20)

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
