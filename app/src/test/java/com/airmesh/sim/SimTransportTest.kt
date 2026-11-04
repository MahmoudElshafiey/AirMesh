package com.airmesh.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimTransportTest {

    @Test
    fun restartRestoresNeighborsFromCurrentLinks() {
        val net = SimNetwork(seed = 13)
        val one = net.addNode(1)
        net.addNode(2)
        net.addNode(3)
        net.connect(1, 2)

        assertEquals(setOf(2), one.transport.neighbors.value)

        one.transport.stop()
        assertTrue(one.transport.neighbors.value.isEmpty())

        // Adding a link while stopped updates the network, but must not be the
        // only source of the restarted neighbor set: node 2 was cleared by
        // stop(), so start() has to rebuild the set from the network links.
        net.connect(1, 3)
        one.transport.start()

        assertEquals(setOf(2, 3), one.transport.neighbors.value)
        assertEquals(setOf(1), net.node(2).transport.neighbors.value)
        assertEquals(setOf(1), net.node(3).transport.neighbors.value)
    }
}
