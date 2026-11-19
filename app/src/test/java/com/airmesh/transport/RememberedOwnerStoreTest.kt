package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK T1 part C1: the persistence arithmetic of [RememberedOwnerStore] — round-trips through a
 * fake storage, expires after 24 h (and clears itself), dies after three failed attempts, and
 * treats any corruption as "no record".
 */
class RememberedOwnerStoreTest {

    private class InMemoryStorage(initialRaw: String? = null) : RememberedOwnerStorage {
        @Volatile var raw: String? = initialRaw
            private set
        override fun readRaw(): String? = raw
        override fun write(raw: String) {
            this.raw = raw
        }
    }

    private class FakeClock(var nowMs: Long = 1_000_000L)

    @Test
    fun recordRoundTripsThroughStorage() {
        val storage = InMemoryStorage()
        val clock = FakeClock(5_000L)
        val store = RememberedOwnerStore(storage, { clock.nowMs })

        assertNull("nothing recorded yet", store.current())
        store.record("aa:bb:cc:dd:ee:ff", 42)

        assertEquals(
            "the record comes back with all fields intact",
            RememberedOwnerStore.Record("aa:bb:cc:dd:ee:ff", 42, 5_000L, 0, null),
            store.current(),
        )

        // A brand-new process (new store instance) sees the same record from the same storage.
        val restarted = RememberedOwnerStore(storage, { clock.nowMs })
        assertEquals("the record survives a restart", "aa:bb:cc:dd:ee:ff", restarted.current()?.deviceAddress)
    }

    @Test
    fun recordExpiresAfterTwentyFourHoursAndClearsItself() {
        val storage = InMemoryStorage()
        val clock = FakeClock(0L)
        val store = RememberedOwnerStore(storage, { clock.nowMs })
        store.record("aa:bb:cc:dd:ee:ff", 7)

        clock.nowMs = RememberedOwnerStore.MAX_RECORD_AGE_MS - 1
        assertEquals("just under the age limit is still valid", 7, store.current()?.nodeId)

        clock.nowMs = RememberedOwnerStore.MAX_RECORD_AGE_MS
        assertNull("at exactly the age limit the record is expired", store.current())
        assertNull(
            "expiry clears the storage so a later restart starts clean",
            RememberedOwnerStore(storage, { clock.nowMs }).current(),
        )
    }

    @Test
    fun threeFailedAttemptsClearTheRecord() {
        val storage = InMemoryStorage()
        val store = RememberedOwnerStore(storage) { 0L }
        store.record("aa:bb:cc:dd:ee:ff", 7)

        store.noteFailure()
        assertEquals("one failure is remembered, record still alive", 1, store.current()?.failedAttempts)
        store.noteFailure()
        assertEquals("two failures are remembered, record still alive", 2, store.current()?.failedAttempts)
        store.noteFailure()
        assertNull(
            "the third failed attempt clears the record (MAX_FAILED_ATTEMPTS)",
            store.current(),
        )
    }

    @Test
    fun refreshSuccessResetsTheAttemptBookkeepingAndTimestamp() {
        val storage = InMemoryStorage()
        val clock = FakeClock(10_000L)
        val store = RememberedOwnerStore(storage, { clock.nowMs })
        store.record("aa:bb:cc:dd:ee:ff", 7)

        store.noteAttempt(nextAllowedAttemptAtMs = 55_000L)
        store.noteFailure()
        assertEquals("the failure is counted before the success", 1, store.current()?.failedAttempts)

        clock.nowMs = 90_000L
        store.refreshSuccess()
        val record = store.current()
        assertEquals("a successful re-join resets the failure count", 0, record?.failedAttempts)
        assertNull("the attempt pacing is reset too", record?.nextAllowedAttemptAtMs)
        assertEquals("the timestamp refreshes to the success time", 90_000L, record?.recordedAtMs)
    }

    @Test
    fun noteAttemptStampsTheNextAllowedTime() {
        val storage = InMemoryStorage()
        val store = RememberedOwnerStore(storage) { 0L }
        store.record("aa:bb:cc:dd:ee:ff", 7)

        assertNull("no attempt yet, so no pacing stamp", store.current()?.nextAllowedAttemptAtMs)
        store.noteAttempt(nextAllowedAttemptAtMs = 123_456L)
        assertEquals(123_456L, store.current()?.nextAllowedAttemptAtMs)
        assertEquals("the attempt does not count as a failure", 0, store.current()?.failedAttempts)
    }

    @Test
    fun corruptStorageIsTreatedAsNoRecord() {
        for (raw in listOf("", "garbage", "aa:bb|1|2", "aa:bb:cc:dd:ee:ff|notanint|5|0|-1", "aa:bb:cc:dd:ee:ff|7|5|99|-1")) {
            val store = RememberedOwnerStore(InMemoryStorage(raw)) { 0L }
            assertNull("raw=$raw must decode to no record", store.current())
        }
    }

    @Test
    fun mutatingMethodsAreNoOpsWithoutARecord() {
        val storage = InMemoryStorage()
        val store = RememberedOwnerStore(storage) { 0L }
        // None of these may throw or invent a record when nothing is stored.
        store.noteAttempt(nextAllowedAttemptAtMs = 1L)
        store.noteFailure()
        store.refreshSuccess()
        assertNull("still no record", store.current())
    }
}
