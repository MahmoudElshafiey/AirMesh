package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the client connect loop's should-retry / should-stop rules,
 * using a fake clock. The decider must stop as soon as the group is confirmed
 * gone (even with an elapsed backoff and a p2p address present), wait while no
 * interface holds a 192.168.49.x address, and otherwise retry on the growing
 * ReconnectPolicy schedule.
 */
class ClientConnectDeciderTest {

    private var now = 1_000_000L
    private val decider = ClientConnectDecider(clock = { now })

    @Test
    fun attemptsWhenGroupFormedAndP2pAddressPresent() {
        assertEquals(ClientConnectDecider.Decision.Attempt, decider.decide(true, true))
    }

    @Test
    fun stopsWhenGroupGoneEvenWithElapsedBackoffAndP2pAddress() {
        decider.recordFailure() // schedules a backoff
        now += 60_000L // far past any backoff

        assertEquals(ClientConnectDecider.Decision.Stop, decider.decide(false, true))
        assertEquals(ClientConnectDecider.Decision.Stop, decider.decide(false, false))
    }

    @Test
    fun waitsForP2pAddressWithFixedPollInterval() {
        val first = decider.decide(true, false) as ClientConnectDecider.Decision.Wait
        assertEquals(ClientConnectDecider.Reason.NO_P2P_ADDRESS, first.reason)
        assertEquals(now + 1_000L, first.untilMs)

        now = first.untilMs // poll again at the scheduled time, still no address
        val second = decider.decide(true, false) as ClientConnectDecider.Decision.Wait
        assertEquals(ClientConnectDecider.Reason.NO_P2P_ADDRESS, second.reason)
        assertEquals(now + 1_000L, second.untilMs)

        now = second.untilMs
        // The p2p address appeared: the very next decision attempts.
        assertEquals(ClientConnectDecider.Decision.Attempt, decider.decide(true, true))
    }

    @Test
    fun backoffGrowsThenCapsAndRetriesAtTheScheduledTime() {
        val expectedDelays = listOf(1_000L, 2_000L, 3_000L, 5_000L)

        for (delayMs in expectedDelays) {
            assertEquals(delayMs, decider.recordFailure())

            // While the backoff is pending no attempt may run.
            val wait = decider.decide(true, true) as ClientConnectDecider.Decision.Wait
            assertEquals(ClientConnectDecider.Reason.BACKOFF, wait.reason)
            assertEquals(now + delayMs, wait.untilMs)

            now += 999L // just before the scheduled time: still waiting
            val early = decider.decide(true, true) as ClientConnectDecider.Decision.Wait
            assertEquals(wait.untilMs, early.untilMs)

            now = wait.untilMs // exactly at the scheduled time: retry allowed
            assertEquals(ClientConnectDecider.Decision.Attempt, decider.decide(true, true))
        }
    }

    @Test
    fun backoffTakesPrecedenceOverMissingP2pAddress() {
        decider.recordFailure() // 1 s backoff pending

        val wait = decider.decide(true, false) as ClientConnectDecider.Decision.Wait
        assertEquals(ClientConnectDecider.Reason.BACKOFF, wait.reason)
    }

    @Test
    fun successClearsTheBackoffSchedule() {
        repeat(4) { decider.recordFailure() } // capped at 5 s
        now += 10_000L

        decider.onSuccess()
        assertEquals(ClientConnectDecider.Decision.Attempt, decider.decide(true, true))

        // A later failure restarts the schedule from the first step.
        assertEquals(1_000L, decider.recordFailure())
    }

    @Test
    fun resetClearsStateForAFreshLoop() {
        repeat(3) { decider.recordFailure() } // 3 s backoff pending

        decider.reset()
        assertEquals(ClientConnectDecider.Decision.Attempt, decider.decide(true, true))
        assertEquals(1_000L, decider.recordFailure())
    }
}
