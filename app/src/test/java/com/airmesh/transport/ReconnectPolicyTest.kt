package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for the client TCP retry backoff schedule, using a fake clock. */
class ReconnectPolicyTest {

    private var now = 1_000_000L

    @Test
    fun delaysGrowThenCap() {
        val policy = ReconnectPolicy(clock = { now })
        assertEquals(1_000, policy.recordFailure())
        assertEquals(2_000, policy.recordFailure())
        assertEquals(3_000, policy.recordFailure())
        // From the fourth failure on the delay stays at the cap.
        assertEquals(5_000, policy.recordFailure())
        assertEquals(5_000, policy.recordFailure())
    }

    @Test
    fun nextAttemptTimeUsesTheInjectedClock() {
        val policy = ReconnectPolicy(clock = { now })
        // No failure recorded yet: the next attempt is immediate.
        assertEquals(now, policy.nextAttemptTime())

        now += 123
        policy.recordFailure()
        assertEquals(now + 1_000, policy.nextAttemptTime())

        now += 456
        repeat(3) { policy.recordFailure() } // failures 2..4
        assertEquals(now + 5_000, policy.nextAttemptTime())
    }

    @Test
    fun resetRestartsTheSchedule() {
        val policy = ReconnectPolicy(clock = { now })
        repeat(4) { policy.recordFailure() }
        assertEquals(5_000, policy.recordFailure())

        policy.reset()
        assertEquals(1_000, policy.recordFailure())
    }
}
