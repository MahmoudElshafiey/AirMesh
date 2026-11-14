package com.airmesh.storage

import androidx.room.TypeConverter
import com.airmesh.chat.ChatStatus

/**
 * Persists [ChatStatus] as its ordinal so SQL can compare delivery progress
 * (QUEUED < SENT < DELIVERED); name strings would sort alphabetically instead.
 */
class ChatStatusConverter {
    @TypeConverter fun fromStatus(status: ChatStatus): Int = status.ordinal

    @TypeConverter fun toStatus(ordinal: Int): ChatStatus = ChatStatus.values()[ordinal]
}
