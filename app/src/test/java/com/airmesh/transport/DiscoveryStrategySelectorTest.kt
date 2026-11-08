package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the DNS-SD discovery strategy rotation and persistence. */
class DiscoveryStrategySelectorTest {

    /** In-memory stand-in for the real preferences-backed store. */
    private class FakeStore(var value: Int? = null) : DiscoveryStrategyStore {
        var saves = 0
            private set

        override fun load(): Int? = value

        override fun save(strategy: Int) {
            value = strategy
            saves++
        }
    }

    private val store = FakeStore()

    @Test
    fun `starts at strategy 1 when nothing is stored`() {
        assertEquals(1, DiscoveryStrategySelector(store).strategy)
    }

    @Test
    fun `does not rotate at 24 s`() {
        val selector = DiscoveryStrategySelector(store)
        selector.scanStarted(nowMs = 0L)

        assertEquals(DiscoveryStrategyEvent.None, selector.tick(24_999L))
        assertEquals(1, selector.strategy)
        assertTrue(selector.watching)
    }

    @Test
    fun `rotates after 25 s of silence`() {
        val selector = DiscoveryStrategySelector(store)
        selector.scanStarted(nowMs = 1_000L)

        assertEquals(DiscoveryStrategyEvent.Rotate(from = 1, to = 2), selector.tick(26_000L))
        assertEquals(2, selector.strategy)
        // The watch is disarmed until the caller starts the next scan.
        assertFalse(selector.watching)
        assertEquals(DiscoveryStrategyEvent.None, selector.tick(60_000L))
    }

    @Test
    fun `a callback arriving exactly at 25 s counts`() {
        val selector = DiscoveryStrategySelector(store)
        selector.scanStarted(nowMs = 0L)

        // Callback processed before any tick: it is good despite the boundary.
        selector.rawCallback(25_000L)

        assertEquals(DiscoveryStrategyEvent.None, selector.tick(25_000L))
        assertEquals(1, selector.strategy)
        assertTrue(selector.rotationStopped)
        assertEquals(1, store.value)
    }

    @Test
    fun `sticks after the first callback`() {
        val selector = DiscoveryStrategySelector(store)
        selector.scanStarted(nowMs = 0L)
        selector.rawCallback(5_000L)

        // Well past the window, and after a later scan would start: never rotates.
        assertEquals(DiscoveryStrategyEvent.None, selector.tick(30_000L))
        selector.scanStarted(31_000L)
        assertEquals(DiscoveryStrategyEvent.None, selector.tick(120_000L))
        assertEquals(1, selector.strategy)
        assertTrue(selector.rotationStopped)
    }

    @Test
    fun `persists the good strategy only once`() {
        val selector = DiscoveryStrategySelector(store)
        selector.scanStarted(0L)
        selector.rawCallback(1_000L)
        selector.rawCallback(2_000L) // another peer, same session

        assertEquals(1, store.saves)
        assertEquals(1, store.value)
    }

    @Test
    fun `rotates through the fallback order and wraps from 4 to 1`() {
        val selector = DiscoveryStrategySelector(store)

        selector.scanStarted(0L)
        assertEquals(DiscoveryStrategyEvent.Rotate(1, 2), selector.tick(25_000L))
        selector.scanStarted(25_000L)
        assertEquals(DiscoveryStrategyEvent.Rotate(2, 3), selector.tick(50_000L))
        selector.scanStarted(50_000L)
        assertEquals(DiscoveryStrategyEvent.Rotate(3, 4), selector.tick(75_000L))
        selector.scanStarted(75_000L)
        assertEquals(DiscoveryStrategyEvent.Rotate(4, 1), selector.tick(100_000L))
        assertEquals(1, selector.strategy)
    }

    @Test
    fun `restores the persisted strategy`() {
        store.value = 3
        assertEquals(3, DiscoveryStrategySelector(store).strategy)

        // A rotated-to strategy that then works is the one remembered next time.
        val shared = FakeStore(1)
        val session = DiscoveryStrategySelector(shared)
        session.scanStarted(0L)
        session.tick(25_000L) // now strategy 2
        session.scanStarted(25_000L)
        session.rawCallback(30_000L)

        assertEquals(2, shared.value)
        assertEquals(2, DiscoveryStrategySelector(shared).strategy)
    }

    @Test
    fun `invalid persisted values fall back to 1`() {
        assertEquals(1, DiscoveryStrategySelector(FakeStore(0)).strategy)
        assertEquals(1, DiscoveryStrategySelector(FakeStore(5)).strategy)
        assertEquals(1, DiscoveryStrategySelector(FakeStore(-3)).strategy)
        assertEquals(1, DiscoveryStrategySelector(FakeStore(null)).strategy)
    }

    @Test
    fun `next strategy helper wraps correctly`() {
        assertEquals(2, DiscoveryStrategySelector.nextStrategy(1))
        assertEquals(3, DiscoveryStrategySelector.nextStrategy(2))
        assertEquals(4, DiscoveryStrategySelector.nextStrategy(3))
        assertEquals(1, DiscoveryStrategySelector.nextStrategy(4))
        // Garbage is treated as 1 first, so its successor is 2.
        assertEquals(2, DiscoveryStrategySelector.nextStrategy(99))
    }

    @Test
    fun `a callback in a later scan still stops rotation`() {
        val selector = DiscoveryStrategySelector(store)
        selector.scanStarted(0L)
        selector.tick(25_000L) // rotated to 2 without any callback

        selector.scanStarted(25_000L)
        selector.rawCallback(26_000L)

        assertEquals(2, selector.strategy)
        assertEquals(2, store.value)
        assertTrue(selector.rotationStopped)
    }

    @Test
    fun `window expiry helper tracks the open scan`() {
        val selector = DiscoveryStrategySelector(store)
        assertFalse(selector.isWindowExpired(99_000L)) // no scan running

        selector.scanStarted(1_000L)
        assertFalse(selector.isWindowExpired(25_999L))
        assertTrue(selector.isWindowExpired(26_000L))
        assertFalse(selector.sawCallbackSinceScanStart())

        selector.rawCallback(2_000L)
        assertFalse(selector.isWindowExpired(99_000L))
        assertTrue(selector.sawCallbackSinceScanStart())
    }

    @Test
    fun `default 2 starts at 2 when nothing is stored`() {
        val selector = DiscoveryStrategySelector(store, defaultStrategy = 2)
        assertEquals(2, selector.strategy)
    }

    @Test
    fun `invalid default falls back to 1`() {
        assertEquals(1, DiscoveryStrategySelector(store, defaultStrategy = 0).strategy)
        assertEquals(1, DiscoveryStrategySelector(store, defaultStrategy = 5).strategy)
        assertEquals(1, DiscoveryStrategySelector(store, defaultStrategy = -2).strategy)
    }

    @Test
    fun `persisted value wins over default`() {
        store.value = 4
        val selector = DiscoveryStrategySelector(store, defaultStrategy = 2)
        assertEquals(4, selector.strategy)
    }

    @Test
    fun `after 25 s of silence from default 2 it rotates to 3`() {
        val selector = DiscoveryStrategySelector(store, defaultStrategy = 2)
        selector.scanStarted(nowMs = 0L)

        assertEquals(DiscoveryStrategyEvent.Rotate(from = 2, to = 3), selector.tick(25_000L))
        assertEquals(3, selector.strategy)
    }

    @Test
    fun `first callback on default 2 persists the strategy`() {
        val selector = DiscoveryStrategySelector(store, defaultStrategy = 2)
        selector.scanStarted(nowMs = 0L)
        selector.rawCallback(nowMs = 500L)

        assertEquals(2, selector.strategy)
        assertTrue(selector.rotationStopped)
        assertEquals(2, store.value)
        assertEquals(1, store.saves)
    }
}
