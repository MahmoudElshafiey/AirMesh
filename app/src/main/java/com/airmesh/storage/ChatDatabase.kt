package com.airmesh.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Chat history database (TASK D2 part 2). Deliberately separate from
 * [AirMeshDatabase]: chat rows are user data with their own lifecycle, while the
 * packet store is protocol state that may be flushed independently.
 */
@Database(entities = [ChatMessageEntity::class, ContactEntity::class], version = 1, exportSchema = false)
@TypeConverters(ChatStatusConverter::class)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao

    abstract fun contactDao(): ContactDao

    companion object {
        /** Builds the on-device chat database (separate file from airmesh.db). */
        fun build(context: Context): ChatDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                "airmesh_chat.db",
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
