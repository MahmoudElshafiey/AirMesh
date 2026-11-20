package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake-clock tests for [AdvertReregisterCoalescer] (TASK T1 part D3). The coalescer is pure —
 * every timestamp is passed in, so these run deterministically with no sockets and no sleeps.
 */
class AdvertReregisterCoalescerTest {

    // ------------------------------------------------------------------
    // (1) With the default constants a new count registers only after it has been stable for
    //     exactly ADVERT_REREGISTER_MIN_INTERVAL_MS of repeated observations.
    // ------------------------------------------------------------------

    @Test
    fun firstChangeRegistersOnlyAfterStability() {
        val c = AdvertReregisterCoalescer()
        c.onFreshRegistration(1)

        var registrations = 0
        for (t in 1_000L..10_000L step 1_000L) {
            assertFalse("no registration before the value is stable, at t=$t", c.onUpdate(2, t))
        }
        assertTrue("registers exactly when stability reaches 10 s (t=11_000)", c.onUpdate(2, 11_000L))
        registrations++

        assertEquals("the registered count is the stable one", 2, c.registeredCount)
        // Feeding the same value again never re-registers.
        assertFalse("an unchanged count never removes and re-adds the service", c.onUpdate(2, 12_000L))
        assertEquals("exactly one registration happened", 1, registrations)
    }

    // ------------------------------------------------------------------
    // (2) A count that oscillates faster than the stability window never registers at all.
    // ------------------------------------------------------------------

    @Test
    fun oscillatingCountNeverReRegisters() {
        val c = AdvertReregisterCoalescer()
        c.onFreshRegistration(1)

        var registrations = 0
        for (t in 0L..60_000L step 5_000L) {
            val count = if ((t / 5_000L) % 2 == 0L) 2 else 3 // flips every 5 s < 10 s stability
            if (c.onUpdate(count, t)) registrations++
        }

        assertEquals("an oscillating count must never re-register", 0, registrations)
        assertEquals("the registered count stays the fresh one", 1, c.registeredCount)
    }

    // ------------------------------------------------------------------
    // (3) Two changes inside one window coalesce into ONE registration of the final value.
    // ------------------------------------------------------------------

    @Test
    fun twoChangesWithinWindowCoalesceToOne() {
        val c = AdvertReregisterCoalescer()
        c.onFreshRegistration(1)

        var registrations = 0
        for (t in 1_000L..4_000L step 1_000L) {
            assertFalse("count 2 is still pending, at t=$t", c.onUpdate(2, t))
        }
        // The count jumps to 3 before 2 has been stable for 10 s: the clock restarts.
        for (t in 5_000L..14_000L step 1_000L) {
            assertFalse("count 3 is still pending, at t=$t", c.onUpdate(3, t))
        }
        assertTrue("the final value registers once it has been stable for 10 s (t=15_000)", c.onUpdate(3, 15_000L))
        registrations++

        assertEquals("exactly one registration happened", 1, registrations)
        assertEquals("it registered the FINAL value, not the intermediate one", 3, c.registeredCount)
    }

    // ------------------------------------------------------------------
    // (4) Even after stability, two actual registrations stay at least minIntervalMs apart.
    //     Custom constants: stable in 2 s, but re-registrations spaced by 10 s.
    // ------------------------------------------------------------------

    @Test
    fun minIntervalGateBlocksSecondRegistration() {
        val c = AdvertReregisterCoalescer(stableMs = 2_000L, minIntervalMs = 10_000L)
        c.onFreshRegistration(1)

        assertFalse("count 2 pending (t=1_000)", c.onUpdate(2, 1_000L))
        assertFalse("not stable yet (t=2_000)", c.onUpdate(2, 2_000L))
        assertTrue("first change: lastAtMs==0 so only stability is needed (t=3_000)", c.onUpdate(2, 3_000L))

        // Count jumps to 3 one second later; it stabilizes at t=6_000 but the spacing gate
        // (10 s since the registration at t=3_000) must hold it back until t=13_000.
        for (t in 4_000L..12_000L step 1_000L) {
            assertFalse("count 3 blocked by the spacing gate, at t=$t", c.onUpdate(3, t))
        }
        assertTrue("registers exactly when both stability and spacing are met (t=13_000)", c.onUpdate(3, 13_000L))

        assertEquals("the registered count is the second value", 3, c.registeredCount)
    }

    // ------------------------------------------------------------------
    // (5) A fresh registration re-anchors the spacing gate: the first real change after it waits
    //     only for stability, not another full interval.
    // ------------------------------------------------------------------

    @Test
    fun freshRegistrationReAnchorsSpacing() {
        val c = AdvertReregisterCoalescer(stableMs = 2_000L, minIntervalMs = 10_000L)
        c.onFreshRegistration(5) // e.g. a role switch just re-registered the service

        assertFalse("count 6 pending (t=21_000)", c.onUpdate(6, 21_000L))
        assertFalse("not stable yet (t=22_000)", c.onUpdate(6, 22_000L))
        // Only 8 s have passed since the previous real registration in test (4)'s timeline —
        // but onFreshRegistration reset lastRegisteredAtMs to "never", so stability alone suffices.
        assertTrue("the first change after a fresh registration waits only for stability (t=23_000)", c.onUpdate(6, 23_000L))

        assertEquals("the registered count is the new value", 6, c.registeredCount)
    }

    // ------------------------------------------------------------------
    // (6) A value equal to the registered one clears any pending change; a later re-appearance of
    //     the other value starts its stability clock from scratch.
    // ------------------------------------------------------------------

    @Test
    fun sameValueClearsPendingChange() {
        val c = AdvertReregisterCoalescer()
        c.onFreshRegistration(1)

        for (t in 0L..9_000L step 1_000L) {
            assertFalse("count 2 pending, at t=$t", c.onUpdate(2, t))
        }
        // The count returns to the registered value: the pending change is dropped.
        assertFalse("same as registered never re-registers (t=10_000)", c.onUpdate(1, 10_000L))

        // Count 2 appears again — its stability clock starts at t=11_000, not at t=0.
        for (t in 11_000L..20_000L step 1_000L) {
            assertFalse("count 2 pending with a fresh clock, at t=$t", c.onUpdate(2, t))
        }
        assertTrue("registers after a full stability window from the re-appearance (t=21_000)", c.onUpdate(2, 21_000L))

        assertEquals("the registered count is 2", 2, c.registeredCount)
    }
}
