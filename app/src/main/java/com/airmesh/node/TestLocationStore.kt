package com.airmesh.node

import android.content.Context

/**
 * Persistence for the debug "test location" switch. Kept behind an interface
 * so the restart-survival behavior is unit-testable on the JVM with a fake;
 * only debug builds ever read or write it.
 */
interface TestLocationStore {
    fun load(): Boolean

    fun save(value: Boolean)
}

/** SharedPreferences-backed [TestLocationStore]; mirrors [SharedPreferencesNodeIdStore]. */
class SharedPreferencesTestLocationStore(context: Context) : TestLocationStore {

    private companion object {
        const val PREFS_NAME = "airmesh_test_location"
        const val KEY_USE_TEST_LOCATION = "use_test_location"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Boolean = prefs.getBoolean(KEY_USE_TEST_LOCATION, false)

    override fun save(value: Boolean) {
        // commit() (not apply): the value must be on disk before a process death.
        prefs.edit().putBoolean(KEY_USE_TEST_LOCATION, value).commit()
    }
}
