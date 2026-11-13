package com.airmesh.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for the "merge separate groups" flag (TASK M1). An interface so
 * tests can fake it; the production implementation is a small SharedPreferences
 * file. The flag defaults to ON: merging is the whole point of the feature, and
 * it only ever makes an owner scan a little more often.
 */
interface MergeGroupsFlagStore {
    fun load(): Boolean
    fun save(enabled: Boolean)
}

class SharedPreferencesMergeGroupsFlagStore(context: Context) : MergeGroupsFlagStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Boolean = prefs.getBoolean(KEY_MERGE_GROUPS_ENABLED, true)

    override fun save(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MERGE_GROUPS_ENABLED, enabled).apply()
    }

    private companion object {
        // Same file as the other service-level switches (auto-mesh, notifications).
        const val PREFS_NAME = "airmesh_service"
        const val KEY_MERGE_GROUPS_ENABLED = "merge_groups_enabled"
    }
}
