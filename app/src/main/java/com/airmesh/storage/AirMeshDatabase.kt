package com.airmesh.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Application database. Only the store-and-forward queue exists so far.
 *
 * `exportSchema = false` keeps the build simple for the course prototype; add
 * migrations before changing the schema of a released app.
 */
@Database(entities = [StoredPacket::class], version = 1, exportSchema = false)
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
            ).build()
    }
}
