package com.airmesh.sim

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SimEventQueueTest {

    @Test
    fun eventsRunInVirtualTimeOrderAndTiesUseInsertionOrder() = runBlocking {
        val clock = SimClock()
        val queue = SimEventQueue()
        val order = mutableListOf<String>()

        queue.schedule(10) { order += "a" }
        queue.schedule(5) { order += "b" }
        queue.schedule(10) { order += "c" }

        queue.runUntil(endTime = 10, clock = clock)

        assertEquals(listOf("b", "a", "c"), order)
        assertEquals(10L, clock.now)
    }

    @Test
    fun eventAddedAtTheSameTimeRunsAfterAlreadyQueuedTies() = runBlocking {
        val clock = SimClock()
        val queue = SimEventQueue()
        val order = mutableListOf<String>()

        queue.schedule(10) {
            order += "first"
            queue.schedule(10) { order += "scheduled-during-first" }
        }
        queue.schedule(10) { order += "second" }

        queue.runUntil(endTime = 10, clock = clock)

        assertEquals(listOf("first", "second", "scheduled-during-first"), order)
    }
}
