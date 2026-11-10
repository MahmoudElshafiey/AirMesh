package com.airmesh.profile

/**
 * Persistence for [ProfileDirectory]. The directory keeps its whole state as a
 * single JSON document; an implementation decides where that text lives. Pure
 * Kotlin so the JVM tests can use an in-memory fake.
 */
interface ProfileStorage {
    /** Returns the stored document, or null when nothing has been saved yet. */
    fun load(): String?

    /** Replaces the stored document with [text]. */
    fun save(text: String)
}

/** In-memory [ProfileStorage]; used by tests and for non-persistent use. */
class InMemoryProfileStorage : ProfileStorage {
    @Volatile private var text: String? = null

    override fun load(): String? = text
    override fun save(newText: String) {
        text = newText
    }
}
