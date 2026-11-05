package com.airmesh.transport

import com.airmesh.protocol.Frame
import com.airmesh.protocol.FrameCodec
import com.airmesh.protocol.FrameType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Plain-TCP implementation of [Transport], usable on the JVM and on Android.
 *
 * A node joins links in two ways:
 * - [listen] makes it a hub: it accepts any number of clients.
 * - [connect] makes it a client of one hub.
 * A node may do both, even at the same time, and may reconnect after [stop].
 *
 * Every socket carries a sequence of length-prefixed frames: a 2-byte
 * big-endian length followed by exactly that many bytes of one
 * [FrameCodec]-encoded frame. A length of zero or one above the maximum frame
 * size is a malformed stream, so that socket is closed. A frame whose body
 * fails CRC or decoding is dropped and counted in [droppedCorruptFrames] while
 * the stream continues, because the next length prefix still starts at a known
 * offset.
 *
 * Immediately after a socket connects, both sides send a HELLO frame with their
 * own node id and read the peer's HELLO. Only then does the peer become a
 * neighbor. HELLO frames are consumed here and are never exposed on
 * [incoming], so the Router cannot flood them.
 *
 * If two sockets report the same node id, the most recently handshaken socket
 * wins and the older one is closed. A failed write also removes that neighbor.
 * Writes to one socket are serialized by a per-socket mutex; no global lock is
 * held during socket I/O.
 *
 * @param localNodeId 16-bit AirMesh node id advertised in HELLO frames.
 * @param dispatcher runs accept, connect, and socket-reader coroutines. It is
 *   injectable so tests can control the threading.
 */
class TcpMeshTransport(
    private val localNodeId: Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Transport {

    private companion object {
        /** Protocol version written into transport HELLO frames. */
        const val PROTOCOL_VERSION = 1

        /**
         * Largest frame on the wire: a full header, the maximum payload, and
         * the CRC. The length prefix cannot exceed this value.
         */
        const val MAX_FRAME_SIZE = FrameCodec.MIN_FRAME_SIZE + FrameCodec.MAX_PAYLOAD

        const val CONNECT_TIMEOUT_MS = 5_000
        const val HANDSHAKE_TIMEOUT_MS = 5_000
    }

    init {
        require(localNodeId in 0..0xFFFF) {
            "localNodeId must fit in 16 bits: $localNodeId"
        }
    }

    /** Guards only the lifecycle fields, peer map, and neighbor set. */
    private val lifecycleLock = Any()

    private var scope: CoroutineScope? = null
    private var running = false
    private var serverSocket: ServerSocket? = null

    /** Live, handshaken peers keyed by the node id they announced. */
    private val peers = ConcurrentHashMap<Int, PeerConnection>()

    private val _neighbors = MutableStateFlow<Set<Int>>(emptySet())
    override val neighbors: StateFlow<Set<Int>> = _neighbors.asStateFlow()

    private val _incoming = MutableSharedFlow<ReceivedPacket>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incoming: Flow<ReceivedPacket> = _incoming.asSharedFlow()

    private val corruptFrames = AtomicInteger(0)

    /** Number of frame bodies dropped because CRC or decoding failed. */
    val droppedCorruptFrames: Int
        get() = corruptFrames.get()

    private val helloBytes: ByteArray = FrameCodec.encode(
        Frame(
            version = PROTOCOL_VERSION,
            type = FrameType.HELLO,
            flags = 0,
            nodeId = localNodeId,
            seq = 0,
            ttl = 1,
            payload = ByteArray(0),
        ),
    )

    /** Starts the coroutine scope. Idempotent; safe after [stop]. */
    override fun start() {
        synchronized(lifecycleLock) {
            if (running) return
            running = true
            scope = CoroutineScope(
                SupervisorJob() + dispatcher + CoroutineName("TcpMeshTransport-$localNodeId"),
            )
        }
    }

    /** Closes the listener and every socket, then clears [neighbors]. */
    override fun stop() {
        val oldScope: CoroutineScope?
        val oldServer: ServerSocket?
        val oldPeers: List<PeerConnection>
        synchronized(lifecycleLock) {
            running = false
            oldScope = scope
            oldServer = serverSocket
            oldPeers = peers.values.toList()
            scope = null
            serverSocket = null
            peers.clear()
            _neighbors.value = emptySet()
        }

        oldServer?.closeQuietly()
        oldPeers.forEach { it.close() }
        oldScope?.cancel()
    }

    /**
     * Starts accepting clients on [port] and returns the bound port.
     *
     * Passing 0 asks the OS for a free port, which the return value reports so
     * tests never need a hard-coded port. Calling it again replaces the
     * previous listener.
     */
    fun listen(port: Int): Int {
        require(port in 0..0xFFFF) { "port must be in 0..65535: $port" }
        ensureStarted()

        val bound = ServerSocket()
        try {
            bound.reuseAddress = true
            bound.bind(InetSocketAddress(port))
        } catch (error: IOException) {
            bound.closeQuietly()
            throw error
        }
        val actualPort = bound.localPort

        var previous: ServerSocket? = null
        val owner = synchronized(lifecycleLock) {
            if (!running) {
                bound.closeQuietly()
                throw IllegalStateException("transport was stopped while binding")
            }
            previous = serverSocket
            serverSocket = bound
            checkNotNull(scope)
        }

        previous?.closeQuietly()
        owner.launch { acceptLoop(bound, owner) }
        return actualPort
    }

    /** Opens one client connection to a hub and performs the HELLO exchange. */
    fun connect(host: String, port: Int) {
        require(port in 0..0xFFFF) { "port must be in 0..65535: $port" }
        val owner = ensureStarted()

        owner.launch {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                handleSocket(socket)
            } catch (error: CancellationException) {
                socket.closeQuietly()
                throw error
            } catch (error: IOException) {
                socket.closeQuietly()
            }
        }
    }

    override suspend fun broadcast(bytes: ByteArray) {
        // Snapshot first: the map may change while sockets are written to.
        for ((neighborId, peer) in peers.entries.toList()) {
            try {
                peer.write(bytes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                removePeer(neighborId, peer)
            }
        }
    }

    override suspend fun send(neighborId: Int, bytes: ByteArray) {
        val peer = peers[neighborId] ?: return
        try {
            peer.write(bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            removePeer(neighborId, peer)
        }
    }

    private fun ensureStarted(): CoroutineScope {
        start()
        return synchronized(lifecycleLock) {
            checkNotNull(scope) { "transport failed to start" }
        }
    }

    private suspend fun acceptLoop(server: ServerSocket, owner: CoroutineScope) {
        while (currentCoroutineContext().isActive && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (error: IOException) {
                // Closing the listener during stop() also lands here.
                if (!currentCoroutineContext().isActive || server.isClosed) return
                continue
            }

            if (!owner.isActive) {
                socket.closeQuietly()
                return
            }
            owner.launch { handleSocket(socket) }
        }
    }

    private suspend fun handleSocket(socket: Socket) {
        val peer = try {
            PeerConnection(socket)
        } catch (error: IOException) {
            socket.closeQuietly()
            return
        }

        var remoteId: Int? = null
        try {
            socket.tcpNoDelay = true
            // A peer that never sends HELLO must not pin a coroutine forever.
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS

            peer.write(helloBytes)
            val helloResult = peer.readFrame() ?: return
            val hello = FrameCodec.decode(helloResult).getOrNull()
            if (hello == null) {
                corruptFrames.incrementAndGet()
                return
            }
            if (hello.type != FrameType.HELLO) return
            if (hello.nodeId == localNodeId) return

            socket.soTimeout = 0
            remoteId = hello.nodeId
            if (!register(hello.nodeId, peer)) return
            readFrames(hello.nodeId, peer)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            // Peer closed, timed out, or sent a malformed stream.
        } finally {
            remoteId?.let { removePeer(it, peer) }
            peer.close()
        }
    }

    private suspend fun readFrames(remoteId: Int, peer: PeerConnection) {
        while (true) {
            val bytes = peer.readFrame() ?: return
            val frame = FrameCodec.decode(bytes).getOrNull()
            if (frame == null) {
                corruptFrames.incrementAndGet()
                continue
            }
            // Handshake traffic is transport-internal and never routed.
            if (frame.type == FrameType.HELLO) continue
            _incoming.emit(ReceivedPacket(senderId = remoteId, bytes = bytes))
        }
    }

    /**
     * Installs [peer] as the live connection for [remoteId].
     *
     * Returns false when [stop] won the race, in which case the caller closes
     * the socket. The previous connection for the same id is returned and must
     * be closed by the caller, outside [lifecycleLock].
     */
    private fun register(remoteId: Int, peer: PeerConnection): Boolean {
        var previous: PeerConnection? = null
        synchronized(lifecycleLock) {
            if (!running) return false
            previous = peers.put(remoteId, peer)
            _neighbors.value = peers.keys.toSet()
        }
        previous?.close()
        return true
    }

    private fun removePeer(remoteId: Int, peer: PeerConnection) {
        var removed = false
        synchronized(lifecycleLock) {
            removed = peers.remove(remoteId, peer)
            if (removed) {
                _neighbors.value = peers.keys.toSet()
            }
        }
        if (removed) peer.close()
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    private fun ServerSocket.closeQuietly() {
        runCatching { close() }
    }

    /**
     * One socket with its framing streams.
     *
     * [writeMutex] is the per-socket write lock; senders never hold the
     * transport-wide [lifecycleLock] while touching a stream.
     */
    private inner class PeerConnection(private val socket: Socket) {

        private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        private val writeMutex = Mutex()
        private val closed = AtomicBoolean(false)

        suspend fun write(bytes: ByteArray) {
            if (bytes.size > MAX_FRAME_SIZE) {
                throw IOException("frame is ${bytes.size} bytes, max is $MAX_FRAME_SIZE")
            }
            writeMutex.withLock {
                if (closed.get()) throw IOException("connection is closed")
                output.writeShort(bytes.size)
                output.write(bytes)
                output.flush()
            }
        }

        /**
         * Reads one length-prefixed frame body.
         *
         * Returns null on a clean end of stream. Zero or oversized lengths are
         * malformed and throw, so the caller closes the socket.
         */
        fun readFrame(): ByteArray? {
            val high = input.read()
            if (high < 0) return null
            val low = input.read()
            if (low < 0) throw EOFException("truncated frame length")

            val size = (high shl 8) or low
            if (size == 0 || size > MAX_FRAME_SIZE) {
                throw IOException("malformed frame length: $size")
            }

            val bytes = ByteArray(size)
            input.readFully(bytes)
            return bytes
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                socket.closeQuietly()
            }
        }
    }
}
