package com.airmesh.routing

/**
 * Bounded duplicate-suppression cache keyed by `(nodeId, seq)`.
 *
 * In a flood, the same frame can reach a node through several paths, so the
 * router must remember which frames it has already handled. Keeping every key
 * forever would grow without bound on a phone that runs for days, so this is a
 * fixed-capacity LRU cache (1024 entries by default).
 *
 * [seen] is synchronized, so its check-and-record step is atomic even when
 * frames arrive from several transport threads at once.
 *
 * Trade-off: if more than [capacity] distinct frames arrive before a duplicate
 * of a very old frame shows up, that duplicate can be delivered again.
 */
class DedupCache(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        /** Default number of remembered `(nodeId, seq)` pairs. */
        const val DEFAULT_CAPACITY = 1024
    }

    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    /**
     * Identity of a frame for duplicate suppression: the originator's node id
     * plus its per-node sequence number.
     *
     * TTL is deliberately not part of the key: every flooded copy of one frame
     * carries a different TTL but must still be recognised as the same frame.
     */
    private data class Key(val nodeId: Int, val seq: Int)

    /** Number of [seen] calls that found an already-known frame. Telemetry only. */
    var duplicateCount: Long = 0L
        private set

    // Insertion-order map. Every hit removes and re-inserts the key, which
    // moves it to the newest end; the eldest entry is the least recently used.
    private val entries = object : LinkedHashMap<Key, Boolean>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Boolean>): Boolean =
            size > capacity
    }

    /**
     * Records the frame identity and returns whether it had already been seen.
     *
     * A hit also refreshes the entry's LRU position, so busy originators are not
     * evicted while idle ones age out. Thread-safe: the check-and-record is
     * atomic, so exactly one of several concurrent callers sees a new key.
     */
    @Synchronized
    fun seen(nodeId: Int, seq: Int): Boolean {
        val key = Key(nodeId, seq)
        val alreadySeen = entries.remove(key) != null
        // Insert (or re-insert) as the most recently used entry.
        entries[key] = true
        if (alreadySeen) duplicateCount++
        return alreadySeen
    }
}
