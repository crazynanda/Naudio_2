package com.maxrave.domain.data.entities

import androidx.room.Entity

@Entity(
    tableName = "favorite_sync_tombstone",
    primaryKeys = ["type", "id"],
)
data class FavoriteSyncTombstoneEntity(
    val type: String,
    val id: String,
    val isFavorite: Boolean,
    val updatedAt: Long,
)

object FavoriteSyncType {
    const val SONG = "SONG"
    const val ALBUM = "ALBUM"
    const val PLAYLIST = "PLAYLIST"
    const val PODCAST = "PODCAST"
    const val ARTIST = "ARTIST"
}
