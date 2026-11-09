package com.maxrave.data.favoritesync

import DatabaseDao
import com.maxrave.domain.data.entities.FavoriteSyncTombstoneEntity
import com.maxrave.domain.data.entities.FavoriteSyncType
import com.maxrave.domain.data.model.favoritesync.FavoritesSyncResult
import kotlin.time.Clock

internal class FavoriteSyncStore(
    private val databaseDao: DatabaseDao,
) {
    suspend fun recordChange(
        type: String,
        id: String,
        isFavorite: Boolean,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    ) {
        databaseDao.insertFavoriteTombstone(
            FavoriteSyncTombstoneEntity(
                type = type,
                id = id,
                isFavorite = isFavorite,
                updatedAt = timestamp,
            ),
        )
    }

    suspend fun exportBundle(): FavoritesSyncBundle {
        val songs = databaseDao.getAllLikedSongs().map { it.toFavoriteDto() }
        val albums = databaseDao.getAllLikedAlbums().map { it.toFavoriteDto() }
        val playlists = databaseDao.getAllLikedPlaylists().map { it.toFavoriteDto() }
        val podcasts = databaseDao.getAllFavoritePodcasts().map { it.toFavoriteDto() }
        val artists = databaseDao.getAllFollowedArtists().map { it.toFavoriteDto() }
        val tombstones =
            databaseDao.getAllFavoriteTombstones().map {
                FavoriteTombstoneDto(
                    type = it.type,
                    id = it.id,
                    isFavorite = it.isFavorite,
                    updatedAtEpochMs = it.updatedAt,
                )
            }
        return FavoritesSyncBundle(
            songs = songs,
            albums = albums,
            playlists = playlists,
            podcasts = podcasts,
            artists = artists,
            tombstones = tombstones,
            clientTimestamp = Clock.System.now().toEpochMilliseconds(),
        )
    }

    suspend fun exportBundles(chunkSize: Int = 500): List<FavoritesSyncBundle> {
        val full = exportBundle()
        val allItemsCount = full.songs.size + full.albums.size + full.playlists.size + full.podcasts.size + full.artists.size + full.tombstones.size
        if (allItemsCount <= chunkSize) {
            return listOf(full)
        }

        // Chunking large collections into smaller batches
        val bundles = mutableListOf<FavoritesSyncBundle>()
        val songChunks = full.songs.chunked(chunkSize)
        val albumChunks = full.albums.chunked(chunkSize)
        val playlistChunks = full.playlists.chunked(chunkSize)
        val podcastChunks = full.podcasts.chunked(chunkSize)
        val artistChunks = full.artists.chunked(chunkSize)
        val tombstoneChunks = full.tombstones.chunked(chunkSize)

        val maxChunks = maxOf(
            songChunks.size,
            albumChunks.size,
            playlistChunks.size,
            podcastChunks.size,
            artistChunks.size,
            tombstoneChunks.size,
            1,
        )

        for (i in 0 until maxChunks) {
            bundles += FavoritesSyncBundle(
                songs = songChunks.getOrElse(i) { emptyList() },
                albums = albumChunks.getOrElse(i) { emptyList() },
                playlists = playlistChunks.getOrElse(i) { emptyList() },
                podcasts = podcastChunks.getOrElse(i) { emptyList() },
                artists = artistChunks.getOrElse(i) { emptyList() },
                tombstones = tombstoneChunks.getOrElse(i) { emptyList() },
                clientTimestamp = full.clientTimestamp,
            )
        }
        return bundles
    }

    suspend fun mergeBundle(incoming: FavoritesSyncBundle): FavoritesSyncResult {
        val localTombstones = databaseDao.getAllFavoriteTombstones().associateBy { "${it.type}:${it.id}" }

        var songsAddedOrUpdated = 0
        var songsRemoved = 0
        var albumsAddedOrUpdated = 0
        var albumsRemoved = 0
        var playlistsAddedOrUpdated = 0
        var playlistsRemoved = 0
        var podcastsAddedOrUpdated = 0
        var podcastsRemoved = 0
        var artistsAddedOrUpdated = 0
        var artistsRemoved = 0

        // 1. Process Songs
        for (songDto in incoming.songs) {
            val key = "${FavoriteSyncType.SONG}:${songDto.videoId}"
            val tombstone = localTombstones[key]
            if (tombstone != null && !tombstone.isFavorite && tombstone.updatedAt >= songDto.favoriteAtEpochMs) {
                // Local deletion is newer than or equal to incoming favorite
                continue
            }
            val existing = databaseDao.getSong(songDto.videoId)
            if (existing == null) {
                databaseDao.insertSong(songDto.toEntity())
                databaseDao.updateLiked(liked = 1, videoId = songDto.videoId, favoriteAt = songDto.favoriteAtEpochMs.toLocalDateTimeSystem())
                databaseDao.insertFavoriteTombstone(
                    FavoriteSyncTombstoneEntity(
                        type = FavoriteSyncType.SONG,
                        id = songDto.videoId,
                        isFavorite = true,
                        updatedAt = songDto.favoriteAtEpochMs,
                    ),
                )
                songsAddedOrUpdated++
            } else {
                val localFavEpoch = existing.favoriteAt.toEpochMillis()
                if (!existing.liked || songDto.favoriteAtEpochMs > localFavEpoch) {
                    val newerEpoch = songDto.favoriteAtEpochMs
                    databaseDao.updateLiked(
                        liked = 1,
                        videoId = songDto.videoId,
                        favoriteAt = newerEpoch.toLocalDateTimeSystem(),
                    )
                    databaseDao.insertFavoriteTombstone(
                        FavoriteSyncTombstoneEntity(
                            type = FavoriteSyncType.SONG,
                            id = songDto.videoId,
                            isFavorite = true,
                            updatedAt = newerEpoch,
                        ),
                    )
                    songsAddedOrUpdated++
                }
            }
        }

        // Process Song Deletions
        for (tomb in incoming.tombstones.filter { it.type == FavoriteSyncType.SONG && !it.isFavorite }) {
            val key = "${FavoriteSyncType.SONG}:${tomb.id}"
            val localTomb = localTombstones[key]
            if (localTomb != null && localTomb.updatedAt > tomb.updatedAtEpochMs) {
                // Local state is newer than remote deletion
                continue
            }
            val existing = databaseDao.getSong(tomb.id)
            val localFavEpoch = existing?.favoriteAt?.toEpochMillis() ?: 0L
            if (localFavEpoch > tomb.updatedAtEpochMs) {
                continue
            }
            if (existing != null && existing.liked) {
                databaseDao.updateLiked(liked = 0, videoId = tomb.id, favoriteAt = null)
                songsRemoved++
            }
            databaseDao.insertFavoriteTombstone(
                FavoriteSyncTombstoneEntity(
                    type = FavoriteSyncType.SONG,
                    id = tomb.id,
                    isFavorite = false,
                    updatedAt = tomb.updatedAtEpochMs,
                ),
            )
        }

        // 2. Process Albums
        for (albumDto in incoming.albums) {
            val key = "${FavoriteSyncType.ALBUM}:${albumDto.browseId}"
            val tombstone = localTombstones[key]
            if (tombstone != null && !tombstone.isFavorite && tombstone.updatedAt >= albumDto.favoriteAtEpochMs) {
                continue
            }
            val existing = databaseDao.getAlbum(albumDto.browseId)
            if (existing == null) {
                databaseDao.insertAlbum(albumDto.toEntity())
                databaseDao.updateAlbumLiked(
                    liked = 1,
                    browseId = albumDto.browseId,
                    favoriteAt = albumDto.favoriteAtEpochMs.toLocalDateTimeSystem(),
                )
                databaseDao.insertFavoriteTombstone(
                    FavoriteSyncTombstoneEntity(
                        type = FavoriteSyncType.ALBUM,
                        id = albumDto.browseId,
                        isFavorite = true,
                        updatedAt = albumDto.favoriteAtEpochMs,
                    ),
                )
                albumsAddedOrUpdated++
            } else {
                val localFavEpoch = existing.favoriteAt.toEpochMillis()
                if (!existing.liked || albumDto.favoriteAtEpochMs > localFavEpoch) {
                    val newerEpoch = albumDto.favoriteAtEpochMs
                    databaseDao.updateAlbumLiked(
                        liked = 1,
                        browseId = albumDto.browseId,
                        favoriteAt = newerEpoch.toLocalDateTimeSystem(),
                    )
                    databaseDao.insertFavoriteTombstone(
                        FavoriteSyncTombstoneEntity(
                            type = FavoriteSyncType.ALBUM,
                            id = albumDto.browseId,
                            isFavorite = true,
                            updatedAt = newerEpoch,
                        ),
                    )
                    albumsAddedOrUpdated++
                }
            }
        }

        // Process Album Deletions
        for (tomb in incoming.tombstones.filter { it.type == FavoriteSyncType.ALBUM && !it.isFavorite }) {
            val key = "${FavoriteSyncType.ALBUM}:${tomb.id}"
            val localTomb = localTombstones[key]
            if (localTomb != null && localTomb.updatedAt > tomb.updatedAtEpochMs) {
                continue
            }
            val existing = databaseDao.getAlbum(tomb.id)
            val localFavEpoch = existing?.favoriteAt?.toEpochMillis() ?: 0L
            if (localFavEpoch > tomb.updatedAtEpochMs) {
                continue
            }
            if (existing != null && existing.liked) {
                databaseDao.updateAlbumLiked(liked = 0, browseId = tomb.id, favoriteAt = null)
                albumsRemoved++
            }
            databaseDao.insertFavoriteTombstone(
                FavoriteSyncTombstoneEntity(
                    type = FavoriteSyncType.ALBUM,
                    id = tomb.id,
                    isFavorite = false,
                    updatedAt = tomb.updatedAtEpochMs,
                ),
            )
        }

        // 3. Process Playlists
        for (playlistDto in incoming.playlists) {
            val key = "${FavoriteSyncType.PLAYLIST}:${playlistDto.id}"
            val tombstone = localTombstones[key]
            if (tombstone != null && !tombstone.isFavorite && tombstone.updatedAt >= playlistDto.favoriteAtEpochMs) {
                continue
            }
            val existing = databaseDao.getPlaylist(playlistDto.id)
            if (existing == null) {
                databaseDao.insertPlaylist(playlistDto.toEntity())
                databaseDao.updatePlaylistLiked(
                    liked = 1,
                    playlistId = playlistDto.id,
                    favoriteAt = playlistDto.favoriteAtEpochMs.toLocalDateTimeSystem(),
                )
                databaseDao.insertFavoriteTombstone(
                    FavoriteSyncTombstoneEntity(
                        type = FavoriteSyncType.PLAYLIST,
                        id = playlistDto.id,
                        isFavorite = true,
                        updatedAt = playlistDto.favoriteAtEpochMs,
                    ),
                )
                playlistsAddedOrUpdated++
            } else {
                val localFavEpoch = existing.favoriteAt.toEpochMillis()
                if (!existing.liked || playlistDto.favoriteAtEpochMs > localFavEpoch) {
                    val newerEpoch = playlistDto.favoriteAtEpochMs
                    databaseDao.updatePlaylistLiked(
                        liked = 1,
                        playlistId = playlistDto.id,
                        favoriteAt = newerEpoch.toLocalDateTimeSystem(),
                    )
                    databaseDao.insertFavoriteTombstone(
                        FavoriteSyncTombstoneEntity(
                            type = FavoriteSyncType.PLAYLIST,
                            id = playlistDto.id,
                            isFavorite = true,
                            updatedAt = newerEpoch,
                        ),
                    )
                    playlistsAddedOrUpdated++
                }
            }
        }

        // Process Playlist Deletions
        for (tomb in incoming.tombstones.filter { it.type == FavoriteSyncType.PLAYLIST && !it.isFavorite }) {
            val key = "${FavoriteSyncType.PLAYLIST}:${tomb.id}"
            val localTomb = localTombstones[key]
            if (localTomb != null && localTomb.updatedAt > tomb.updatedAtEpochMs) {
                continue
            }
            val existing = databaseDao.getPlaylist(tomb.id)
            val localFavEpoch = existing?.favoriteAt?.toEpochMillis() ?: 0L
            if (localFavEpoch > tomb.updatedAtEpochMs) {
                continue
            }
            if (existing != null && existing.liked) {
                databaseDao.updatePlaylistLiked(liked = 0, playlistId = tomb.id, favoriteAt = null)
                playlistsRemoved++
            }
            databaseDao.insertFavoriteTombstone(
                FavoriteSyncTombstoneEntity(
                    type = FavoriteSyncType.PLAYLIST,
                    id = tomb.id,
                    isFavorite = false,
                    updatedAt = tomb.updatedAtEpochMs,
                ),
            )
        }

        // 4. Process Podcasts
        for (podcastDto in incoming.podcasts) {
            val key = "${FavoriteSyncType.PODCAST}:${podcastDto.podcastId}"
            val tombstone = localTombstones[key]
            if (tombstone != null && !tombstone.isFavorite && tombstone.updatedAt >= podcastDto.favoriteTimeEpochMs) {
                continue
            }
            val existing = databaseDao.getPodcast(podcastDto.podcastId)
            if (existing == null) {
                databaseDao.insertPodcast(podcastDto.toEntity())
                databaseDao.updatePodcastFavorite(
                    isFavorite = true,
                    podcastId = podcastDto.podcastId,
                    favoriteTime = podcastDto.favoriteTimeEpochMs.toLocalDateTimeSystem(),
                )
                databaseDao.insertFavoriteTombstone(
                    FavoriteSyncTombstoneEntity(
                        type = FavoriteSyncType.PODCAST,
                        id = podcastDto.podcastId,
                        isFavorite = true,
                        updatedAt = podcastDto.favoriteTimeEpochMs,
                    ),
                )
                podcastsAddedOrUpdated++
            } else {
                val localFavEpoch = existing.favoriteTime.toEpochMillis()
                if (!existing.isFavorite || podcastDto.favoriteTimeEpochMs > localFavEpoch) {
                    val newerEpoch = podcastDto.favoriteTimeEpochMs
                    databaseDao.updatePodcastFavorite(
                        isFavorite = true,
                        podcastId = podcastDto.podcastId,
                        favoriteTime = newerEpoch.toLocalDateTimeSystem(),
                    )
                    databaseDao.insertFavoriteTombstone(
                        FavoriteSyncTombstoneEntity(
                            type = FavoriteSyncType.PODCAST,
                            id = podcastDto.podcastId,
                            isFavorite = true,
                            updatedAt = newerEpoch,
                        ),
                    )
                    podcastsAddedOrUpdated++
                }
            }
        }

        // Process Podcast Deletions
        for (tomb in incoming.tombstones.filter { it.type == FavoriteSyncType.PODCAST && !it.isFavorite }) {
            val key = "${FavoriteSyncType.PODCAST}:${tomb.id}"
            val localTomb = localTombstones[key]
            if (localTomb != null && localTomb.updatedAt > tomb.updatedAtEpochMs) {
                continue
            }
            val existing = databaseDao.getPodcast(tomb.id)
            val localFavEpoch = existing?.favoriteTime?.toEpochMillis() ?: 0L
            if (localFavEpoch > tomb.updatedAtEpochMs) {
                continue
            }
            if (existing != null && existing.isFavorite) {
                databaseDao.updatePodcastFavorite(isFavorite = false, podcastId = tomb.id, favoriteTime = null)
                podcastsRemoved++
            }
            databaseDao.insertFavoriteTombstone(
                FavoriteSyncTombstoneEntity(
                    type = FavoriteSyncType.PODCAST,
                    id = tomb.id,
                    isFavorite = false,
                    updatedAt = tomb.updatedAtEpochMs,
                ),
            )
        }

        // 5. Process Artists
        for (artistDto in incoming.artists) {
            val key = "${FavoriteSyncType.ARTIST}:${artistDto.channelId}"
            val tombstone = localTombstones[key]
            if (tombstone != null && !tombstone.isFavorite && tombstone.updatedAt >= artistDto.followedAtEpochMs) {
                continue
            }
            val existing = databaseDao.getArtist(artistDto.channelId)
            if (existing == null) {
                databaseDao.insertArtist(artistDto.toEntity())
                databaseDao.updateFollowed(
                    followed = 1,
                    channelId = artistDto.channelId,
                    followedAt = artistDto.followedAtEpochMs.toLocalDateTimeSystem(),
                )
                databaseDao.insertFavoriteTombstone(
                    FavoriteSyncTombstoneEntity(
                        type = FavoriteSyncType.ARTIST,
                        id = artistDto.channelId,
                        isFavorite = true,
                        updatedAt = artistDto.followedAtEpochMs,
                    ),
                )
                artistsAddedOrUpdated++
            } else {
                val localFavEpoch = existing.followedAt.toEpochMillis()
                if (!existing.followed || artistDto.followedAtEpochMs > localFavEpoch) {
                    val newerEpoch = artistDto.followedAtEpochMs
                    databaseDao.updateFollowed(
                        followed = 1,
                        channelId = artistDto.channelId,
                        followedAt = newerEpoch.toLocalDateTimeSystem(),
                    )
                    databaseDao.insertFavoriteTombstone(
                        FavoriteSyncTombstoneEntity(
                            type = FavoriteSyncType.ARTIST,
                            id = artistDto.channelId,
                            isFavorite = true,
                            updatedAt = newerEpoch,
                        ),
                    )
                    artistsAddedOrUpdated++
                }
            }
        }

        // Process Artist Deletions
        for (tomb in incoming.tombstones.filter { it.type == FavoriteSyncType.ARTIST && !it.isFavorite }) {
            val key = "${FavoriteSyncType.ARTIST}:${tomb.id}"
            val localTomb = localTombstones[key]
            if (localTomb != null && localTomb.updatedAt > tomb.updatedAtEpochMs) {
                continue
            }
            val existing = databaseDao.getArtist(tomb.id)
            val localFavEpoch = existing?.followedAt?.toEpochMillis() ?: 0L
            if (localFavEpoch > tomb.updatedAtEpochMs) {
                continue
            }
            if (existing != null && existing.followed) {
                databaseDao.updateFollowed(followed = 0, channelId = tomb.id, followedAt = null)
                artistsRemoved++
            }
            databaseDao.insertFavoriteTombstone(
                FavoriteSyncTombstoneEntity(
                    type = FavoriteSyncType.ARTIST,
                    id = tomb.id,
                    isFavorite = false,
                    updatedAt = tomb.updatedAtEpochMs,
                ),
            )
        }

        return FavoritesSyncResult(
            songsAddedOrUpdated = songsAddedOrUpdated,
            songsRemoved = songsRemoved,
            albumsAddedOrUpdated = albumsAddedOrUpdated,
            albumsRemoved = albumsRemoved,
            playlistsAddedOrUpdated = playlistsAddedOrUpdated,
            playlistsRemoved = playlistsRemoved,
            podcastsAddedOrUpdated = podcastsAddedOrUpdated,
            podcastsRemoved = podcastsRemoved,
            artistsAddedOrUpdated = artistsAddedOrUpdated,
            artistsRemoved = artistsRemoved,
        )
    }
}
