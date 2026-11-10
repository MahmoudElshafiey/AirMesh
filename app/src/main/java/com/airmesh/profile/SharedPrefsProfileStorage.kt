package com.airmesh.profile

import android.content.Context
import android.content.SharedPreferences

/**
 * [ProfileStorage] backed by SharedPreferences, mirroring
 * [com.airmesh.node.SharedPreferencesNodeIdStore]. This is the only
 * Android-dependent piece of the profile feature; [ProfileDirectory] and the
 * node's own-profile persistence stay pure Kotlin behind the interface.
 */
class SharedPrefsProfileStorage(context: Context) : ProfileStorage {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        // commit() rather than apply(): profile writes are rare and small, and
        // the next process start must see them.
        prefs.edit().putString(key, value).commit()
    }

    companion object {
        const val PREFS_NAME = "airmesh_profile"
    }
}
