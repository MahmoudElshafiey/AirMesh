package com.airmesh.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the UI log batching window, driven by a fake clock (TASK U4 part 4). */
class LogUiBatcherTest {

    private var now = 0L
    private val batcher = LogUiBatcher(clock = { now })

    @Test
    fun theFirstPublishIsAllowedImmediately() {
        assertTrue(batcher.canPublish())
        assertEquals(0L, batcher.waitMs())
    }

    @Test
    fun aPublishOpensA250MillisecondWindow() {
        batcher.markPublished() // at t=0
        assertFalse("inside the window", batcher.canPublish())
        now = 249
        assertFalse(batcher.canPublish())
        assertEquals(1L, batcher.waitMs())
        now = 250
        assertTrue(batcher.canPublish())
        assertEquals(0L, batcher.waitMs())
    }

    @Test
    fun theWindowIsAlwaysMeasuredFromTheLastPublish() {
        batcher.markPublished() // t=0 -> next allowed at 250
        now = 300
        assertTrue(batcher.canPublish())
        batcher.markPublished() // t=300 -> next allowed at 550
        now = 499
        assertFalse(batcher.canPublish())
        assertEquals(51L, batcher.waitMs())
        now = 550
        assertTrue(batcher.canPublish())
    }

    @Test
    fun waitMsIsNeverNegative() {
        now = 10_000
        assertEquals(0L, batcher.waitMs())
    }

    @Test
    fun theIntervalConstantIs250Milliseconds() {
        assertEquals(250L, LogUiBatcher.MIN_PUBLISH_INTERVAL_MS)
    }
}
