package com.airmesh.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for the "owner discoverability probe (experimental)" flag (TASK T1 part C3). An
 * interface so tests can fake it; the production implementation is a small SharedPreferences
 * file. The flag defaults to OFF: while an empty owner stays empty, the probe re-scans with
 * strategy 3 (discoverPeers then services) every 20 s instead of the normal cadence — an
 * experiment for evidence (1), where a restarted phone never saw the owner's DNS-SD advert.
 */
interface OwnerProbeFlagStore {
    fun load(): Boolean
    fun save(enabled: Boolean)

    companion object {
        /** The real service starts with the probe off (TASK T1 part C3). */
        const val DEFAULT_ENABLED = false
    }
}

class SharedPreferencesOwnerProbeFlagStore(context: Context) : OwnerProbeFlagStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Boolean = prefs.getBoolean(KEY_OWNER_PROBE_ENABLED, OwnerProbeFlagStore.DEFAULT_ENABLED)

    override fun save(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OWNER_PROBE_ENABLED, enabled).apply()
    }

    private companion object {
        // Same file as the other service-level switches (auto-mesh, merge groups, supervisor).
        const val PREFS_NAME = "airmesh_service"
        const val KEY_OWNER_PROBE_ENABLED = "owner_probe_enabled"
    }
}
