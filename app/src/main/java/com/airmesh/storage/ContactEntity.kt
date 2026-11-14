package com.airmesh.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One known peer (TASK D2 part 2); name/color are filled in by PROFILE frames. */
@Entity(
    tableName = "chat_contacts",
    indices = [Index(value = ["nodeId"], unique = true)],
)
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val nodeId: Int,
    val displayName: String?,
    val colorIndex: Int,
)
