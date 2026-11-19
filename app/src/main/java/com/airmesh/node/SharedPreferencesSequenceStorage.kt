package com.airmesh.node

import android.content.Context
import com.airmesh.routing.SequenceStorage

/**
 * [SequenceStorage] backed by SharedPreferences so the origin-sequence high-water mark
 * survives app restarts (TASK T1 part B2). Same prefs file as the node id. The value is a
 * decimal string so corrupt content stays visible to the keeper's parser instead of being
 * silently coerced by an Int default.
 */
class SharedPreferencesSequenceStorage(context: Context) : SequenceStorage {

    private companion object {
        const val PREFS_NAME = "airmesh_node"
        const val KEY_SEQ_MARK = "seq_high_water_mark"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readRaw(): String? = prefs.getString(KEY_SEQ_MARK, null)

    override fun write(value: Int) {
        // commit() (not apply): the mark must be on disk before a crash can lose it.
        prefs.edit().putString(KEY_SEQ_MARK, value.toString()).commit()
    }
}
