package com.airmesh.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameType
import com.airmesh.routing.StoreAndForward
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented checks for the real Room layer.
 *
 * Run with a connected device or emulator:
 * `./gradlew connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class RoomPacketStoreTest {

    private lateinit var database: AirMeshDatabase
    private lateinit var store: StoreAndForward

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AirMeshDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // The private RoomPacketStore is reachable only through this factory.
        store = createRoomBackedStore(
            database = database,
            send = { _, _ -> true },
            clock = { NOW },
            maxAgeMs = 60_000,
            maxStoreSize = 100,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveAndReadBackFrame() = runBlocking {
        val frame = frame(seq = 1, payload = "round-trip")

        store.save(frame, deliveredToNeighbors = emptySet(), createdAt = 1_000)

        val read = store.pendingFor(neighborId = 42, notBefore = 0).single()
        assertEquals(1_000L, read.createdAt)
        assertEquals(frame.version, read.frame.version)
        assertEquals(frame.type, read.frame.type)
        assertEquals(frame.flags, read.frame.flags)
        assertEquals(frame.nodeId, read.frame.nodeId)
        assertEquals(frame.seq, read.frame.seq)
        assertEquals(frame.ttl, read.frame.ttl)
        assertArrayEquals(frame.payload, read.frame.payload)
        assertTrue(read.deliveredToNeighbors.isEmpty())
    }

    @Test
    fun deliveredNeighborsRoundTrip() = runBlocking {
        store.save(frame(seq = 1), deliveredToNeighbors = emptySet(), createdAt = 1_000)
        store.save(frame(seq = 2), deliveredToNeighbors = setOf(2, 10, 65535), createdAt = 1_000)

        assertEquals(
            listOf(1),
            store.pendingFor(neighborId = 2, notBefore = 0).map { it.frame.seq },
        )

        val deliveredFrame = store.pendingFor(neighborId = 3, notBefore = 0)
            .single { it.frame.seq == 2 }
        assertEquals(setOf(2, 10, 65535), deliveredFrame.deliveredToNeighbors)

        assertTrue(store.isDelivered(nodeId = 7, seq = 2, neighborId = 2))
        assertTrue(store.isDelivered(nodeId = 7, seq = 2, neighborId = 10))
        assertTrue(store.isDelivered(nodeId = 7, seq = 2, neighborId = 65535))
        assertFalse(store.isDelivered(nodeId = 7, seq = 2, neighborId = 3))
    }

    @Test
    fun expiredFramesAreRemoved() = runBlocking {
        store.save(frame(seq = 1), deliveredToNeighbors = emptySet(), createdAt = 1_000)
        store.save(frame(seq = 2), deliveredToNeighbors = emptySet(), createdAt = 2_000)

        store.deleteOlderThan(notBefore = 1_500)

        assertEquals(
            listOf(2),
            store.pendingFor(neighborId = 1, notBefore = 0).map { it.frame.seq },
        )
        assertEquals(
            listOf(2),
            store.pendingFor(neighborId = 1, notBefore = 1_500).map { it.frame.seq },
        )
    }

    @Test
    fun moreFramesThanMaxSizeDropsOldest() = runBlocking {
        val limited = createRoomBackedStore(
            database = database,
            send = { _, _ -> true },
            clock = { NOW },
            maxAgeMs = 60_000,
            maxStoreSize = 2,
        )

        repeat(4) { index ->
            limited.save(
                frame(seq = index + 1),
                deliveredToNeighbors = emptySet(),
                createdAt = 1_000L + index,
            )
        }

        assertEquals(
            listOf(3, 4),
            limited.pendingFor(neighborId = 1, notBefore = 0).map { it.frame.seq },
        )
    }

    @Test
    fun identicalCreatedAtEvictsInInsertionOrder() = runBlocking {
        // Both frames share a timestamp; the auto-generated id decides order.
        store.save(frame(seq = 1), deliveredToNeighbors = emptySet(), createdAt = 5_000)
        store.save(frame(seq = 2), deliveredToNeighbors = emptySet(), createdAt = 5_000)

        store.trimToSize(maxSize = 1)

        assertEquals(
            listOf(2),
            store.pendingFor(neighborId = 1, notBefore = 0).map { it.frame.seq },
        )
    }

    @Test
    fun resavingExistingFrameKeepsDeliveredSetAndAge() = runBlocking {
        val frame = frame(seq = 9)
        store.save(frame, deliveredToNeighbors = setOf(1, 2), createdAt = 1_000)

        // Re-save with a later (but still non-expired) timestamp and no ids.
        store.save(frame, deliveredToNeighbors = emptySet(), createdAt = 2_000)

        assertTrue(store.isDelivered(nodeId = 7, seq = 9, neighborId = 1))
        assertTrue(store.isDelivered(nodeId = 7, seq = 9, neighborId = 2))

        // createdAt stayed at 1_000, so the frame is outside a later cutoff.
        assertTrue(store.pendingFor(neighborId = 3, notBefore = 1_500).isEmpty())

        // New delivery progress is merged into the existing row.
        store.save(frame, deliveredToNeighbors = setOf(3), createdAt = 2_500)
        assertTrue(store.isDelivered(nodeId = 7, seq = 9, neighborId = 3))
        assertTrue(store.pendingFor(neighborId = 3, notBefore = 0).isEmpty())
    }

    private fun frame(
        seq: Int,
        nodeId: Int = 7,
        payload: String = "payload-$seq",
    ): Frame = Frame(
        version = 1,
        type = FrameType.MSG,
        flags = 0,
        nodeId = nodeId,
        seq = seq,
        ttl = 5,
        payload = payload.toByteArray(),
    )

    private companion object {
        const val NOW = 10_000L
    }
}
