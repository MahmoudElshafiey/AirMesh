package com.airmesh.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Application database. Only the store-and-forward queue exists so far.
 *
 * Version 2 adds the auto-generated `id` column and the unique
 * `(nodeId, seq)` index. The app is not released yet, so the builder falls back
 * to a destructive migration instead of carrying a v1 -> v2 migration. Replace
 * that fallback with a real [androidx.room.migration.Migration] before shipping.
 *
 * `exportSchema = false` keeps the build simple for the course prototype.
 */
@Database(entities = [StoredPacket::class], version = 2, exportSchema = false)
@TypeConverters(NeighborSetConverter::class)
abstract class AirMeshDatabase : RoomDatabase() {

    abstract fun storedPacketDao(): StoredPacketDao

    companion object {
        /** Builds the on-device database. Call once and keep the instance. */
        fun build(context: Context): AirMeshDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AirMeshDatabase::class.java,
                "airmesh.db",
            )
                // Unreleased app: rebuilding the queue on a schema change is
                // acceptable. A shipped app must add a real migration instead.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
