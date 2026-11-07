package com.airmesh.node

/**
 * Test double for [LocationProvider]: returns a fixed fix (or null) without
 * waiting, so tests never need GPS hardware or real timeouts.
 */
class FakeLocationProvider(private val fix: LocationFix? = null) : LocationProvider {

    /** Number of times [requestFix] was called; lets tests assert the flow. */
    var requestCount: Int = 0
        private set

    override suspend fun requestFix(): LocationFix? {
        requestCount++
        return fix
    }
}
