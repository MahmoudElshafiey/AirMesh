package com.airmesh.transport

import com.airmesh.node.EventLog
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the two-phase [TcpMeshTransport.connect] outcome reporting:
 * a refused port must fail in CONNECT, a listener that accepts but never sends
 * HELLO must fail in HANDSHAKE with its own timeout, and a raw probe connection
 * (plain connect + close) must not break the server's real clients.
 */
class TcpMeshTransportConnectOutcomeTest {

    private companion object {
        const val HOST = "127.0.0.1"
        const val FIXED_TIME = 1_700_000_000_000L
        const val POLL_MS = 5L
    }

    @Test
    fun connectToClosedPortFailsInConnectPhase() = runBlocking {
        // A port that was just released: the OS refuses new connections.
        val closedPort = ServerSocket(0).use { it.localPort }
        val eventLog = EventLog(clock = { FIXED_TIME })
        val client = TcpMeshTransport(localNodeId = 1, eventLog = eventLog)

        try {
            val outcome = client.connect(HOST, closedPort, connectTimeoutMs = 500)

            assertTrue("expected a failure, got $outcome", outcome is ConnectOutcome.Failure)
            val failure = outcome as ConnectOutcome.Failure
            assertEquals(ConnectPhase.CONNECT, failure.phase)
            assertEquals("ConnectException", failure.exceptionClass)
            assertTrue(failure.elapsedMs >= 0)

            val logText = eventLog.entries.value.joinToString("\n") { it.text }
            assertTrue(logText.contains("connecting to $HOST:$closedPort"))
            assertTrue(logText.contains("connect phase failed"))
        } finally {
            client.stop()
        }
    }

    @Test
    fun serverThatAcceptsButNeverSendsHelloFailsInHandshakePhase() = runBlocking {
        // A raw listener that completes the TCP handshake but stays silent:
        // the client must time out in HANDSHAKE, not hang.
        val server = ServerSocket().apply { bind(InetSocketAddress(HOST, 0)) }
        var accepted: Socket? = null
        val acceptThread = Thread {
            runCatching { accepted = server.accept() }
        }.also { it.start() }

        val eventLog = EventLog(clock = { FIXED_TIME })
        val client = TcpMeshTransport(localNodeId = 1, eventLog = eventLog)

        try {
            val outcome = client.connect(HOST, server.localPort, handshakeTimeoutMs = 300)

            assertTrue("expected a failure, got $outcome", outcome is ConnectOutcome.Failure)
            val failure = outcome as ConnectOutcome.Failure
            assertEquals(ConnectPhase.HANDSHAKE, failure.phase)
            assertEquals("SocketTimeoutException", failure.exceptionClass)
            // soTimeout was 300ms; allow timer jitter but not an early return.
            assertTrue("elapsed ${failure.elapsedMs}ms is below the 300ms handshake budget",
                failure.elapsedMs >= 250,
            )

            val logText = eventLog.entries.value.joinToString("\n") { it.text }
            assertTrue(logText.contains("connected:"))
            assertTrue(logText.contains("HELLO sent"))
            assertTrue(logText.contains("handshake phase failed"))
        } finally {
            client.stop()
            accepted?.close()
            server.close()
            acceptThread.join(2_000)
        }
    }

    @Test
    fun successfulConnectReportsTheRemoteNodeId() = runBlocking {
        val eventLog = EventLog(clock = { FIXED_TIME })
        val server = TcpMeshTransport(localNodeId = 2, eventLog = eventLog)
        val client = TcpMeshTransport(localNodeId = 1, eventLog = eventLog)

        try {
            val port = server.listen(0)
            val outcome = client.connect(HOST, port)

            assertEquals(ConnectOutcome.Success(remoteNodeId = 2), outcome)
            awaitUntil("both sides register the neighbor") {
                client.neighbors.value == setOf(2) && server.neighbors.value == setOf(1)
            }

            val logText = eventLog.entries.value.joinToString("\n") { it.text }
            assertTrue(logText.contains("accept loop started"))
            assertTrue(logText.contains("accepted connection from"))
            assertTrue(logText.contains("handshake complete: neighbor 2 is live"))
        } finally {
            client.stop()
            server.stop()
        }
    }

    @Test
    fun serverToleratesProbeConnectionsAndStillServesRealClients() = runBlocking {
        val eventLog = EventLog(clock = { FIXED_TIME })
        val server = TcpMeshTransport(localNodeId = 2, eventLog = eventLog)
        val client = TcpMeshTransport(localNodeId = 1, eventLog = eventLog)

        try {
            val port = server.listen(0)

            // The raw reachability probe: plain connect, immediate close.
            Socket().use { it.connect(InetSocketAddress(HOST, port), 5_000) }

            awaitUntil("server logged the probe connection") {
                eventLog.entries.value.count { it.text.contains("accepted connection from") } >= 1
            }

            // The real attempt must still succeed afterwards.
            val outcome = client.connect(HOST, port)
            assertEquals(ConnectOutcome.Success(remoteNodeId = 2), outcome)

            awaitUntil("server logged both the probe and the real connection") {
                eventLog.entries.value.count { it.text.contains("accepted connection from") } >= 2
            }
        } finally {
            client.stop()
            server.stop()
        }
    }

    private suspend fun awaitUntil(
        description: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(timeoutMs) {
                while (!condition()) delay(POLL_MS)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("timed out after ${timeoutMs}ms waiting for $description", timeout)
        }
    }
}
