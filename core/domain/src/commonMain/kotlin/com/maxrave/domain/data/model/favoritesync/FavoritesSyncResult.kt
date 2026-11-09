package com.maxrave.domain.data.model.favoritesync

import kotlinx.serialization.Serializable

@Serializable
data class FavoritesSyncResult(
    val songsAddedOrUpdated: Int = 0,
    val songsRemoved: Int = 0,
    val albumsAddedOrUpdated: Int = 0,
    val albumsRemoved: Int = 0,
    val playlistsAddedOrUpdated: Int = 0,
    val playlistsRemoved: Int = 0,
    val podcastsAddedOrUpdated: Int = 0,
    val podcastsRemoved: Int = 0,
    val artistsAddedOrUpdated: Int = 0,
    val artistsRemoved: Int = 0,
) {
    val totalAddedOrUpdated: Int
        get() = songsAddedOrUpdated + albumsAddedOrUpdated + playlistsAddedOrUpdated + podcastsAddedOrUpdated + artistsAddedOrUpdated

    val totalRemoved: Int
        get() = songsRemoved + albumsRemoved + playlistsRemoved + podcastsRemoved + artistsRemoved

    val isEmpty: Boolean
        get() = totalAddedOrUpdated == 0 && totalRemoved == 0
}
