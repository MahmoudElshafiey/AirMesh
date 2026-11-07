package com.airmesh.node

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [TestLocationStore] standing in for SharedPreferences on a device. */
private class FakeTestLocationStore(private val prefs: MutableMap<String, Boolean>) : TestLocationStore {

    private companion object {
        const val KEY = "use_test_location"
    }

    override fun load(): Boolean = prefs[KEY] ?: false

    override fun save(value: Boolean) {
        prefs[KEY] = value
    }
}

class TestLocationStoreTest {

    @Test
    fun `the debug switch survives a simulated restart`() {
        // The map stands in for the device's persistent storage — it outlives
        // both "process" instances.
        val deviceStorage = mutableMapOf<String, Boolean>()

        val firstSession = FakeTestLocationStore(deviceStorage)
        assertFalse(firstSession.load()) // default: off
        firstSession.save(true)          // user arms the test location

        // Simulated process death: a brand-new store over the same storage.
        val restartedSession = FakeTestLocationStore(deviceStorage)
        assertTrue(restartedSession.load())
    }
}
