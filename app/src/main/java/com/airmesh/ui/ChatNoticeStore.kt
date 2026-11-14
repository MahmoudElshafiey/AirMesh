package com.airmesh.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers that the one-time "messages are not encrypted" note in a conversation was
 * dismissed, so it never appears again on its own (TASK U2 part 1). Same SharedPreferences
 * file as the theme and onboarding settings; an interface so the activity's wiring stays
 * replaceable.
 */
interface ChatNoticeStore {
    fun isEncryptionNoteDismissed(): Boolean
    fun markEncryptionNoteDismissed()
}

class SharedPreferencesChatNoticeStore(context: Context) : ChatNoticeStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isEncryptionNoteDismissed(): Boolean =
        prefs.getBoolean(KEY_ENCRYPTION_NOTE_DISMISSED, false)

    override fun markEncryptionNoteDismissed() {
        prefs.edit().putBoolean(KEY_ENCRYPTION_NOTE_DISMISSED, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "airmesh_settings"
        const val KEY_ENCRYPTION_NOTE_DISMISSED = "encryption_note_dismissed"
    }
}
