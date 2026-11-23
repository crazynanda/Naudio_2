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
    /** Verified like-pushes this sync carried out. */
    val pushesConfirmed: Int = 0,
    /** Like-pushes that did not verify, kept in the pending queue for a retry. */
    val pushesFailed: Int = 0,
    /** Whether the remote snapshot this sync reconciled against reached the end of its pagination. */
    val snapshotComplete: Boolean = true,
    /**
     * True when the remote snapshot was not trusted for removals: no favorite was taken away
     * this cycle, and the local library was left untouched.
     */
    val removalsDeferred: Boolean = false,
) {
    val totalAddedOrUpdated: Int
        get() = songsAddedOrUpdated + albumsAddedOrUpdated + playlistsAddedOrUpdated + podcastsAddedOrUpdated + artistsAddedOrUpdated

    val totalRemoved: Int
        get() = songsRemoved + albumsRemoved + playlistsRemoved + podcastsRemoved + artistsRemoved

    val isEmpty: Boolean
        get() =
            totalAddedOrUpdated == 0 && totalRemoved == 0 &&
                pushesConfirmed == 0 && pushesFailed == 0
}
