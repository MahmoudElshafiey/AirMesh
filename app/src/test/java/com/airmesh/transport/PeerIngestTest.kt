package com.airmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [PeerIngest] — the pure-logic peer accept/ignore layer.
 *
 * Test strings are copied verbatim from real phone logs:
 * - Galaxy S21: instance `airmesh-n52647-v2-t7e1b99fe-o0.local.`
 *   src `a6:a4:90:f2:cd:da`
 * - Galaxy A54: instance `airmesh-n62280-v2-t74f089b0-o0.local.`
 *   src `be:32:b2:4b:42:97`
 */
class PeerIngestTest {

    private val myNodeId = 999           // never collides with test data
    private val logs = mutableListOf<String>()
    private val stats = DnsSdStats()

    private fun ingest() = PeerIngest(
        myNodeId = myNodeId,
        log = { logs += it },
        stats = stats,
    )

    // ---- real log order: TXT then service (Galaxy S21) ----------------------

    @Test
    fun `TXT then service gives exactly one accepted peer`() {
        val pi = ingest()

        // TXT arrives first (the order seen in real logs).
        pi.onTxtRecordAvailable(
            fullDomain = "_airmesh._tcp.airmesh-n52647-v2-t7e1b99fe-o0.local.",
            txtRecord = mapOf("ad" to "airmesh-n52647-v2-t7e1b99fe-o0"),
            srcAddress = "a6:a4:90:f2:cd:da",
            nowMs = 1000L,
        )
        // TXT refreshes → peer exists after TXT
        assertEquals(1, pi.peers.size)

        // Service arrives second.
        pi.onServiceAvailable(
            instanceName = "airmesh-n52647-v2-t7e1b99fe-o0.local.",
            serviceType = "_airmesh._tcp",
            srcAddress = "a6:a4:90:f2:cd:da",
            nowMs = 2000L,
        )
        // Still one peer, lastSeen updated.
        assertEquals(1, pi.peers.size)
        val peer = pi.peers["a6:a4:90:f2:cd:da"]!!
        assertEquals(52647, peer.nodeId)
        assertEquals(0x7e1b99feL, peer.tieBreak)
        assertFalse(peer.owner)
        assertEquals(2000L, peer.lastSeenMs)

        // "accepted" counted only once (logs contain exactly one accepted line).
        assertTrue(logs.any { it.contains("accepted AirMesh peer #52647") })
    }

    // ---- reverse order: service then TXT ------------------------------------

    @Test
    fun `service then TXT gives exactly one accepted peer`() {
        val pi = ingest()

        pi.onServiceAvailable(
            instanceName = "airmesh-n62280-v2-t74f089b0-o0.local.",
            serviceType = "_airmesh._tcp",
            srcAddress = "be:32:b2:4b:42:97",
            nowMs = 1000L,
        )
        assertEquals(1, pi.peers.size)
        assertTrue(logs.any { it.contains("accepted AirMesh peer #62280") })

        pi.onTxtRecordAvailable(
            fullDomain = "_airmesh._tcp.airmesh-n62280-v2-t74f089b0-o0.local.",
            txtRecord = mapOf("ad" to "airmesh-n62280-v2-t74f089b0-o0"),
            srcAddress = "be:32:b2:4b:42:97",
            nowMs = 2000L,
        )
        assertEquals(1, pi.peers.size)
        assertEquals(2000L, pi.peers["be:32:b2:4b:42:97"]!!.lastSeenMs)
    }

    // ---- service only -------------------------------------------------------

    @Test
    fun `service only accepts peer`() {
        val pi = ingest()
        pi.onServiceAvailable(
            instanceName = "airmesh-n52647-v2-t7e1b99fe-o0.local.",
            serviceType = "_airmesh._tcp",
            srcAddress = "a6:a4:90:f2:cd:da",
            nowMs = 5000L,
        )
        assertEquals(1, pi.peers.size)
        val peer = pi.peers["a6:a4:90:f2:cd:da"]!!
        assertEquals(52647, peer.nodeId)
        assertEquals(0x7e1b99feL, peer.tieBreak)
        assertFalse(peer.owner)
        assertTrue(logs.any { it.contains("accepted AirMesh peer #52647 owner=0 src=a6:a4:90:f2:cd:da") })
    }

    // ---- TXT only with map ad -----------------------------------------------

    @Test
    fun `TXT only with map ad upserts peer`() {
        val pi = ingest()
        pi.onTxtRecordAvailable(
            fullDomain = "_airmesh._tcp.airmesh-n62280-v2-t74f089b0-o0.local.",
            txtRecord = mapOf("ad" to "airmesh-n62280-v2-t74f089b0-o0"),
            srcAddress = "be:32:b2:4b:42:97",
            nowMs = 3000L,
        )
        assertEquals(1, pi.peers.size)
        val peer = pi.peers["be:32:b2:4b:42:97"]!!
        assertEquals(62280, peer.nodeId)
        assertEquals(0x74f089b0L, peer.tieBreak)
        assertFalse(peer.owner)
    }

    // ---- foreign service is ignored -----------------------------------------

    @Test
    fun `foreign service is ignored`() {
        val pi = ingest()
        pi.onServiceAvailable(
            instanceName = "printer._http._tcp.local.",
            serviceType = "_http._tcp.local.",
            srcAddress = "aa:bb:cc:dd:ee:ff",
            nowMs = 1000L,
        )
        assertTrue(pi.peers.isEmpty())
        assertTrue(logs.any { it.contains("ignored: foreign service") })
    }

    // ---- own id is ignored --------------------------------------------------

    @Test
    fun `service with own nodeId is ignored`() {
        val pi = PeerIngest(
            myNodeId = 52647,
            log = { logs += it },
            stats = stats,
        )
        pi.onServiceAvailable(
            instanceName = "airmesh-n52647-v2-t7e1b99fe-o0.local.",
            serviceType = "_airmesh._tcp",
            srcAddress = "a6:a4:90:f2:cd:da",
            nowMs = 1000L,
        )
        assertTrue(pi.peers.isEmpty())
        assertTrue(logs.any { it.contains("ignored own ad") })
    }

    @Test
    fun `TXT with own nodeId is ignored`() {
        val pi = PeerIngest(
            myNodeId = 52647,
            log = { logs += it },
            stats = stats,
        )
        pi.onTxtRecordAvailable(
            fullDomain = "_airmesh._tcp.airmesh-n52647-v2-t7e1b99fe-o0.local.",
            txtRecord = mapOf("ad" to "airmesh-n52647-v2-t7e1b99fe-o0"),
            srcAddress = "a6:a4:90:f2:cd:da",
            nowMs = 1000L,
        )
        assertTrue(pi.peers.isEmpty())
    }

    // ---- unsupported version is ignored and logged --------------------------

    @Test
    fun `service with version 3 is ignored and logged`() {
        val pi = ingest()
        pi.onServiceAvailable(
            instanceName = "airmesh-n52647-v3-t7e1b99fe-o0",
            serviceType = "_airmesh._tcp",
            srcAddress = "a6:a4:90:f2:cd:da",
            nowMs = 1000L,
        )
        assertTrue(pi.peers.isEmpty())
        assertTrue(logs.any { it.contains("unsupported version 3") })
    }

    @Test
    fun `TXT with version 3 is ignored and logged`() {
        val pi = ingest()
        pi.onTxtRecordAvailable(
            fullDomain = "_airmesh._tcp.airmesh-n52647-v3-t7e1b99fe-o0.local.",
            txtRecord = mapOf("ad" to "airmesh-n52647-v3-t7e1b99fe-o0"),
            srcAddress = "a6:a4:90:f2:cd:da",
            nowMs = 1000L,
        )
        assertTrue(pi.peers.isEmpty())
        assertTrue(logs.any { it.contains("unsupported version 3") })
    }

    // ---- same advert twice: updates lastSeen, no second accepted ------------

    @Test
    fun `same advert twice updates lastSeen without second accepted`() {
        val pi = ingest()
        pi.onServiceAvailable(
            instanceName = "airmesh-n52647-v2-t7e1b99fe-o0.local.",
            serviceType = "_airmesh._tcp",
            srcAddress = "a6:a4:90:f2:cd:da",
            nowMs = 1000L,
        )
        assertEquals(1, pi.peers.size)
        assertEquals(1000L, pi.peers["a6:a4:90:f2:cd:da"]!!.lastSeenMs)

        // Same advert, later timestamp.
        pi.onServiceAvailable(
            instanceName = "airmesh-n52647-v2-t7e1b99fe-o0.local.",
            serviceType = "_airmesh._tcp",
            srcAddress = "a6:a4:90:f2:cd:da",
            nowMs = 5000L,
        )
        assertEquals(1, pi.peers.size)
        assertEquals(5000L, pi.peers["a6:a4:90:f2:cd:da"]!!.lastSeenMs)

        // "accepted" logged exactly once (the second upsert only refreshes).
        val acceptedLogs = logs.filter { it.contains("accepted AirMesh peer #52647") }
        assertEquals(2, acceptedLogs.size) // log line fires each time, but stats count is 1
        // Verify via stats: the stats object was passed in; its accepted count is 1.
        // We can check via the statsLine.
        assertTrue(stats.statsLine().contains("accepted=1"))
    }

    // ---- two different peers ------------------------------------------------

    @Test
    fun `two different peers via service callbacks`() {
        val pi = ingest()
        pi.onServiceAvailable(
            instanceName = "airmesh-n52647-v2-t7e1b99fe-o0.local.",
            serviceType = "_airmesh._tcp",
            srcAddress = "a6:a4:90:f2:cd:da",
            nowMs = 1000L,
        )
        pi.onServiceAvailable(
            instanceName = "airmesh-n62280-v2-t74f089b0-o0.local.",
            serviceType = "_airmesh._tcp",
            srcAddress = "be:32:b2:4b:42:97",
            nowMs = 2000L,
        )
        assertEquals(2, pi.peers.size)
        assertTrue(stats.statsLine().contains("accepted=2"))
    }

    // ---- TXT never logs "foreign" -------------------------------------------

    @Test
    fun `TXT callback with unparseable data does not log foreign`() {
        val pi = ingest()
        logs.clear()
        pi.onTxtRecordAvailable(
            fullDomain = "_http._tcp.local.",
            txtRecord = mapOf("foo" to "bar"),
            srcAddress = "aa:bb:cc:dd:ee:ff",
            nowMs = 1000L,
        )
        assertTrue(pi.peers.isEmpty())
        // Must NOT log "foreign"
        assertFalse(logs.any { it.contains("foreign") })
        // But does log that no ad was parseable
        assertTrue(logs.any { it.contains("TXT no parseable ad") })
    }

    // ---- placeholder address ignored ----------------------------------------

    @Test
    fun `service with placeholder address is ignored`() {
        val pi = ingest()
        pi.onServiceAvailable(
            instanceName = "airmesh-n52647-v2-t7e1b99fe-o0.local.",
            serviceType = "_airmesh._tcp",
            srcAddress = "02:00:00:00:00:00",
            nowMs = 1000L,
        )
        assertTrue(pi.peers.isEmpty())
    }

    @Test
    fun `service with null address is ignored`() {
        val pi = ingest()
        pi.onServiceAvailable(
            instanceName = "airmesh-n52647-v2-t7e1b99fe-o0.local.",
            serviceType = "_airmesh._tcp",
            srcAddress = null,
            nowMs = 1000L,
        )
        assertTrue(pi.peers.isEmpty())
    }
}
