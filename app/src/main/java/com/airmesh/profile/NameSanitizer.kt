package com.airmesh.profile

/**
 * Cleans raw user input into a mesh display name. Pure Kotlin (no Android) so
 * it is unit-testable on the JVM and can be shared by the node and any future UI.
 */
object NameSanitizer {

    /** Maximum number of Unicode code points in a display name. */
    const val MAX_CODE_POINTS = 24

    private val WHITESPACE = Regex("\\s+")

    /**
     * Sanitizes [raw] into a display name, or null when nothing usable remains:
     * strips bidi override/isolate characters and control/newline characters,
     * trims the ends, collapses inner whitespace runs to single spaces, and caps
     * the result at [MAX_CODE_POINTS] code points.
     */
    fun sanitize(raw: String): String? {
        val stripped = buildString {
            for (ch in raw) {
                if (isBidiOverrideOrIsolate(ch)) continue
                // isISOControl covers \n, \r and the other C0/C1 control chars.
                if (ch.isISOControl()) continue
                append(ch)
            }
        }

        val collapsed = stripped.trim().split(WHITESPACE).joinToString(" ")
        if (collapsed.isEmpty()) return null

        return capToCodePoints(collapsed, MAX_CODE_POINTS)
    }

    /** Bidi overrides/isolates that could be used to spoof a name's direction. */
    private fun isBidiOverrideOrIsolate(ch: Char): Boolean =
        ch.code in 0x202A..0x202E || ch.code in 0x2066..0x2069

    /** Truncates [s] to at most [max] Unicode code points (surrogate-pair safe). */
    private fun capToCodePoints(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        val end = s.offsetByCodePoints(0, max)
        return s.substring(0, end)
    }
}
