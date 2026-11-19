package com.airmesh.service

import android.content.Context
import android.content.SharedPreferences
import com.airmesh.transport.RememberedOwnerStorage

/**
 * TASK T1 part C1: SharedPreferences adapter for [com.airmesh.transport.RememberedOwnerStore].
 * Same "airmesh_node" prefs file as the sequence high-water mark; commit() so the record is on
 * disk before a crash can lose it.
 */
class SharedPreferencesRememberedOwnerStorage(context: Context) : RememberedOwnerStorage {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readRaw(): String? = prefs.getString(KEY_REMEMBERED_OWNER, null)

    override fun write(raw: String) {
        prefs.edit().putString(KEY_REMEMBERED_OWNER, raw).commit()
    }

    private companion object {
        const val PREFS_NAME = "airmesh_node"
        const val KEY_REMEMBERED_OWNER = "remembered_owner_record"
    }
}
