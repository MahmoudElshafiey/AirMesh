package com.airmesh.transport

/**
 * TASK T1 part C1: persistence for the remembered-owner record. Pure Kotlin so the expiry and
 * attempt-count arithmetic is unit-testable; the app supplies a SharedPreferences-backed
 * implementation, tests an in-memory one.
 */
interface RememberedOwnerStorage {
    /** Raw stored text; null when nothing has been recorded yet. */
    fun readRaw(): String?

    /** Persists [raw] durably (the record must survive a crash). */
    fun write(raw: String)
}

/**
 * TASK T1 part C1: remembers the Wi-Fi Direct owner this phone last joined, so auto-mesh can
 * re-join it after a restart even when DNS-SD stays silent (evidence run2-A54/A55: the client
 * saw its owner in requestPeers for 97 s without a single DNS-SD callback).
 *
 * The record holds the owner's P2P device address (what manager.connect() needs), its node id,
 * and when it was recorded. It is cleared after [MAX_FAILED_ATTEMPTS] failed re-join attempts or
 * once it is older than [MAX_RECORD_AGE_MS]; the policy layer applies a tighter freshness window
 * on top of that. The record is encoded as one pipe-separated string so any corruption makes the
 * whole thing unreadable, which is treated as "no record".
 */
class RememberedOwnerStore(
    private val storage: RememberedOwnerStorage,
    /** Injectable clock (fake in tests). */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /** A record older than this is dropped on read. */
        const val MAX_RECORD_AGE_MS = 24 * 60 * 60_000L

        /** The record is cleared once this many re-join attempts have failed. */
        const val MAX_FAILED_ATTEMPTS = 3
    }

    /** The remembered owner plus its attempt bookkeeping. */
    data class Record(
        val deviceAddress: String,
        val nodeId: Int,
        val recordedAtMs: Long,
        val failedAttempts: Int,
        /** Earliest time the next attempt may start; null before the first attempt. */
        val nextAllowedAttemptAtMs: Long?,
    )

    /** (Re)records [deviceAddress] as our owner; resets the attempt bookkeeping. */
    @Synchronized
    fun record(deviceAddress: String, nodeId: Int) {
        storage.write(encode(deviceAddress, nodeId, clock(), 0, null))
    }

    /** The current record, or null when none is stored, corrupt, or expired (expiry clears it). */
    @Synchronized
    fun current(): Record? {
        val record = decode(storage.readRaw()) ?: return null
        if (clock() - record.recordedAtMs >= MAX_RECORD_AGE_MS) {
            storage.write("") // an empty string decodes to null, so this is a durable clear
            return null
        }
        return record
    }

    /** Stamps that an attempt is starting; [nextAllowedAttemptAtMs] paces the next one. */
    @Synchronized
    fun noteAttempt(nextAllowedAttemptAtMs: Long) {
        val record = decode(storage.readRaw()) ?: return
        storage.write(encode(record.deviceAddress, record.nodeId, record.recordedAtMs, record.failedAttempts, nextAllowedAttemptAtMs))
    }

    /** A re-join attempt failed; clears the record once [MAX_FAILED_ATTEMPTS] have failed. */
    @Synchronized
    fun noteFailure() {
        val record = decode(storage.readRaw()) ?: return
        val failures = record.failedAttempts + 1
        if (failures >= MAX_FAILED_ATTEMPTS) {
            storage.write("")
        } else {
            storage.write(encode(record.deviceAddress, record.nodeId, record.recordedAtMs, failures, record.nextAllowedAttemptAtMs))
        }
    }

    /** A re-join landed: refresh the timestamp and reset the attempt bookkeeping. */
    @Synchronized
    fun refreshSuccess() {
        val record = decode(storage.readRaw()) ?: return
        storage.write(encode(record.deviceAddress, record.nodeId, clock(), 0, null))
    }

    private fun encode(address: String, nodeId: Int, recordedAtMs: Long, failures: Int, nextAllowed: Long?): String =
        listOf(address, nodeId.toString(), recordedAtMs.toString(), failures.toString(), (nextAllowed ?: -1L).toString())
            .joinToString("|")

    private fun decode(raw: String?): Record? {
        if (raw == null) return null
        val parts = raw.trim().split("|")
        if (parts.size != 5) return null
        val address = parts[0]
        if (address.isEmpty()) return null
        val nodeId = parts[1].toIntOrNull() ?: return null
        val recordedAtMs = parts[2].toLongOrNull() ?: return null
        val failures = parts[3].toIntOrNull() ?: return null
        if (failures !in 0..MAX_FAILED_ATTEMPTS) return null
        val nextAllowed = parts[4].toLongOrNull()?.takeIf { it >= 0L }
        return Record(address, nodeId, recordedAtMs, failures, nextAllowed)
    }
}
