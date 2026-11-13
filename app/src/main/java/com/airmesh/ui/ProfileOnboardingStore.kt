package com.airmesh.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers that the first-run "Choose your name" dialog was answered (saved or
 * skipped), so it never appears again on its own. An interface so the activity's
 * wiring stays replaceable; production uses the same SharedPreferences file as
 * the theme setting.
 */
interface ProfileOnboardingStore {
    fun isDone(): Boolean
    fun markDone()
}

class SharedPreferencesProfileOnboardingStore(context: Context) : ProfileOnboardingStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)

    override fun markDone() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "airmesh_settings"
        const val KEY_ONBOARDING_DONE = "profile_onboarding_done"
    }
}
