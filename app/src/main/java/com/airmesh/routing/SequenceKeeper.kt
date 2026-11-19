package com.airmesh.routing

/**
 * TASK T1 part B2: persistence for the origin-sequence high-water mark.
 *
 * The keeper is pure Kotlin (no Android imports) so its restart arithmetic is unit-testable;
 * the app supplies a SharedPreferences-backed implementation, tests an in-memory one.
 */
interface SequenceStorage {
    /** Raw stored text; null when nothing has been persisted yet. */
    fun readRaw(): String?

    /** Persists [value] durably (the mark must survive a crash). */
    fun write(value: Int)
}

/**
 * TASK T1 part B2: keeps the node's origin sequence from ever going backwards across a
 * process restart.
 *
 * The stored value is a HIGH-WATER MARK, not "last used": it always runs [PERSIST_MARGIN]
 * ahead of the newest allocated seq. On start the node resumes at `stored + [START_GAP]`, so
 * the first frame after any restart (crash or clean stop) carries a number that is strictly
 * ahead — modulo 65536 — of every number this node has ever sent, and therefore cannot be in
 * any peer's dedup window (which only contains numbers actually seen on the wire). With the
 * defaults the first post-restart seq is at least 82 numbers beyond the last one used.
 *
 * The Router stays the single allocator: pass [initialSeq] to its constructor and feed every
 * allocation back through [onAllocated]; the keeper persists `(current + PERSIST_MARGIN)`
 * every [PERSIST_EVERY] allocations and again on a clean [stop]. Missing or corrupt storage
 * starts the node at seq 1, exactly like before this class existed.
 */
class SequenceKeeper(
    private val storage: SequenceStorage,
    /** Injectable clock (fake in tests); stamps each persist for diagnostics. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val MAX_SEQ = 0xFFFF

        /** Resume this far above the stored mark so a restart never reuses recent numbers. */
        const val START_GAP = 64

        /** Persist after every N allocations (bounds how much progress a crash can lose). */
        const val PERSIST_EVERY = 16

        /** The persisted value runs this many seqs ahead of the last allocated one. */
        const val PERSIST_MARGIN = 32
    }

    /** Value to pass as the Router's `initialSeq`; the first frame will be this + 1. */
    val initialSeq: Int

    private var lastSeq: Int
    private var allocationsSincePersist = 0

    /** Fake-clock-stamped time of the last persist; null while nothing was persisted yet. */
    @Volatile
    var lastPersistedAtMs: Long? = null
        private set

    init {
        // Missing or corrupt storage means "never sent anything": start at seq 1, exactly like
        // before this class existed. A stored mark of 0 is VALID (a wrapped persist) and gets
        // the normal +gap resume.
        val stored = loadHighWaterMarkOrNull()
        initialSeq = if (stored == null) 0 else (stored + START_GAP) and MAX_SEQ
        lastSeq = initialSeq
    }

    /**
     * Called by the Router for every allocated seq, in order. Persists the mark after every
     * [PERSIST_EVERY] allocations so a crash loses at most that many numbers of progress.
     */
    @Synchronized
    fun onAllocated(seq: Int) {
        require(seq in 0..MAX_SEQ) { "seq must fit in 16 bits: $seq" }
        lastSeq = seq
        allocationsSincePersist++
        if (allocationsSincePersist >= PERSIST_EVERY) persist()
    }

    /** Clean stop: persist the mark even when fewer than [PERSIST_EVERY] new allocations happened. */
    @Synchronized
    fun stop() {
        persist()
    }

    private fun persist() {
        storage.write((lastSeq + PERSIST_MARGIN) and MAX_SEQ)
        lastPersistedAtMs = clock()
        allocationsSincePersist = 0
    }

    /** Stored high-water mark; null when nothing valid has been persisted yet. */
    private fun loadHighWaterMarkOrNull(): Int? {
        val raw = storage.readRaw()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val value = raw.toIntOrNull() ?: return null
        return if (value in 0..MAX_SEQ) value else null
    }
}
