package com.maxrave.domain.data.entities

import androidx.room.Entity

@Entity(
    tableName = "youtube_favorite_pending",
    primaryKeys = ["accountId", "videoId"],
)
data class YouTubeFavoritePendingEntity(
    val accountId: String,
    val videoId: String,
    val targetState: Int, // 1 for Liked, 0 for Unliked
    val createdAt: Long,
    val updatedAt: Long,
    val retryCount: Int = 0,
    val lastAttemptAt: Long = 0L,
    val lastError: String? = null,
)
