package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the strict DNS-SD refresh chaining and its backoff. */
class DnsSdRefreshSequencerTest {

    private var nowMs = 1_000L
    private val clock: () -> Long = { nowMs }

    private fun step(step: DnsSdRefreshStep, delayMs: Long) = DnsSdRefreshAction.RunStep(step, delayMs)

    @Test
    fun `happy path chains clear then add then discover after 500 ms`() {
        val sequencer = DnsSdRefreshSequencer(clock)

        assertEquals(step(DnsSdRefreshStep.CLEAR_REQUESTS, 0L), sequencer.requestRefresh())
        assertTrue(sequencer.running)
        assertEquals(DnsSdRefreshStep.CLEAR_REQUESTS, sequencer.currentStep)

        assertEquals(step(DnsSdRefreshStep.ADD_REQUEST, 0L), sequencer.onStepSuccess(DnsSdRefreshStep.CLEAR_REQUESTS))
        assertEquals(DnsSdRefreshStep.ADD_REQUEST, sequencer.currentStep)

        assertEquals(
            step(DnsSdRefreshStep.DISCOVER_SERVICES, DnsSdRefreshSequencer.ADD_TO_DISCOVER_DELAY_MS),
            sequencer.onStepSuccess(DnsSdRefreshStep.ADD_REQUEST),
        )
        assertEquals(500L, DnsSdRefreshSequencer.ADD_TO_DISCOVER_DELAY_MS)
        assertEquals(DnsSdRefreshStep.DISCOVER_SERVICES, sequencer.currentStep)

        assertEquals(DnsSdRefreshAction.CycleComplete, sequencer.onStepSuccess(DnsSdRefreshStep.DISCOVER_SERVICES))
        assertFalse(sequencer.running)
        assertNull(sequencer.currentStep)
    }

    @Test
    fun `failed step retries itself with the right delay`() {
        val sequencer = DnsSdRefreshSequencer(clock)
        sequencer.requestRefresh()

        assertEquals(
            step(DnsSdRefreshStep.CLEAR_REQUESTS, 2_000L),
            sequencer.onStepFailure(DnsSdRefreshStep.CLEAR_REQUESTS, reason = 1),
        )
        // The failed step stays current: the retry must not skip ahead.
        assertEquals(DnsSdRefreshStep.CLEAR_REQUESTS, sequencer.currentStep)

        assertEquals(
            step(DnsSdRefreshStep.CLEAR_REQUESTS, 4_000L),
            sequencer.onStepFailure(DnsSdRefreshStep.CLEAR_REQUESTS, reason = 1),
        )
        assertEquals(
            step(DnsSdRefreshStep.CLEAR_REQUESTS, 8_000L),
            sequencer.onStepFailure(DnsSdRefreshStep.CLEAR_REQUESTS, reason = 1),
        )
    }

    @Test
    fun `backoff is capped at 30 seconds`() {
        assertEquals(2_000L, DnsSdRefreshSequencer.backoffDelayMs(1))
        assertEquals(4_000L, DnsSdRefreshSequencer.backoffDelayMs(2))
        assertEquals(8_000L, DnsSdRefreshSequencer.backoffDelayMs(3))
        assertEquals(16_000L, DnsSdRefreshSequencer.backoffDelayMs(4))
        assertEquals(30_000L, DnsSdRefreshSequencer.backoffDelayMs(5))
        assertEquals(30_000L, DnsSdRefreshSequencer.backoffDelayMs(6))
        assertEquals(30_000L, DnsSdRefreshSequencer.backoffDelayMs(50))

        val sequencer = DnsSdRefreshSequencer(clock)
        sequencer.requestRefresh()
        repeat(5) { sequencer.onStepFailure(DnsSdRefreshStep.CLEAR_REQUESTS, 2) }
        assertEquals(
            step(DnsSdRefreshStep.CLEAR_REQUESTS, 30_000L),
            sequencer.onStepFailure(DnsSdRefreshStep.CLEAR_REQUESTS, 2),
        )
    }

    @Test
    fun `backoff resets after a successful step`() {
        val sequencer = DnsSdRefreshSequencer(clock)
        sequencer.requestRefresh()
        sequencer.onStepFailure(DnsSdRefreshStep.CLEAR_REQUESTS, 1)
        sequencer.onStepFailure(DnsSdRefreshStep.CLEAR_REQUESTS, 1)
        assertEquals(2, sequencer.consecutiveFailures)

        // Clear finally succeeds; the counter is cleared with it.
        sequencer.onStepSuccess(DnsSdRefreshStep.CLEAR_REQUESTS)
        assertEquals(0, sequencer.consecutiveFailures)

        // The next failure (a different step) starts again at 2 s.
        assertEquals(
            step(DnsSdRefreshStep.ADD_REQUEST, 2_000L),
            sequencer.onStepFailure(DnsSdRefreshStep.ADD_REQUEST, 3),
        )
    }

    @Test
    fun `a refresh request while one is running is ignored`() {
        val sequencer = DnsSdRefreshSequencer(clock)
        sequencer.requestRefresh()
        sequencer.onStepFailure(DnsSdRefreshStep.CLEAR_REQUESTS, 1)

        assertEquals(DnsSdRefreshAction.Ignored, sequencer.requestRefresh())
        // State untouched by the ignored request.
        assertEquals(DnsSdRefreshStep.CLEAR_REQUESTS, sequencer.currentStep)
        assertEquals(1, sequencer.consecutiveFailures)
        assertTrue(sequencer.running)
    }

    @Test
    fun `reset after success allows a fresh cycle`() {
        val sequencer = DnsSdRefreshSequencer(clock)
        sequencer.requestRefresh()
        sequencer.onStepSuccess(DnsSdRefreshStep.CLEAR_REQUESTS)
        sequencer.onStepSuccess(DnsSdRefreshStep.ADD_REQUEST)
        sequencer.onStepSuccess(DnsSdRefreshStep.DISCOVER_SERVICES)

        assertFalse(sequencer.running)
        assertEquals(0, sequencer.consecutiveFailures)

        // The sequencer is reusable: the next refresh starts again at clear.
        assertEquals(step(DnsSdRefreshStep.CLEAR_REQUESTS, 0L), sequencer.requestRefresh())
    }

    @Test
    fun `reset abandons a running cycle`() {
        val sequencer = DnsSdRefreshSequencer(clock)
        sequencer.requestRefresh()

        sequencer.reset()

        assertFalse(sequencer.running)
        assertNull(sequencer.currentStep)
        assertEquals(0, sequencer.consecutiveFailures)
        // A scheduled step must not fire after the reset.
        assertFalse(sequencer.isRunningStep(DnsSdRefreshStep.CLEAR_REQUESTS))
    }

    @Test
    fun `stale or wrong step callbacks are ignored`() {
        val sequencer = DnsSdRefreshSequencer(clock)

        // Nothing started yet.
        assertEquals(DnsSdRefreshAction.Ignored, sequencer.onStepSuccess(DnsSdRefreshStep.CLEAR_REQUESTS))
        assertEquals(DnsSdRefreshAction.Ignored, sequencer.onStepFailure(DnsSdRefreshStep.ADD_REQUEST, 1))

        sequencer.requestRefresh()
        // Out-of-order success and failure for a step we are not waiting on.
        assertEquals(DnsSdRefreshAction.Ignored, sequencer.onStepSuccess(DnsSdRefreshStep.DISCOVER_SERVICES))
        assertEquals(DnsSdRefreshAction.Ignored, sequencer.onStepFailure(DnsSdRefreshStep.ADD_REQUEST, 1))
        assertEquals(DnsSdRefreshStep.CLEAR_REQUESTS, sequencer.currentStep)
    }

    @Test
    fun `activity is timestamped from the injected clock`() {
        val sequencer = DnsSdRefreshSequencer(clock)
        nowMs = 5_000L
        sequencer.requestRefresh()
        assertEquals(5_000L, sequencer.lastActivityMs)

        nowMs = 9_250L
        sequencer.onStepSuccess(DnsSdRefreshStep.CLEAR_REQUESTS)
        assertEquals(9_250L, sequencer.lastActivityMs)

        nowMs = 12_000L
        sequencer.onStepFailure(DnsSdRefreshStep.ADD_REQUEST, 4)
        assertEquals(12_000L, sequencer.lastActivityMs)
    }
}
