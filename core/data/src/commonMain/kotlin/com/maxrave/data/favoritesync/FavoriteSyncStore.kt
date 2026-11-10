package com.maxrave.data.favoritesync

import DatabaseDao
import com.maxrave.domain.data.entities.FavoriteSyncTombstoneEntity
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
            batchIndex = 0,
            batchTotal = 1,
            hasMore = false,
        )
    }

    suspend fun exportBundles(
        chunkSize: Int = 500,
        maxBytes: Int = 3_500_000,
    ): List<FavoritesSyncBundle> {
        val now = Clock.System.now().toEpochMilliseconds()
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

        val totalCount =
            songs.size + albums.size + playlists.size + podcasts.size + artists.size + tombstones.size
        if (totalCount == 0) {
            return listOf(
                FavoritesSyncBundle(
                    clientTimestamp = now,
                    batchIndex = 0,
                    batchTotal = 1,
                    hasMore = false,
                ),
            )
        }

        val batches = mutableListOf<FavoritesSyncBundle>()

        var currentSongs = mutableListOf<FavoriteSongDto>()
        var currentAlbums = mutableListOf<FavoriteAlbumDto>()
        var currentPlaylists = mutableListOf<FavoritePlaylistDto>()
        var currentPodcasts = mutableListOf<FavoritePodcastDto>()
        var currentArtists = mutableListOf<FavoriteArtistDto>()
        var currentTombstones = mutableListOf<FavoriteTombstoneDto>()
        var currentEstimatedBytes = 0

        fun currentCount() =
            currentSongs.size + currentAlbums.size + currentPlaylists.size + currentPodcasts.size + currentArtists.size + currentTombstones.size

        fun flushCurrent() {
            if (currentCount() == 0) return
            batches.add(
                FavoritesSyncBundle(
                    songs = currentSongs,
                    albums = currentAlbums,
                    playlists = currentPlaylists,
                    podcasts = currentPodcasts,
                    artists = currentArtists,
                    tombstones = currentTombstones,
                    clientTimestamp = now,
                ),
            )
            currentSongs = mutableListOf()
            currentAlbums = mutableListOf()
            currentPlaylists = mutableListOf()
            currentPodcasts = mutableListOf()
            currentArtists = mutableListOf()
            currentTombstones = mutableListOf()
            currentEstimatedBytes = 0
        }

        fun addItem(
            itemEstimatedBytes: Int,
            addBlock: () -> Unit,
        ) {
            if (currentCount() >= chunkSize || (currentCount() > 0 && currentEstimatedBytes + itemEstimatedBytes > maxBytes)) {
                flushCurrent()
            }
            addBlock()
            currentEstimatedBytes += itemEstimatedBytes
        }

        for (s in songs) {
            val est = 300 + s.title.length * 2 + (s.thumbnails?.length ?: 0)
            addItem(est) { currentSongs.add(s) }
        }
        for (a in albums) {
            val est = 300 + a.title.length * 2 + (a.description?.length ?: 0) * 2 + (a.tracks?.sumOf { it.length } ?: 0)
            addItem(est) { currentAlbums.add(a) }
        }
        for (p in playlists) {
            val est = 300 + p.title.length * 2 + (p.description?.length ?: 0) * 2 + (p.tracks?.sumOf { it.length } ?: 0)
            addItem(est) { currentPlaylists.add(p) }
        }
        for (pod in podcasts) {
            val est = 300 + pod.title.length * 2 + (pod.description?.length ?: 0) * 2 + pod.listEpisodes.sumOf { it.length }
            addItem(est) { currentPodcasts.add(pod) }
        }
        for (art in artists) {
            val est = 250 + art.name.length * 2 + (art.thumbnails?.length ?: 0)
            addItem(est) { currentArtists.add(art) }
        }
        for (t in tombstones) {
            val est = 100 + t.id.length
            addItem(est) { currentTombstones.add(t) }
        }

        flushCurrent()

        // Double check actual serialized byte size of each batch against maxBytes;
        // If an individual batch exceeds maxBytes (e.g. huge metadata), split it in half until valid.
        val verifiedBatches = mutableListOf<FavoritesSyncBundle>()
        for (b in batches) {
            splitIfOversized(b, maxBytes, verifiedBatches)
        }

        val totalBatches = verifiedBatches.size
        return verifiedBatches.mapIndexed { idx, bundle ->
            bundle.copy(
                batchIndex = idx,
                batchTotal = totalBatches,
                hasMore = idx < totalBatches - 1,
            )
        }
    }

    private fun splitIfOversized(
        bundle: FavoritesSyncBundle,
        maxBytes: Int,
        outList: MutableList<FavoritesSyncBundle>,
    ) {
        val bytes = favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), bundle).encodeToByteArray().size
        val count =
            bundle.songs.size + bundle.albums.size + bundle.playlists.size + bundle.podcasts.size + bundle.artists.size + bundle.tombstones.size
        if (bytes <= maxBytes || count <= 1) {
            outList.add(bundle)
            return
        }

        val halfSongs = (bundle.songs.size + 1) / 2
        val halfAlbums = (bundle.albums.size + 1) / 2
        val halfPlaylists = (bundle.playlists.size + 1) / 2
        val halfPodcasts = (bundle.podcasts.size + 1) / 2
        val halfArtists = (bundle.artists.size + 1) / 2
        val halfTombstones = (bundle.tombstones.size + 1) / 2

        val b1 =
            bundle.copy(
                songs = bundle.songs.take(halfSongs),
                albums = bundle.albums.take(halfAlbums),
                playlists = bundle.playlists.take(halfPlaylists),
                podcasts = bundle.podcasts.take(halfPodcasts),
                artists = bundle.artists.take(halfArtists),
                tombstones = bundle.tombstones.take(halfTombstones),
            )
        val b2 =
            bundle.copy(
                songs = bundle.songs.drop(halfSongs),
                albums = bundle.albums.drop(halfAlbums),
                playlists = bundle.playlists.drop(halfPlaylists),
                podcasts = bundle.podcasts.drop(halfPodcasts),
                artists = bundle.artists.drop(halfArtists),
                tombstones = bundle.tombstones.drop(halfTombstones),
            )

        splitIfOversized(b1, maxBytes, outList)
        splitIfOversized(b2, maxBytes, outList)
    }

    suspend fun mergeBundle(incoming: FavoritesSyncBundle): FavoritesSyncResult =
        databaseDao.mergeFavoritesBundle(incoming)
}
