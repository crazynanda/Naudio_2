package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maxrave.domain.data.model.browse.album.Track

@Entity(tableName = "recent_song")
data class RecentSongEntity(
    @PrimaryKey
    val videoId: String,
    val track: Track,
    val selectedAt: Long,
)
