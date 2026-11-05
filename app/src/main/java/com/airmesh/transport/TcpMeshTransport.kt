package com.airmesh.transport

import com.airmesh.node.EventLog
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
import java.util.concurrent.TimeUnit
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
import kotlinx.coroutines.withContext

/** Which phase of a client connection attempt failed. */
enum class ConnectPhase {
    /** The TCP connect itself (three-way handshake) did not complete. */
    CONNECT,

    /** TCP connected, but the HELLO exchange did not finish in time. */
    HANDSHAKE,
}

/** Outcome of one [TcpMeshTransport.connect] attempt. */
sealed interface ConnectOutcome {
    /** The HELLO exchange completed and the peer is now a live neighbor. */
    data class Success(val remoteNodeId: Int) : ConnectOutcome

    /**
     * The attempt failed in [phase]. [exceptionClass] and [message] describe
     * the underlying exception (or a short reason for non-exception failures);
     * [elapsedMs] is how long the whole attempt took.
     */
    data class Failure(
        val phase: ConnectPhase,
        val exceptionClass: String,
        val message: String?,
        val elapsedMs: Long,
    ) : ConnectOutcome
}

/**
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
    /** Optional shared diagnostic log; when set, socket events are written here. */
    private val eventLog: EventLog? = null,
) : Transport {

    private companion object {
        /** Protocol version written into transport HELLO frames. */
        const val PROTOCOL_VERSION = 1

        /**
         * Largest frame on the wire: a full header, the maximum payload, and
         * the CRC. The length prefix cannot exceed this value.
         */
        const val MAX_FRAME_SIZE = FrameCodec.MIN_FRAME_SIZE + FrameCodec.MAX_PAYLOAD

        /** Default timeout for the TCP connect phase of [connect]. */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L

        /** Default timeout for the HELLO handshake phase. */
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 5_000L
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
        // API 37 stubs dropped InetSocketAddress.getHostAddress(); go via InetAddress.
        val boundHost = (bound.localSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"
        log("accept loop started on $boundHost:$actualPort")
        return actualPort
    }

    /**
     * Opens one client connection to a hub and performs the HELLO exchange.
     *
     * The attempt runs in two phases with separate, configurable timeouts so
     * on-device logs can tell them apart:
     * - [ConnectPhase.CONNECT]: the TCP three-way handshake, bounded by
     *   [connectTimeoutMs]. A refused port surfaces as ConnectException here.
     * - [ConnectPhase.HANDSHAKE]: sending our HELLO and waiting for the peer's,
     *   bounded by [handshakeTimeoutMs]. A listener that accepts but never
     *   answers surfaces as SocketTimeoutException here.
     *
     * On success the socket stays open: a reader coroutine keeps consuming
     * frames until it closes. The returned [ConnectOutcome] reports which phase
     * failed (exception class, message, elapsed ms) so callers can log an
     * actionable line instead of guessing from silence.
     */
    suspend fun connect(
        host: String,
        port: Int,
        connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    ): ConnectOutcome {
        require(port in 0..0xFFFF) { "port must be in 0..65535: $port" }
        val owner = ensureStarted()

        val startedAtNanos = System.nanoTime()
        return withContext(dispatcher) {
            log("connecting to $host:$port (connect timeout ${connectTimeoutMs}ms)")
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs.toInt())
            } catch (error: CancellationException) {
                socket.closeQuietly()
                throw error
            } catch (error: IOException) {
                socket.closeQuietly()
                log("connect phase failed after ${elapsedMs(startedAtNanos)}ms: ${describe(error)}")
                return@withContext ConnectOutcome.Failure(
                    phase = ConnectPhase.CONNECT,
                    exceptionClass = error.javaClass.simpleName,
                    message = error.message,
                    elapsedMs = elapsedMs(startedAtNanos),
                )
            }

            log(
                "connected: local=${socket.localAddress?.hostAddress}:${socket.localPort} " +
                    "remote=${socket.remoteHost()}:$port",
            )

            val peer = try {
                PeerConnection(socket)
            } catch (error: IOException) {
                socket.closeQuietly()
                log("handshake phase failed after ${elapsedMs(startedAtNanos)}ms: ${describe(error)}")
                return@withContext ConnectOutcome.Failure(
                    phase = ConnectPhase.HANDSHAKE,
                    exceptionClass = error.javaClass.simpleName,
                    message = error.message,
                    elapsedMs = elapsedMs(startedAtNanos),
                )
            }

            var registeredId: Int? = null
            try {
                val remoteId = handshake(socket, peer, handshakeTimeoutMs)
                if (!register(remoteId, peer)) {
                    // stop() won the race: the socket is valid but no longer wanted.
                    log("handshake complete with node $remoteId but transport stopped; closing")
                    return@withContext ConnectOutcome.Failure(
                        phase = ConnectPhase.HANDSHAKE,
                        exceptionClass = "TransportStopped",
                        message = null,
                        elapsedMs = elapsedMs(startedAtNanos),
                    )
                }
                registeredId = remoteId
                startReader(owner, remoteId, peer)
                log(
                    "handshake complete: neighbor $remoteId is live " +
                        "(attempt took ${elapsedMs(startedAtNanos)}ms)",
                )
                ConnectOutcome.Success(remoteId)
            } catch (error: CancellationException) {
                socket.closeQuietly()
                registeredId?.let { removePeer(it, peer) }
                throw error
            } catch (error: IOException) {
                log("handshake phase failed after ${elapsedMs(startedAtNanos)}ms: ${describe(error)}")
                ConnectOutcome.Failure(
                    phase = ConnectPhase.HANDSHAKE,
                    exceptionClass = error.javaClass.simpleName,
                    message = error.message,
                    elapsedMs = elapsedMs(startedAtNanos),
                )
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
                log("accept loop error (continuing): ${describe(error)}")
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
        log("accepted connection from ${socket.remoteHost()}:${socket.port}")
        val peer = try {
            PeerConnection(socket)
        } catch (error: IOException) {
            log("reader setup failed for ${socket.remoteHost()}:${socket.port} — ${describe(error)}")
            socket.closeQuietly()
            return
        }

        var remoteId: Int? = null
        try {
            socket.tcpNoDelay = true
            remoteId = handshake(socket, peer, DEFAULT_HANDSHAKE_TIMEOUT_MS)
            if (!register(remoteId, peer)) return
            readFrames(remoteId, peer)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            // Peer closed, timed out, or sent a malformed stream. A raw probe
            // connection that closes before answering HELLO lands here too.
            val where = remoteId?.let { "reader for node $it" }
                ?: "handshake with ${socket.remoteHost()}:${socket.port}"
            log("$where ended: ${describe(error)}")
        } finally {
            remoteId?.let { removePeer(it, peer) }
            peer.close()
        }
    }

    /**
     * Performs the HELLO exchange on an already-connected [socket].
     *
     * Both sides send their HELLO immediately and wait for the peer's; a peer
     * that never answers must not pin a coroutine forever, so reads are bounded
     * by [timeoutMs]. Returns the peer's node id. Throws IOException when the
     * peer times out, resets, or sends a malformed or invalid HELLO — the
     * message says which case happened.
     */
    private suspend fun handshake(socket: Socket, peer: PeerConnection, timeoutMs: Long): Int {
        socket.soTimeout = timeoutMs.toInt()

        log("HELLO sent")
        peer.write(helloBytes)

        val helloResult = peer.readFrame() ?: throw EOFException("peer closed before sending HELLO")
        val hello = FrameCodec.decode(helloResult).getOrNull()
            ?: run {
                corruptFrames.incrementAndGet()
                throw IOException("HELLO frame failed CRC or decoding")
            }
        if (hello.type != FrameType.HELLO) {
            throw IOException("expected a HELLO frame, got ${hello.type}")
        }
        if (hello.nodeId == localNodeId) {
            throw IOException("peer announced our own node id $localNodeId")
        }

        log("HELLO received (node id ${hello.nodeId})")
        socket.soTimeout = 0
        return hello.nodeId
    }

    /**
     * Keeps consuming frames from [peer] until the socket ends, then removes
     * the neighbor and closes the connection. Runs in its own coroutine so a
     * finished [connect] does not pin the caller for the link's lifetime.
     */
    private fun startReader(owner: CoroutineScope, remoteId: Int, peer: PeerConnection) {
        owner.launch {
            try {
                readFrames(remoteId, peer)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                log("reader for node $remoteId ended: ${describe(error)}")
            } finally {
                removePeer(remoteId, peer)
                peer.close()
            }
        }
    }

    /** Writes one line to the shared diagnostic log, if one was provided. */
    private fun log(message: String) {
        eventLog?.log("TCP: $message")
    }

    /** Milliseconds elapsed since a [System.nanoTime] stamp. */
    private fun elapsedMs(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

    /** "ClassName: message" for log lines and failure reports. */
    private fun describe(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message}"

    /**
     * Host part of this socket's remote address, or "?" when not connected yet.
     * The API 37 platform stubs dropped [InetSocketAddress.getHostAddress], so
     * the IP string comes from the wrapped InetAddress instead.
     */
    private fun Socket.remoteHost(): String =
        (remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"

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
