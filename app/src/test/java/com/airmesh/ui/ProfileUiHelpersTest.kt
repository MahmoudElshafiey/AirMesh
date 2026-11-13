package com.airmesh.ui

import com.airmesh.profile.ProfileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure profile helpers: display names, initials and palette. */
class ProfileUiHelpersTest {

    private fun profiles(vararg entries: Pair<Int, ProfileInfo>): Map<Int, ProfileInfo> =
        entries.toMap()

    private fun profile(name: String, colorIndex: Int = 0) = ProfileInfo(name, colorIndex, 0L)

    // --- Display name ---------------------------------------------------------

    @Test
    fun displayNameUsesTheKnownName() {
        assertEquals("Alice", displayName(7, profiles(7 to profile("Alice"))))
    }

    @Test
    fun displayNameFallsBackToTheNodeId() {
        assertEquals("User #7", displayName(7, emptyMap()))
    }

    @Test
    fun displayNameFallsBackWhenTheStoredNameIsBlank() {
        assertEquals("User #7", displayName(7, profiles(7 to profile("   "))))
    }

    // --- Avatar color ---------------------------------------------------------

    @Test
    fun avatarColorIndexPrefersThePickedColor() {
        assertEquals(3, avatarColorIndex(7, profiles(7 to profile("Alice", colorIndex = 3))))
    }

    @Test
    fun avatarColorIndexIsDeterministicForUnknownNodes() {
        assertEquals(11 % 8, avatarColorIndex(11, emptyMap()))
        assertEquals(7, avatarColorIndex(-1, emptyMap())) // floorMod keeps it non-negative
    }

    @Test
    fun paletteHasEightDistinctOpaqueEntries() {
        val colors = (0 until AVATAR_COLOR_COUNT).map(::avatarColor)
        assertEquals(8, AVATAR_COLOR_COUNT)
        assertEquals(8, colors.toSet().size)
        colors.forEach { assertEquals(0xFF, (it ushr 24).toInt()) }
    }

    @Test
    fun paletteKeepsWhiteInitialsReadableInBothThemes() {
        // White is the initial color; the fill must carry the contrast itself.
        for (index in 0 until AVATAR_COLOR_COUNT) {
            val ratio = contrastWithWhite(avatarColor(index))
            assertTrue("palette entry $index has contrast $ratio < 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun avatarColorClampsOutOfRangeIndices() {
        assertEquals(avatarColor(0), avatarColor(-5))
        assertEquals(avatarColor(AVATAR_COLOR_COUNT - 1), avatarColor(99))
    }

    // --- Initials -------------------------------------------------------------

    @Test
    fun initialsUseTheFirstTwoWords() {
        assertEquals("A", avatarInitials("Alice"))
        assertEquals("AS", avatarInitials("alice smith"))
        assertEquals("AM", avatarInitials("Abdelrahman Mohamed Adel")) // 24 code points
    }

    @Test
    fun initialsSkipTheHashOfAUserFallback() {
        assertEquals("U7", avatarInitials("User #7"))
    }

    @Test
    fun initialsKeepAnEmojiWhole() {
        assertEquals("🚑R", avatarInitials("🚑 Rescue"))
        // Skin tone and ZWJ family sequences stay one glyph each.
        assertEquals("👍🏽B", avatarInitials("👍🏽 Builder"))
        assertEquals("👨‍👩‍👧F", avatarInitials("👨‍👩‍👧 Family"))
        assertEquals("🇪🇬E", avatarInitials("🇪🇬 Egypt"))
    }

    @Test
    fun initialsUppercaseWithoutSplittingGlyphs() {
        assertEquals("S", avatarInitials("ß"))
    }

    @Test
    fun initialsHandleRightToLeftNames() {
        assertEquals("مع", avatarInitials("محمد علي"))
    }

    @Test
    fun initialsFallBackToAQuestionMark() {
        assertEquals("?", avatarInitials(""))
        assertEquals("?", avatarInitials("   "))
        assertEquals("?", avatarInitials("###"))
    }

    // --- Name field -----------------------------------------------------------

    @Test
    fun counterCountsCodePointsNotChars() {
        assertEquals("0/24", profileNameCounter(""))
        assertEquals("3/24", profileNameCounter("abc"))
        assertEquals("1/24", profileNameCounter("🚑")) // surrogate pair = one
    }

    @Test
    fun inputCapNeverSplitsASurrogatePair() {
        val emoji = "🚑".repeat(30)
        val capped = capProfileNameInput(emoji)
        assertEquals(24, capped.codePointCount(0, capped.length))
        assertEquals(48, capped.length)
        assertEquals(emoji.take(48), capped)
    }

    @Test
    fun inputCapLeavesShortTextUntouched() {
        assertEquals("Alice", capProfileNameInput("Alice"))
    }

    @Test
    fun previewUsesTheNodeSanitizer() {
        assertEquals("", previewProfileName("   "))
        // The node's sanitizer drops control characters instead of turning them into spaces.
        assertEquals("AB", previewProfileName("  A\nB  "))
        assertEquals("Alice", previewProfileName("Al\u202Eice")) // bidi override stripped
    }

    @Test
    fun payloadFitAllowsTwentyFourAsciiButNotTwentyFourEmoji() {
        assertTrue(fitsProfilePayload("A".repeat(24)))
        assertTrue(!fitsProfilePayload(""))
        assertTrue(!fitsProfilePayload("🚑".repeat(24))) // 96 UTF-8 bytes
        assertTrue(fitsProfilePayload("🚑".repeat(16))) // exactly 64 bytes
    }

    @Test
    fun byteWarningOnlyAppearsForOversizedEmojiNames() {
        assertNull(profileNameByteWarning("A".repeat(24)))
        assertNull(profileNameByteWarning(""))
        assertEquals(
            "Too long for one mesh frame — use fewer emoji.",
            profileNameByteWarning("🚑".repeat(24)),
        )
    }

    // --- Contrast (pure WCAG math, no Android) --------------------------------

    private fun contrastWithWhite(argb: Long): Double =
        1.05 / (relativeLuminance(argb) + 0.05)

    private fun relativeLuminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
