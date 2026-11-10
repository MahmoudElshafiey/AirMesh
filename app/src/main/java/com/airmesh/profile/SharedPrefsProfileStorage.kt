package com.airmesh.profile

import android.content.SharedPreferences

/**
 * [ProfileStorage] backed by SharedPreferences: the whole directory is one JSON
 * document under a single key. This is the only Android-dependent piece of the
 * profile feature; [ProfileDirectory] itself stays pure Kotlin.
 */
class SharedPrefsProfileStorage(private val prefs: SharedPreferences) : ProfileStorage {

    override fun load(): String? = prefs.getString(KEY, null)

    override fun save(text: String) {
        // commit() rather than apply(): writes are rare and small, and the
        // next process start must see them.
        prefs.edit().putString(KEY, text).commit()
    }

    private companion object {
        const val KEY = "profile_directory"
    }
}
