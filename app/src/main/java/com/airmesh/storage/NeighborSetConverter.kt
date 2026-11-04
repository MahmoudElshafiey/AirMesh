package com.airmesh.storage

import androidx.room.TypeConverter

/**
 * Stores a set of neighbor node ids as a comma-separated string.
 *
 * Node ids are validated 16-bit integers, so commas can never appear inside a
 * value and the encoding is unambiguous. An empty set is stored as an empty
 * string rather than a list with one blank element.
 */
class NeighborSetConverter {

    @TypeConverter
    fun fromNeighbors(neighbors: Set<String>): String = neighbors.joinToString(separator = ",")

    @TypeConverter
    fun toNeighbors(value: String): Set<String> =
        if (value.isEmpty()) emptySet() else value.split(',').toSet()
}
