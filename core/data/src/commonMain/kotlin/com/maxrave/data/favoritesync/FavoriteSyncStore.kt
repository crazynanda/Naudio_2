package com.maxrave.data.favoritesync

import DatabaseDao
import com.maxrave.domain.data.entities.FavoriteSyncTombstoneEntity
import com.maxrave.domain.data.model.favoritesync.FavoritesSyncResult
import com.maxrave.domain.data.model.loginsync.LoginSyncException
import kotlin.time.Clock

class OversizedEntitySyncException(
    val entityType: String,
    val entityId: String,
    val size: Int,
    val maxBytes: Int,
    message: String = "Entity '$entityId' of type '$entityType' ($size bytes) exceeds the maximum synchronization size of $maxBytes bytes",
) : LoginSyncException(Reason.FAILED, message)

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
        if (bytes <= maxBytes) {
            outList.add(bundle)
            return
        }

        val totalRecords =
            bundle.songs.size + bundle.albums.size + bundle.playlists.size +
                bundle.podcasts.size + bundle.artists.size + bundle.tombstones.size

        if (totalRecords > 1) {
            // Guaranteed progress split: partition totalRecords into two strictly smaller non-empty parts
            var remainingToTake = totalRecords / 2

            val takeSongs = minOf(remainingToTake, bundle.songs.size)
            remainingToTake -= takeSongs

            val takeAlbums = minOf(remainingToTake, bundle.albums.size)
            remainingToTake -= takeAlbums

            val takePlaylists = minOf(remainingToTake, bundle.playlists.size)
            remainingToTake -= takePlaylists

            val takePodcasts = minOf(remainingToTake, bundle.podcasts.size)
            remainingToTake -= takePodcasts

            val takeArtists = minOf(remainingToTake, bundle.artists.size)
            remainingToTake -= takeArtists

            val takeTombstones = minOf(remainingToTake, bundle.tombstones.size)
            remainingToTake -= takeTombstones

            val b1 =
                bundle.copy(
                    songs = bundle.songs.take(takeSongs),
                    albums = bundle.albums.take(takeAlbums),
                    playlists = bundle.playlists.take(takePlaylists),
                    podcasts = bundle.podcasts.take(takePodcasts),
                    artists = bundle.artists.take(takeArtists),
                    tombstones = bundle.tombstones.take(takeTombstones),
                )
            val b2 =
                bundle.copy(
                    songs = bundle.songs.drop(takeSongs),
                    albums = bundle.albums.drop(takeAlbums),
                    playlists = bundle.playlists.drop(takePlaylists),
                    podcasts = bundle.podcasts.drop(takePodcasts),
                    artists = bundle.artists.drop(takeArtists),
                    tombstones = bundle.tombstones.drop(takeTombstones),
                )

            splitIfOversized(b1, maxBytes, outList)
            splitIfOversized(b2, maxBytes, outList)
            return
        }

        // totalRecords == 1 and bytes > maxBytes: individually oversized entity
        val fragmented = tryFragmentSingleEntity(bundle)
        if (fragmented != null) {
            val (f1, f2) = fragmented
            splitIfOversized(f1, maxBytes, outList)
            splitIfOversized(f2, maxBytes, outList)
            return
        }

        val (entityType, entityId) = getSingleEntityInfo(bundle)
        throw OversizedEntitySyncException(
            entityType = entityType,
            entityId = entityId,
            size = bytes,
            maxBytes = maxBytes,
        )
    }

    private fun tryFragmentSingleEntity(bundle: FavoritesSyncBundle): Pair<FavoritesSyncBundle, FavoritesSyncBundle>? {
        if (bundle.playlists.isNotEmpty()) {
            val p = bundle.playlists.first()
            val tracks = p.tracks
            if (tracks != null && tracks.size > 1) {
                val half = tracks.size / 2
                val p1 = p.copy(tracks = tracks.take(half))
                val p2 = p.copy(tracks = tracks.drop(half))
                return Pair(bundle.copy(playlists = listOf(p1)), bundle.copy(playlists = listOf(p2)))
            }
        }
        if (bundle.albums.isNotEmpty()) {
            val a = bundle.albums.first()
            val tracks = a.tracks
            if (tracks != null && tracks.size > 1) {
                val half = tracks.size / 2
                val a1 = a.copy(tracks = tracks.take(half))
                val a2 = a.copy(tracks = tracks.drop(half))
                return Pair(bundle.copy(albums = listOf(a1)), bundle.copy(albums = listOf(a2)))
            }
        }
        if (bundle.podcasts.isNotEmpty()) {
            val pod = bundle.podcasts.first()
            val episodes = pod.listEpisodes
            if (episodes.size > 1) {
                val half = episodes.size / 2
                val pod1 = pod.copy(listEpisodes = episodes.take(half))
                val pod2 = pod.copy(listEpisodes = episodes.drop(half))
                return Pair(bundle.copy(podcasts = listOf(pod1)), bundle.copy(podcasts = listOf(pod2)))
            }
        }
        return null
    }

    private fun getSingleEntityInfo(bundle: FavoritesSyncBundle): Pair<String, String> {
        bundle.songs.firstOrNull()?.let { return Pair("SONG", it.videoId) }
        bundle.albums.firstOrNull()?.let { return Pair("ALBUM", it.browseId) }
        bundle.playlists.firstOrNull()?.let { return Pair("PLAYLIST", it.id) }
        bundle.podcasts.firstOrNull()?.let { return Pair("PODCAST", it.podcastId) }
        bundle.artists.firstOrNull()?.let { return Pair("ARTIST", it.channelId) }
        bundle.tombstones.firstOrNull()?.let { return Pair("TOMBSTONE", "${it.type}:${it.id}") }
        return Pair("UNKNOWN", "unknown")
    }

    suspend fun mergeBundle(incoming: FavoritesSyncBundle): FavoritesSyncResult =
        databaseDao.mergeFavoritesBundle(incoming)
}
