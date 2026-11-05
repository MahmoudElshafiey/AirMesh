package com.airmesh.node

import kotlin.random.Random

/** Persistence contract for the node id, injectable so tests stay on the JVM. */
interface NodeIdStore {
    /** Returns the stored id, or null when nothing valid has been saved yet. */
    fun read(): Int?

    fun write(id: Int)
}

/**
 * Generates this installation's 16-bit node id once and keeps it stable.
 *
 * 0 is not a valid AirMesh node id, so generated ids are in `1..65535`.
 * A stored value outside that range (for example a corrupted preference) is
 * replaced instead of being trusted.
 */
class NodeIdProvider(
    private val store: NodeIdStore,
    private val random: Random = Random.Default,
) {

    companion object {
        const val MIN_ID = 1
        const val MAX_ID = 0xFFFF
    }

    @Synchronized
    fun getOrCreate(): Int {
        val stored = store.read()
        if (stored != null && stored in MIN_ID..MAX_ID) return stored

        // nextInt() excludes its upper bound, so 0x10000 yields at most 65535.
        val generated = random.nextInt(MIN_ID, MAX_ID + 1)
        store.write(generated)
        return generated
    }
}
