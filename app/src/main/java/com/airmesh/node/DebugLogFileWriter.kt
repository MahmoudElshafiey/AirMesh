package com.airmesh.node

/**
 * The minimal file operations the rolling debug log writer needs (TASK S2 item 4). Production is
 * backed by java.io.File in the app's logs folder; JVM tests use an in-memory fake. Pure Kotlin —
 * no Android imports, so [DebugLogFileWriter] stays unit-testable on the JVM.
 */
interface LogFileSystem {
    /** True when [name] exists. */
    fun exists(name: String): Boolean

    /** Size of [name] in bytes; 0 when it does not exist. */
    fun sizeBytes(name: String): Long

    /** Appends the lines (each terminated with a newline) to [name], creating it if needed. */
    fun appendLines(name: String, lines: List<String>)

    /** Renames [from] onto [to]; the caller removes an existing [to] first. False on failure. */
    fun rename(from: String, to: String): Boolean

    /** Deletes [name]; false when it does not exist or deletion fails. */
    fun delete(name: String): Boolean

    /** Full text of [name], or null when it does not exist. */
    fun readAllText(name: String): String?
}

/**
 * Rolling debug log file (TASK S2 item 4). Pure Kotlin with an injectable clock and
 * [LogFileSystem] so rotation, ordering, the header line and flush batching are unit-testable.
 *
 * Keeps at most [files].size files of [maxBytesPerFile] each, newest first: when appending would
 * push the live file past its cap it is rotated into the next slot (the oldest file is dropped).
 * Lines are queued by [append] and written by [flush], which only writes once every
 * [FLUSH_INTERVAL_MS]; a failing disk never throws — the batch is dropped and later lines retry.
 */
class DebugLogFileWriter(
    private val fs: LogFileSystem,
    private val clock: () -> Long = System::currentTimeMillis,
    /** File names newest first; the first entry is the live file. Default: 2 files of 1 MB. */
    private val files: List<String> = listOf(DEBUG_LOG_FILE, PREVIOUS_DEBUG_LOG_FILE),
    private val maxBytesPerFile: Long = MAX_BYTES_PER_FILE,
) {

    private val pending = ArrayDeque<String>()
    /** The first flush is due immediately; [start] forces one anyway. */
    private var lastFlushAtMs = -FLUSH_INTERVAL_MS

    /**
     * Opens a new process session: the previous live file (if any) becomes the previous-session
     * file, and [headerLine] starts the fresh live file. Called once per process start.
     */
    fun start(headerLine: String) {
        if (fs.exists(files.first()) && fs.sizeBytes(files.first()) > 0L) rotate()
        pending.addLast(headerLine)
        flush(force = true)
    }

    /** Queues one already-formatted line; [flush] writes it at the next due interval. */
    fun append(line: String) {
        pending.addLast(line)
    }

    /**
     * Writes the queued lines when [FLUSH_INTERVAL_MS] have passed since the last write (or always
     * when [force]). Rotates before writing when the live file would exceed its cap. A disk
     * failure drops the batch instead of throwing; the next due flush writes whatever is new.
     */
    fun flush(force: Boolean = false) {
        if (!force && clock() - lastFlushAtMs < FLUSH_INTERVAL_MS) return
        if (pending.isEmpty()) return
        val lines = pending.toList().also { pending.clear() }
        runCatching {
            ensureCapacity(lines)
            fs.appendLines(files.first(), lines)
        }.onFailure { /* a failing disk must never crash the mesh */ }
        lastFlushAtMs = clock()
    }

    /** Rotates newest-first when appending [lines] would push the live file past its cap. */
    private fun ensureCapacity(lines: List<String>) {
        // joinToString + one newline per line is exactly what appendLines writes.
        val incomingBytes = (lines.joinToString("\n").length + lines.size).toLong()
        var currentSize = fs.sizeBytes(files.first())
        // currentSize > 0 stops the loop after one rotation: a batch larger than the whole cap
        // cannot be split, so it is written into the fresh file as-is.
        while (currentSize > 0L && currentSize + incomingBytes > maxBytesPerFile && files.size >= 2) {
            rotate()
            currentSize = 0L
        }
    }

    /** Shifts every file one slot toward "older"; whatever is already in the oldest slot is dropped. */
    private fun rotate() {
        // Newest first: move each older generation into the next slot, dropping what it covers.
        for (i in (1..files.lastIndex).reversed()) {
            if (!fs.exists(files[i - 1])) continue
            fs.delete(files[i]) // drop the older generation before moving into its slot
            fs.rename(files[i - 1], files[i])
        }
    }

    companion object {
        /** Live debug log file name inside getExternalFilesDir("logs"). */
        const val DEBUG_LOG_FILE = "airmesh-debug.log"

        /** Previous-session file: the live file before the last process start. */
        const val PREVIOUS_DEBUG_LOG_FILE = "airmesh-debug.old.log"

        /** Each file holds at most this many bytes (1 MB). */
        const val MAX_BYTES_PER_FILE = 1_048_576L

        /** Queued lines are written to disk at most once per interval. */
        const val FLUSH_INTERVAL_MS = 2_000L
    }
}

/** java.io-backed [LogFileSystem] for one directory (the app's logs folder). */
class FileLogFileSystem(private val dir: java.io.File) : LogFileSystem {

    private fun file(name: String) = java.io.File(dir, name)

    override fun exists(name: String): Boolean = file(name).exists()

    override fun sizeBytes(name: String): Long = file(name).length() // 0 when missing

    override fun appendLines(name: String, lines: List<String>) {
        file(name).appendText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    override fun rename(from: String, to: String): Boolean = file(from).renameTo(file(to))

    override fun delete(name: String): Boolean = file(name).delete()

    override fun readAllText(name: String): String? {
        val f = file(name)
        return if (f.exists()) f.readText(Charsets.UTF_8) else null
    }
}
