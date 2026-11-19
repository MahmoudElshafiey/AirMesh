package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * TASK T1 part C2: every precondition of [RememberedOwnerPolicy.consider] — the 12 s searching
 * minimum, record freshness (30 min), the 3-attempt cap, the pacing stamp, and the AVAILABLE
 * radio-peer match — plus deterministic spacing for a seeded Random.
 */
class RememberedOwnerPolicyTest {

    private class InMemoryStorage : RememberedOwnerStorage {
        @Volatile var raw: String? = null
        override fun readRaw(): String? = raw
        override fun write(raw: String) {
            this.raw = raw
        }
    }

    private val storage = InMemoryStorage()
    private var nowMs = 0L
    private val store = RememberedOwnerStore(storage) { nowMs }
    private lateinit var policy: RememberedOwnerPolicy

    @Before
    fun setUp() {
        storage.raw = null
        nowMs = 0L
        policy = RememberedOwnerPolicy(store, { nowMs }, Random(1))
    }

    private val peer = RadioPeer("aa:bb:cc:dd:ee:ff", RadioPeer.AVAILABLE)

    @Test
    fun returnsTargetWhenEveryPreconditionHolds() {
        store.record(peer.deviceAddress, 7) // recorded at t=0; we have been searching since t=0
        nowMs = RememberedOwnerPolicy.MIN_SEARCHING_MS
        val target = policy.consider(nowMs, 0L, listOf(peer))
        assertEquals(
            "the target carries the record's address, node id and attempt number",
            RememberedOwnerPolicy.Target("aa:bb:cc:dd:ee:ff", 7, 1),
            target,
        )
    }

    @Test
    fun noAttemptBeforeTheMinimumSearchingTime() {
        store.record(peer.deviceAddress, 7)
        for (t in 0L until RememberedOwnerPolicy.MIN_SEARCHING_MS step 1_000L) {
            assertNull("at t=${t}ms we have not searched long enough", policy.consider(t, 0L, listOf(peer)))
        }
    }

    @Test
    fun noAttemptWithoutARecord() {
        nowMs = RememberedOwnerPolicy.MIN_SEARCHING_MS
        assertNull(policy.consider(nowMs, 0L, listOf(peer)))
    }

    @Test
    fun noAttemptWhenTheRecordIsStale() {
        store.record(peer.deviceAddress, 7) // recorded at t=0
        nowMs = RememberedOwnerPolicy.RECORD_MAX_AGE_MS - 1
        assertNotNull("just under the age limit is still trusted", policy.consider(nowMs, 0L, listOf(peer)))

        storage.raw = null
        store.record(peer.deviceAddress, 7) // re-record at t=age-1 so only the age changes
        nowMs = RememberedOwnerPolicy.RECORD_MAX_AGE_MS + (RememberedOwnerPolicy.RECORD_MAX_AGE_MS - 1)
        assertNull("at exactly the age limit the record is stale", policy.consider(nowMs, 0L, listOf(peer)))
    }

    @Test
    fun noAttemptAfterThreeFailures() {
        store.record(peer.deviceAddress, 7)
        nowMs = RememberedOwnerPolicy.MIN_SEARCHING_MS
        repeat(3) {
            assertNotNull("attempt is allowed", policy.consider(nowMs, 0L, listOf(peer)))
            policy.noteFailure()
            // Move past the pacing stamp so only the failure count can block the next attempt.
            nowMs += RememberedOwnerPolicy.ATTEMPT_SPACING_MS + RememberedOwnerPolicy.SPACING_JITTER_MAX_MS
        }
        assertNull("the store cleared itself after three failures", store.current())
        assertNull(policy.consider(nowMs, 0L, listOf(peer)))
    }

    @Test
    fun noAttemptBeforeThePacingStampElapses() {
        store.record(peer.deviceAddress, 7)
        nowMs = RememberedOwnerPolicy.MIN_SEARCHING_MS
        assertNotNull("the first attempt is allowed", policy.consider(nowMs, 0L, listOf(peer)))

        // The stamp is now + spacing + jitter (jitter <= max), so this moment is still too early.
        assertNull(
            "immediately after the attempt the pacing stamp blocks a re-attempt",
            policy.consider(nowMs + RememberedOwnerPolicy.ATTEMPT_SPACING_MS - 1, 0L, listOf(peer)),
        )
    }

    @Test
    fun noAttemptWhenThePeerIsMissingOrNotAvailable() {
        store.record(peer.deviceAddress, 7)
        nowMs = RememberedOwnerPolicy.MIN_SEARCHING_MS
        assertNull("no radio peer at all", policy.consider(nowMs, 0L, emptyList()))
        assertNull(
            "a CONNECTED status is not an attempt trigger (we would already be in a group)",
            policy.consider(nowMs, 0L, listOf(RadioPeer(peer.deviceAddress, RadioPeer.CONNECTED))),
        )
        assertNull(
            "another device's address does not match the record",
            policy.consider(nowMs, 0L, listOf(RadioPeer("11:22:33:44:55:66", RadioPeer.AVAILABLE))),
        )
    }

    @Test
    fun spacingIsDeterministicForASeededRandom() {
        store.record(peer.deviceAddress, 7)
        nowMs = RememberedOwnerPolicy.MIN_SEARCHING_MS
        policy.consider(nowMs, 0L, listOf(peer))
        val expectedJitter = Random(1).nextInt(RememberedOwnerPolicy.SPACING_JITTER_MAX_MS.toInt() + 1)
        assertEquals(
            "the stamp is now + spacing + the seeded jitter",
            nowMs + RememberedOwnerPolicy.ATTEMPT_SPACING_MS + expectedJitter,
            store.current()?.nextAllowedAttemptAtMs,
        )

        // A second policy with the same seed stamps identically from a fresh record.
        val otherStorage = InMemoryStorage()
        val otherStore = RememberedOwnerStore(otherStorage) { nowMs }
        otherStore.record(peer.deviceAddress, 7)
        RememberedOwnerPolicy(otherStore, { nowMs }, Random(1)).consider(nowMs, 0L, listOf(peer))
        assertEquals(
            "same seed -> same stamp",
            store.current()?.nextAllowedAttemptAtMs,
            otherStore.current()?.nextAllowedAttemptAtMs,
        )
    }

    @Test
    fun successResetsTheRecordSoAttemptsStartOver() {
        store.record(peer.deviceAddress, 7)
        nowMs = RememberedOwnerPolicy.MIN_SEARCHING_MS
        assertNotNull(policy.consider(nowMs, 0L, listOf(peer)))
        policy.noteFailure()
        assertEquals("the failure is counted", 1, store.current()?.failedAttempts)

        nowMs += RememberedOwnerPolicy.ATTEMPT_SPACING_MS + RememberedOwnerPolicy.SPACING_JITTER_MAX_MS
        val second = policy.consider(nowMs, 0L, listOf(peer))
        assertEquals("the next attempt is numbered #2", 2, second?.attemptNumber)

        nowMs += 1_000L
        policy.noteSuccess()
        assertEquals("success resets the failure count", 0, store.current()?.failedAttempts)
        assertNull("and clears the pacing stamp", store.current()?.nextAllowedAttemptAtMs)
    }
}
