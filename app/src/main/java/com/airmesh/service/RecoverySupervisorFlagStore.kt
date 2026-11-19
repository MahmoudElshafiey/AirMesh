package com.airmesh.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for the "recovery supervisor (experimental)" flag (TASK S1 part 1). An interface
 * so tests can fake it; the production implementation is a small SharedPreferences file. The
 * flag defaults to OFF: on real phones the level-2 group removal dissolved a fresh, still-empty
 * owner group before the other phone had even tried to join, so the supervisor stays opt-in
 * until its guards have been exercised in the field.
 */
interface RecoverySupervisorFlagStore {
    fun load(): Boolean
    fun save(enabled: Boolean)

    companion object {
        /** The real service starts with the supervisor off (TASK S1 part 1). */
        const val DEFAULT_ENABLED = false
    }
}

class SharedPreferencesRecoverySupervisorFlagStore(context: Context) : RecoverySupervisorFlagStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Boolean = prefs.getBoolean(KEY_RECOVERY_SUPERVISOR_ENABLED, RecoverySupervisorFlagStore.DEFAULT_ENABLED)

    override fun save(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RECOVERY_SUPERVISOR_ENABLED, enabled).apply()
    }

    private companion object {
        // Same file as the other service-level switches (auto-mesh, merge groups).
        const val PREFS_NAME = "airmesh_service"
        const val KEY_RECOVERY_SUPERVISOR_ENABLED = "recovery_supervisor_enabled"
    }
}
