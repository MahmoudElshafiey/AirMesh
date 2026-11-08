package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for configurable rotation order, silence window, and non-persisted
 * service strategy selection (Part 1).
 */
class DiscoveryStrategySelectorOrderAndWindowTest {

    @Test
    fun `order parameter walks 2, 4, 3, 1 and wraps back to 2`() {
        val selector = DiscoveryStrategySelector(
            store = NoDiscoveryStrategyStore,
            defaultStrategy = 2,
            rotationOrder = listOf(2, 4, 3, 1),
            silenceWindowMs = 12_000L,
        )
        assertEquals(2, selector.strategy)

        selector.scanStarted(0L)
        assertEquals(DiscoveryStrategyEvent.Rotate(from = 2, to = 4), selector.tick(12_000L))
        assertEquals(4, selector.strategy)

        selector.scanStarted(12_000L)
        assertEquals(DiscoveryStrategyEvent.Rotate(from = 4, to = 3), selector.tick(24_000L))
        assertEquals(3, selector.strategy)

        selector.scanStarted(24_000L)
        assertEquals(DiscoveryStrategyEvent.Rotate(from = 3, to = 1), selector.tick(36_000L))
        assertEquals(1, selector.strategy)

        selector.scanStarted(36_000L)
        assertEquals(DiscoveryStrategyEvent.Rotate(from = 1, to = 2), selector.tick(48_000L))
        assertEquals(2, selector.strategy)
    }

    @Test
    fun `window 12 s rotates at 12_000 ms and not at 11_999 ms`() {
        val selector = DiscoveryStrategySelector(
            store = NoDiscoveryStrategyStore,
            defaultStrategy = 2,
            rotationOrder = listOf(2, 4, 3, 1),
            silenceWindowMs = 12_000L,
        )
        selector.scanStarted(nowMs = 1_000L)

        assertFalse(selector.isWindowExpired(12_999L))
        assertEquals(DiscoveryStrategyEvent.None, selector.tick(12_999L))
        assertEquals(2, selector.strategy)
        assertTrue(selector.watching)

        assertTrue(selector.isWindowExpired(13_000L))
        assertEquals(DiscoveryStrategyEvent.Rotate(from = 2, to = 4), selector.tick(13_000L))
        assertEquals(4, selector.strategy)
        assertFalse(selector.watching)
    }

    @Test
    fun `default parameters behave as before`() {
        val selector = DiscoveryStrategySelector(NoDiscoveryStrategyStore)
        assertEquals(1, selector.strategy)
        assertEquals(listOf(1, 2, 3, 4), selector.order)
        assertEquals(25_000L, selector.silenceWindowMs)

        selector.scanStarted(0L)
        assertEquals(DiscoveryStrategyEvent.None, selector.tick(24_999L))
        assertEquals(DiscoveryStrategyEvent.Rotate(1, 2), selector.tick(25_000L))
        selector.scanStarted(25_000L)
        assertEquals(DiscoveryStrategyEvent.Rotate(2, 3), selector.tick(50_000L))
        selector.scanStarted(50_000L)
        assertEquals(DiscoveryStrategyEvent.Rotate(3, 4), selector.tick(75_000L))
        selector.scanStarted(75_000L)
        assertEquals(DiscoveryStrategyEvent.Rotate(4, 1), selector.tick(100_000L))
    }

    @Test
    fun `no-op store never restores a value and service selector ignores prior saves`() {
        // Simulate an earlier session saving strategies 3 and 4 into NoDiscoveryStrategyStore.
        NoDiscoveryStrategyStore.save(3)
        NoDiscoveryStrategyStore.save(4)
        assertNull(NoDiscoveryStrategyStore.load())

        val firstSession = DiscoveryStrategySelector.forService()
        firstSession.scanStarted(0L)
        firstSession.tick(12_000L) // rotates to 4
        firstSession.scanStarted(12_000L)
        firstSession.rawCallback(12_500L) // attempts to persist 4 into NoDiscoveryStrategyStore
        assertEquals(4, firstSession.strategy)
        assertNull(NoDiscoveryStrategyStore.load())

        // A fresh selector for the next process start still begins at 2, ignoring the earlier session.
        val nextSession = DiscoveryStrategySelector.forService()
        assertEquals(2, nextSession.strategy)
        assertEquals(listOf(2, 4, 3, 1), nextSession.order)
        assertEquals(12_000L, nextSession.silenceWindowMs)
        assertEquals(
            "dnssd: starting strategy=2 (default, not persisted) order=2,4,3,1 window=12s",
            nextSession.startupLogLine(),
        )
    }
}
