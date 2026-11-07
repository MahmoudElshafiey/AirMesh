package com.airmesh.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for the theme setting. An interface so tests can fake it; the
 * production implementation is a small SharedPreferences file.
 */
interface ThemeModeStore {
    fun load(): ThemeMode
    fun save(mode: ThemeMode)
}

class SharedPreferencesThemeModeStore(context: Context) : ThemeModeStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ThemeMode = parseThemeMode(prefs.getString(KEY_THEME_MODE, null))

    override fun save(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    private companion object {
        const val PREFS_NAME = "airmesh_settings"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
