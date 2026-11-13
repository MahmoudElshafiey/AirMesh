package com.airmesh.ui

import com.airmesh.profile.NameSanitizer
import com.airmesh.profile.ProfileInfo
import com.airmesh.protocol.ProfilePayload

/**
 * Pure presentation helpers for mesh display names and avatars.
 *
 * No Android imports: the palette is returned as an ARGB [Long] and the Compose
 * layer wraps it in `Color(...)`, so every function is unit-testable on the JVM.
 */

/** Longest display name a PROFILE payload carries, in Unicode code points. */
const val PROFILE_NAME_MAX_CODE_POINTS: Int = ProfilePayload.MAX_NAME_CODE_POINTS

/** Number of avatar palette entries (color indices 0..7). */
const val AVATAR_COLOR_COUNT: Int = ProfilePayload.MAX_COLOR_INDEX + 1

/**
 * Avatar palette, indexed 0..7. Every entry is a dark saturated tone so white
 * initials keep a contrast ratio above 4.5:1 on both the light and the dark
 * surface — the fill, not the text color, makes the circle readable.
 */
private val AVATAR_COLORS = longArrayOf(
    0xFF1565C0, // blue
    0xFF00695C, // teal
    0xFF2E7D32, // green
    0xFF6A1B9A, // purple
    0xFFAD1457, // magenta
    0xFFC62828, // red
    0xFF4E342E, // brown
    0xFF37474F, // blue-grey
)

private val WHITESPACE = Regex("\\s+")
private const val ZERO_WIDTH_JOINER = 0x200D

/**
 * Label for [nodeId]: the name the mesh learned for it, or a stable fallback.
 * Blank names (never sent by the node, but cheap to guard) also fall back.
 */
fun displayName(nodeId: Int, profiles: Map<Int, ProfileInfo>): String {
    val name = profiles[nodeId]?.name?.trim()
    return if (name.isNullOrEmpty()) "User #$nodeId" else name
}

/**
 * Avatar color for [nodeId]: the color that node picked for itself, or a
 * deterministic one derived from its id so unknown senders stay distinguishable.
 */
fun avatarColorIndex(nodeId: Int, profiles: Map<Int, ProfileInfo>): Int =
    profiles[nodeId]?.colorIndex ?: Math.floorMod(nodeId, AVATAR_COLOR_COUNT)

/** ARGB fill for palette entry [index]; out-of-range indices clamp to the ends. */
fun avatarColor(index: Int): Long = AVATAR_COLORS[index.coerceIn(0, AVATAR_COLORS.lastIndex)]

/**
 * Up to two initials for an avatar: the first character (or whole emoji) of the
 * first two words, uppercased. Emoji and other caseless characters are returned
 * unchanged; punctuation such as the '#' of "User #7" is skipped so the digit
 * after it becomes the initial ("U7").
 */
fun avatarInitials(name: String): String {
    val words = name.trim().split(WHITESPACE).filter { it.isNotEmpty() }.take(2)
    val initials = buildString {
        for (word in words) append(firstVisibleInitial(word) ?: continue)
    }
    return initials.ifEmpty { "?" }
}

/** First letter/digit/emoji of [word], skipping leading punctuation. */
private fun firstVisibleInitial(word: String): String? {
    var index = 0
    while (index < word.length) {
        val codePoint = word.codePointAt(index)
        val grapheme = firstGrapheme(word.substring(index)) ?: return null
        if (Character.isLetterOrDigit(codePoint) || isEmojiCodePoint(codePoint)) {
            return uppercaseInitial(grapheme)
        }
        index += Character.charCount(codePoint)
    }
    return null
}

/**
 * The first user-perceived character of [text], approximated without ICU:
 * surrogate pairs, combining marks, variation selectors, skin tones, key caps,
 * flag pairs and ZWJ sequences are kept together so an emoji stays one glyph.
 */
private fun firstGrapheme(text: String): String? {
    if (text.isEmpty()) return null
    val first = text.codePointAt(0)
    var end = Character.charCount(first)

    // Two regional indicators form one flag.
    if (first in 0x1F1E6..0x1F1FF && end < text.length) {
        val next = text.codePointAt(end)
        if (next in 0x1F1E6..0x1F1FF) end += Character.charCount(next)
        return text.substring(0, end)
    }

    while (end < text.length) {
        val codePoint = text.codePointAt(end)
        when {
            isGraphemeExtender(codePoint) -> end += Character.charCount(codePoint)
            // ZWJ joins the next code point into the same glyph (👨‍👩‍👧).
            codePoint == ZERO_WIDTH_JOINER -> {
                end += 1
                if (end < text.length) end += Character.charCount(text.codePointAt(end))
            }
            else -> break
        }
    }
    return text.substring(0, end)
}

/** Marks that extend the preceding glyph: accents, selectors, skin tones, key caps. */
private fun isGraphemeExtender(codePoint: Int): Boolean {
    if (codePoint in 0xFE00..0xFE0F) return true // variation selectors
    if (codePoint in 0x1F3FB..0x1F3FF) return true // skin tone modifiers
    if (codePoint == 0x20E3) return true // combining enclosing key cap
    return when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        -> true
        else -> false
    }
}

private fun isEmojiCodePoint(codePoint: Int): Boolean =
    codePoint in 0x2600..0x27BF || codePoint in 0x1F000..0x1FAFF

/** Uppercases a cased initial; "ß" becomes "S" rather than a two-glyph "SS". */
private fun uppercaseInitial(grapheme: String): String {
    if (!Character.isLetter(grapheme.codePointAt(0))) return grapheme
    return firstGrapheme(grapheme.uppercase()) ?: grapheme
}

// --- Name field helpers -------------------------------------------------------

/** Unicode code points in [raw]; emoji count as one, like the payload limit does. */
fun profileNameCodePointCount(raw: String): Int = raw.codePointCount(0, raw.length)

/** Live "N/24" counter next to the name field. */
fun profileNameCounter(raw: String, max: Int = PROFILE_NAME_MAX_CODE_POINTS): String =
    "${profileNameCodePointCount(raw)}/$max"

/** Clamps typed/pasted text to [max] code points without splitting a surrogate pair. */
fun capProfileNameInput(raw: String, max: Int = PROFILE_NAME_MAX_CODE_POINTS): String {
    if (profileNameCodePointCount(raw) <= max) return raw
    return raw.substring(0, raw.offsetByCodePoints(0, max))
}

/** What the mesh will actually see: the node's own sanitizer, or "" when unusable. */
fun previewProfileName(raw: String): String = NameSanitizer.sanitize(raw) ?: ""

/**
 * False when [name] cannot travel in one PROFILE payload: empty, above 24 code
 * points, or above 64 UTF-8 bytes (24 emoji exceed the byte cap even at 24
 * code points).
 */
fun fitsProfilePayload(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return false
    if (profileNameCodePointCount(trimmed) > PROFILE_NAME_MAX_CODE_POINTS) return false
    return trimmed.toByteArray(Charsets.UTF_8).size <= ProfilePayload.MAX_NAME_BYTES
}

/** Hint for a name that passes the code-point cap but not the byte cap. */
fun profileNameByteWarning(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    if (profileNameCodePointCount(trimmed) > PROFILE_NAME_MAX_CODE_POINTS) return null
    if (trimmed.toByteArray(Charsets.UTF_8).size <= ProfilePayload.MAX_NAME_BYTES) return null
    return "Too long for one mesh frame — use fewer emoji."
}
