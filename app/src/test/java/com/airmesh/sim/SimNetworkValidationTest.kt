package com.airmesh.sim

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SimNetworkValidationTest {

    @Test
    fun duplicateNodeIdsAreRejected() {
        val net = SimNetwork(seed = 20)
        net.addNode(1)

        val error = assertThrows(IllegalArgumentException::class.java) {
            net.addNode(1)
        }

        assertTrue(error.message!!.contains("node 1 already exists"))
    }

    @Test
    fun runUntilPastTimeIsRejected() {
        val net = SimNetwork(seed = 21)
        net.addNode(1)
        net.runUntil(1_000)

        val error = assertThrows(IllegalArgumentException::class.java) {
            net.runUntil(999)
        }

        assertTrue(error.message!!.contains("endTime must not be before virtual now"))
    }

    @Test
    fun explicitConnectIsRejectedInRadioRangeMode() {
        val net = SimNetwork(seed = 22)
        net.addNode(1)
        net.addNode(2)
        net.configureRadioRange(range = 10.0)

        val error = assertThrows(IllegalArgumentException::class.java) {
            net.connect(1, 2)
        }

        assertTrue(error.message!!.contains("explicit-link mode"))
    }

    @Test
    fun radioRangeIsRejectedAfterExplicitLinks() {
        val net = SimNetwork(seed = 23)
        net.addNode(1)
        net.addNode(2)
        net.connect(1, 2)

        val error = assertThrows(IllegalArgumentException::class.java) {
            net.configureRadioRange(range = 10.0)
        }

        assertTrue(error.message!!.contains("explicit"))
    }
}
