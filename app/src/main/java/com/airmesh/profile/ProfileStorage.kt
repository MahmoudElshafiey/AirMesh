package com.airmesh.profile

/**
 * String-key persistence for profile data. [ProfileDirectory] stores its whole
 * state as one JSON document under a single key; the node's own name and color
 * live under two more keys. Pure Kotlin so the JVM tests can use an in-memory
 * fake, while production uses SharedPreferences behind the same interface.
 */
interface ProfileStorage {
    /** Returns the value stored under [key], or null when absent. */
    fun getString(key: String): String?

    /** Stores [value] under [key], replacing any previous value. */
    fun putString(key: String, value: String)
}

/** In-memory [ProfileStorage]; used by tests and for non-persistent use. */
class InMemoryProfileStorage : ProfileStorage {
    private val lock = Any()
    private val values = HashMap<String, String>()

    override fun getString(key: String): String? = synchronized(lock) { values[key] }
    override fun putString(key: String, value: String) {
        synchronized(lock) { values[key] = value }
    }
}
