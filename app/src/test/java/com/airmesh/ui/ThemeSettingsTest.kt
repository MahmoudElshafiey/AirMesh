package com.airmesh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the theme-mode decision (resolveDarkTheme) and the restoration of
 * a stored value (parseThemeMode). Kept in its own file so the existing
 * MeshUiHelpersTest stays untouched.
 */
class ThemeSettingsTest {

    // --- resolveDarkTheme: all 6 mode/system combinations -------------------

    @Test
    fun `system mode follows the device setting`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.SYSTEM, systemIsDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.SYSTEM, systemIsDark = false))
    }

    @Test
    fun `light mode is always light`() {
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemIsDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemIsDark = false))
    }

    @Test
    fun `dark mode is always dark`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemIsDark = true))
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemIsDark = false))
    }

    // --- parseThemeMode -------------------------------------------------------

    @Test
    fun `parse restores each stored name`() {
        assertEquals(ThemeMode.SYSTEM, parseThemeMode("SYSTEM"))
        assertEquals(ThemeMode.LIGHT, parseThemeMode("LIGHT"))
        assertEquals(ThemeMode.DARK, parseThemeMode("DARK"))
    }

    @Test
    fun `missing or unknown stored value falls back to system mode`() {
        assertEquals(ThemeMode.SYSTEM, parseThemeMode(null))
        assertEquals(ThemeMode.SYSTEM, parseThemeMode(""))
        assertEquals(ThemeMode.SYSTEM, parseThemeMode("bogus"))
        // Names are matched exactly (they are written by us), so a lowercase
        // value from an older/foreign writer must not be guessed at.
        assertEquals(ThemeMode.SYSTEM, parseThemeMode("dark"))
    }
}
