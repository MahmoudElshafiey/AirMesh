package com.airmesh.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A packet received from a directly connected neighbor.
 *
 * @property senderId node id of the neighbor that delivered the bytes
 *   (not necessarily the original author of the payload).
 * @property bytes raw payload handed to the routing layer; ownership transfers
 *   to the receiver.
 *
 * Note: the generated data-class equals/hashCode compare [bytes] by reference.
 * Use contentEquals when comparing packets.
 */
data class ReceivedPacket(
    val senderId: Int,
    val bytes: ByteArray,
)

/**
 * Link between the routing layer and a concrete transport.
 *
 * The routing layer depends only on this interface: the real Wi-Fi Direct
 * transport and the FakeTransport simulator will both implement it. It is pure
 * Kotlin (no Android imports) so routing logic stays unit-testable on the JVM.
 *
 * Members may be called from any coroutine context. Packets are best-effort:
 * there is no delivery acknowledgement.
 */
interface Transport {

    /**
     * Hot stream of every packet received from any directly connected neighbor.
     * Deduplication and TTL handling belong to the routing layer, not the
     * transport. Emissions may arrive on any dispatcher, so collectors should
     * not block.
     */
    val incoming: Flow<ReceivedPacket>

    /**
     * Node ids of the neighbors that are directly reachable right now.
     * Empty until [start] is called; cleared again by [stop].
     */
    val neighbors: StateFlow<Set<Int>>

    /**
     * Best-effort send of [bytes] to every current neighbor.
     * Suspends only until the packet is handed to the transport, not until it
     * is delivered. An empty neighbor set is not an error.
     */
    suspend fun broadcast(bytes: ByteArray)

    /**
     * Best-effort unicast of [bytes] to [neighborId].
     *
     * If the neighbor is not currently reachable the packet may be dropped
     * (implementations should log it); no exception is thrown, because
     * neighbor churn is normal in a mesh.
     */
    suspend fun send(neighborId: Int, bytes: ByteArray)

    /**
     * Starts discovery and listening. Idempotent; may be called again after
     * [stop].
     */
    fun start()

    /**
     * Releases sockets and listeners and clears [neighbors]. Idempotent.
     * In-flight sends are not guaranteed to complete.
     */
    fun stop()
}
