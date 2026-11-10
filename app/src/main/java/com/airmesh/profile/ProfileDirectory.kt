package com.airmesh.profile

/** One known display name: what a node calls itself, and when we last accepted it. */
data class ProfileInfo(
    val name: String,
    val colorIndex: Int,
    val updatedAt: Long,
)

/**
 * In-memory directory of mesh display names, keyed by node id.
 *
 * Pure Kotlin (no Android): the clock and [ProfileStorage] are injected so JVM
 * tests can drive time and persistence deterministically. Every method is
 * synchronized because frames arrive on a multi-threaded dispatcher.
 */
class ProfileDirectory(
    private val localNodeId: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val storage: ProfileStorage? = null,
) {

    companion object {
        /** Maximum remembered nodes; the least recently updated one is evicted. */
        const val MAX_ENTRIES = 500

        /** A node may push a new name at most once per this interval (ms). */
        const val MIN_UPDATE_INTERVAL_MS = 10_000L

        /** Storage key under which the directory's JSON document is kept. */
        const val STORAGE_KEY = "profile_directory"
    }

    private val lock = Any()
    private val entries = LinkedHashMap<Int, ProfileInfo>()

    init {
        // Absent or corrupted stored data simply starts the directory empty.
        storage?.getString(STORAGE_KEY)?.let { text ->
            ProfileDirectoryJson.decode(text)?.let { loaded ->
                synchronized(lock) { entries.putAll(loaded) }
            }
        }
    }

    /**
     * Accepts a profile update for [nodeId] received from the network. Returns
     * false and changes nothing when:
     *  - it claims to be our own node id (we only learn ours locally), or
     *  - we accepted an update for that node less than [MIN_UPDATE_INTERVAL_MS]
     *    ago, measured from the last ACCEPTED update.
     */
    fun acceptFromNetwork(nodeId: Int, name: String, colorIndex: Int): Boolean = synchronized(lock) {
        if (nodeId == localNodeId) return@synchronized false

        val now = clock()
        val existing = entries[nodeId]
        // "Older than 10s" is strict: an update exactly 10s later is still too soon.
        if (existing != null && now - existing.updatedAt <= MIN_UPDATE_INTERVAL_MS) {
            return@synchronized false
        }

        entries[nodeId] = ProfileInfo(name, colorIndex, now)
        evictLocked()
        persistLocked()
        true
    }

    /** Display label for [nodeId]: the known name, or a stable fallback. */
    fun label(nodeId: Int): String = synchronized(lock) {
        entries[nodeId]?.name ?: "User #$nodeId"
    }

    /** Consistent copy of all known profiles (for UI snapshots). */
    fun snapshot(): Map<Int, ProfileInfo> = synchronized(lock) { entries.toMap() }

    private fun evictLocked() {
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.minByOrNull { it.value.updatedAt } ?: break
            entries.remove(oldest.key)
        }
    }

    private fun persistLocked() {
        storage?.putString(STORAGE_KEY, ProfileDirectoryJson.encode(entries))
    }
}

/**
 * Minimal hand-rolled JSON for the directory document:
 * `{"<nodeId>":{"n":"name","c":color,"t":updatedMillis},...}`
 *
 * No JSON library is available in this project. The decoder only accepts the
 * shape [encode] produces and returns null on anything else, so corrupted
 * storage is ignored instead of crashing.
 */
internal object ProfileDirectoryJson {

    fun encode(entries: Map<Int, ProfileInfo>): String = buildString {
        append('{')
        var first = true
        for ((nodeId, info) in entries) {
            if (!first) append(',')
            first = false
            append('"').append(nodeId).append("\":{\"n\":")
            append(encodeString(info.name))
            append(",\"c\":").append(info.colorIndex)
            append(",\"t\":").append(info.updatedAt).append('}')
        }
        append('}')
    }

    /** Parses a document produced by [encode]; null when it does not match. */
    fun decode(text: String): Map<Int, ProfileInfo>? {
        val r = Reader(text)
        if (!r.expect('{')) return null
        val result = LinkedHashMap<Int, ProfileInfo>()

        while (true) {
            r.skipWs()
            when (r.peek()) {
                null -> return null // unexpected end of input
                '}' -> { r.advance(); break }
                ',' -> { r.advance(); continue } // tolerate stray/trailing commas
                '"' -> Unit
                else -> return null
            }

            val nodeId = r.parseString()?.toIntOrNull() ?: return null
            if (!r.expect(':') || !r.expect('{')) return null

            var name: String? = null
            var color: Int? = null
            var time: Long? = null
            while (true) {
                r.skipWs()
                when (r.peek()) {
                    null -> return null
                    '}' -> { r.advance(); break }
                    ',' -> { r.advance(); continue } // tolerate stray/trailing commas
                    '"' -> Unit
                    else -> return null
                }
                val key = r.parseString() ?: return null
                if (!r.expect(':')) return null
                when (key) {
                    "n" -> name = r.parseString() ?: return null
                    "c" -> {
                        val n = r.parseNumber() ?: return null
                        if (n !in Int.MIN_VALUE..Int.MAX_VALUE) return null
                        color = n.toInt()
                    }
                    "t" -> time = r.parseNumber() ?: return null
                    else -> return null // unknown field: treat as corrupted
                }
            }
            if (name == null || color == null || time == null) return null
            result[nodeId] = ProfileInfo(name, color, time)
        }

        r.skipWs()
        return if (r.atEnd()) result else null // trailing garbage: corrupted
    }

    private fun encodeString(s: String): String = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch.code < 0x20 -> append("\\u").append("%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }

    /** Index-based scanner for the exact document shape [encode] produces. */
    private class Reader(val text: String) {
        var i = 0
            private set

        fun peek(): Char? = text.getOrNull(i)
        fun atEnd(): Boolean = i >= text.length
        fun advance() { i++ }
        fun skipWs() { while (i < text.length && text[i].isWhitespace()) i++ }

        fun expect(c: Char): Boolean {
            skipWs()
            if (peek() == c) {
                i++
                return true
            }
            return false
        }

        /** A quoted string with JSON escapes; null on malformed input. */
        fun parseString(): String? {
            if (!expect('"')) return null
            val sb = StringBuilder()
            while (true) {
                when (val ch = peek()) {
                    null -> return null
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        when (val esc = peek()) {
                            null -> return null
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 5 > text.length) return null
                                val code = text.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                        i++
                    }
                    else -> { sb.append(ch); i++ }
                }
            }
        }

        /** A non-negative (or '-') integer; null when no digits are present. */
        fun parseNumber(): Long? {
            skipWs()
            val start = i
            if (peek() == '-') i++
            val digitsStart = i
            while (i < text.length && text[i].isDigit()) i++
            if (i == digitsStart) return null
            return text.substring(start, i).toLongOrNull()
        }
    }
}
