package com.airmesh.node

import android.content.Context

/**
 * [NodeIdStore] backed by SharedPreferences so the node keeps the same id
 * across app restarts.
 */
class SharedPreferencesNodeIdStore(context: Context) : NodeIdStore {

    private companion object {
        const val PREFS_NAME = "airmesh_node"
        const val KEY_NODE_ID = "node_id"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): Int? {
        val stored = prefs.getInt(KEY_NODE_ID, 0)
        return if (stored in NodeIdProvider.MIN_ID..NodeIdProvider.MAX_ID) stored else null
    }

    override fun write(id: Int) {
        // commit() (not apply) so the id is on disk before the node starts using it.
        prefs.edit().putInt(KEY_NODE_ID, id).commit()
    }
}
