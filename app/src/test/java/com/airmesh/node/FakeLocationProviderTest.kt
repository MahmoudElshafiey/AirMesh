package com.airmesh.node

import com.airmesh.protocol.LocationPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the [FakeLocationProvider] contract: fixed result, no waiting, call counting. */
class FakeLocationProviderTest {

    @Test
    fun returnsTheConfiguredFix() = runBlocking {
        val fix = LocationFix(30_044_420, 31_235_760, 12, timeMs = 1_700_000_000_000L)
        val provider = FakeLocationProvider(fix)

        assertEquals(fix, provider.requestFix())
        // The fix converts to the exact payload a LOCATION frame would carry.
        assertEquals(LocationPayload(30_044_420, 31_235_760, 12), fix.toPayload())
    }

    @Test
    fun returnsNullWhenConfiguredWithNoFix() = runBlocking {
        val provider = FakeLocationProvider(null)
        assertNull(provider.requestFix())
        assertEquals(1, provider.requestCount)
    }
}
