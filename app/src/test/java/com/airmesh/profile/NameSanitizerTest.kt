package com.airmesh.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for [NameSanitizer]: trimming, collapsing, stripping and capping. */
class NameSanitizerTest {

    // U+1F680 (rocket): one code point, used to exercise the code-point cap.
    private val rocket = "🚀"

    @Test
    fun trimsAndCollapsesInnerWhitespace() {
        assertEquals("a b", NameSanitizer.sanitize("  a   b  "))
    }

    @Test
    fun removesControlCharactersAndNewlines() {
        assertEquals("ab", NameSanitizer.sanitize("a\nb"))
        assertEquals("abc", NameSanitizer.sanitize("a\u0001bc"))
    }

    @Test
    fun stripsBidiOverrideAndIsolateCharacters() {
        // U+202E (RLE) and U+2069 (PDI) are removed so a name cannot spoof direction.
        assertEquals("ab", NameSanitizer.sanitize("a‮b"))
        assertEquals("cd", NameSanitizer.sanitize("c⁩d"))
    }

    @Test
    fun capsToTwentyFourCodePoints() {
        val name = rocket.repeat(30) // 30 code points
        val result = NameSanitizer.sanitize(name)!!
        assertEquals(24, result.codePointCount(0, result.length))
        assertEquals(rocket.repeat(24), result)
    }

    @Test
    fun allWhitespaceGivesNull() {
        assertNull(NameSanitizer.sanitize("   \n\t  "))
        assertNull(NameSanitizer.sanitize(""))
    }

    @Test
    fun bidiOnlyGivesNull() {
        // U+202A (LRM) and U+202E (RLE): nothing but bidi marks, so the name is empty.
        assertNull(NameSanitizer.sanitize("‪‮"))
    }
}
