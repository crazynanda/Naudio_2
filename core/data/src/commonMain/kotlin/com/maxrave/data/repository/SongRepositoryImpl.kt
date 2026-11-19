package com.maxrave.data.repository

import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.extension.getFullDataFromDB
import com.maxrave.data.favoritesync.toLocalDateTimeSystem
import com.maxrave.data.mapping.toListTrack
import com.maxrave.data.mapping.toTrack
import com.maxrave.data.mapping.toYouTubeComment
import com.maxrave.data.mapping.toYouTubeCommentsPage
import com.maxrave.data.mapping.toSongItemForDownload
import com.maxrave.data.mapping.toWatchEndpoint
import com.maxrave.domain.data.entities.FavoriteSyncTombstoneEntity
import com.maxrave.domain.data.entities.FavoriteSyncType
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.entities.QueueEntity
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.SongInfoEntity
import com.maxrave.domain.data.entities.YouTubeFavoritePendingEntity
import com.maxrave.domain.data.entities.analytics.query.SongPlaySample
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.comments.YouTubeComment
import com.maxrave.domain.data.model.comments.YouTubeCommentsPage
import com.maxrave.domain.data.model.download.DownloadProgress
import com.maxrave.domain.data.model.favoritesync.FavoritesSyncResult
import com.maxrave.domain.data.model.favoritesync.YouTubeSyncStatus
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.extension.now
import com.maxrave.domain.utils.toSongEntity
import com.maxrave.kotlinytmusicscraper.models.MusicShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.getContinuation
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.kotlinytmusicscraper.models.response.LikedSongsBrowseIds
import com.maxrave.kotlinytmusicscraper.pages.PlaylistPage
import kotlin.time.Clock
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.YouTubeLikeStatus
import com.maxrave.domain.utils.isLive
import com.maxrave.domain.utils.isRadioQueueId
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import com.maxrave.kotlinytmusicscraper.models.response.LikeStatus
import com.maxrave.kotlinytmusicscraper.pages.NextPage
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistContinuation
import com.maxrave.kotlinytmusicscraper.parser.getContinuePlaylistContinuation
import com.maxrave.data.parser.findSongColumns
import com.maxrave.data.parser.parseDurationSeconds
import com.maxrave.data.parser.parseSongAlbumAt
import com.maxrave.data.parser.parseSongArtistsAt
import com.maxrave.data.parser.toListThumbnail
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.utils.LIVE_RESULT_TYPE
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.mp.KoinPlatform.getKoin
import kotlin.math.pow

private const val TAG = "SongRepositoryImpl"

/**
 * How many pages of the liked songs are read at most.
 *
 * A library of thousands of likes is read page by page; the limit keeps a library that keeps
 * growing from holding the sync forever.
 */
private const val MAX_LIKED_SONGS_PAGES = 50

/**
 * The liked songs of the account, and whether the library was read at all.
 *
 * The two answers are kept apart: a library that answered and holds no songs is an account
 * without likes, while a library that did not answer is a session that could not be read. Only
 * the first may take the local favorites away.
 */
internal data class RemoteLikedSongs(
    val tracks: List<Track>,
    val libraryRead: Boolean,
)

/**
 * The library could not be read.
 *
 * [refused] says the session was refused (the page asked for a sign-in), which is a different
 * problem than a request that did not get an answer.
 */
internal class LikedSongsUnreadableException(
    val refused: Boolean,
    message: String,
) : Exception(message)
private const val COMMENTS_TAG = "Comments"
internal const val KEY_LAST_FAVORITES_SYNC_TIME = "last_favorites_sync_time"
internal const val KEY_LAST_SYNCED_ACCOUNT = "last_synced_account_id"
private const val KEY_LEGACY_LAST_SYNCED_ACCOUNT = "last_synced_account_email"
private const val KEY_ACCOUNT_FAV_BACKUP_PREFIX = "account_fav_cache_"

@Serializable
internal data class AccountFavoritesSnapshot(
    val accountId: String,
    val savedAtEpochMs: Long,
    val songs: List<AccountSongFavoriteSnapshot>,
    val tombstones: List<AccountTombstoneSnapshot> = emptyList(),
)

@Serializable
internal data class AccountSongFavoriteSnapshot(
    val videoId: String,
    val title: String = "",
    val favoriteAtEpochMs: Long?,
)

@Serializable
internal data class AccountTombstoneSnapshot(
    val type: String,
    val id: String,
    val isFavorite: Boolean,
    val updatedAt: Long,
)

internal fun getAccountIdentifier(account: GoogleAccountEntity?): String {
    if (account == null || account.email.isBlank()) return ""
    val email = account.email.trim().lowercase()
    val pageId = account.pageId?.trim().orEmpty()
    return if (pageId.isNotEmpty()) "${email}#${pageId}" else "${email}#"
}

internal fun isSameAccount(storedAccountId: String, currentAccount: GoogleAccountEntity?): Boolean {
    if (storedAccountId.isBlank() || currentAccount == null || currentAccount.email.isBlank()) return false
    val currentId = getAccountIdentifier(currentAccount)
    if (storedAccountId == currentId) return true
    // Backward compatibility if previous version stored raw email:
    val rawEmail = currentAccount.email.trim().lowercase()
    if (storedAccountId == rawEmail && currentAccount.pageId.isNullOrBlank()) {
        return true
    }
    return false
}

/**
 * A short, non-reversible tag of an account id.
 *
 * The id of an account holds its email address, and the log is written to a file the user can
 * export. The tag still says whether the account changed, without saying which account it is.
 */
internal fun maskedAccountId(accountId: String): String {
    if (accountId.isBlank()) return "none"
    var hash = 0L
    for (char in accountId) {
        hash = hash * 31 + char.code
    }
    return "acct-" + (hash and 0xFFFFFF).toString(16).padStart(6, '0')
}

internal class SongRepositoryImpl(
    private val dataStoreManager: DataStoreManager,
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
    downloadHandler: DownloadHandler? = null,
    private val youtubeLikeSyncMutex: Mutex = Mutex(),
    private val accountFavoritesSyncMutex: Mutex = Mutex(),
) : SongRepository {
    private val _youTubeSyncStatus = MutableStateFlow<YouTubeSyncStatus>(YouTubeSyncStatus.Idle)
    override val youTubeSyncStatus: StateFlow<YouTubeSyncStatus> = _youTubeSyncStatus.asStateFlow()

    private suspend fun getActiveAccountId(): String {
        val activeAccount = localDataSource.getUsedGoogleAccount()
        val id = getAccountIdentifier(activeAccount)
        return id.ifEmpty { "default_user" }
    }

    private fun isNetworkError(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        var cur: Throwable? = throwable
        while (cur != null) {
            val name = cur::class.simpleName.orEmpty().lowercase()
            val msg = cur.message.orEmpty().lowercase()
            if (name.contains("ioexception") ||
                name.contains("connectexception") ||
                name.contains("socketexception") ||
                name.contains("unknownhostexception") ||
                msg.contains("connect") ||
                msg.contains("socket") ||
                msg.contains("timeout") ||
                msg.contains("unreachable") ||
                msg.contains("network") ||
                msg.contains("unknownhost")
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    override suspend fun flushPendingYouTubeLikes(): Result<Unit> = withContext(Dispatchers.IO) {
        if (dataStoreManager.combineLocalAndYouTubeLiked.first() != TRUE) {
            _youTubeSyncStatus.value = YouTubeSyncStatus.Idle
            return@withContext Result.success(Unit)
        }
        val activeAccountId = getActiveAccountId()
        youtubeLikeSyncMutex.withLock {
            flushPendingYouTubeLikesLocked(activeAccountId)
        }
    }

    override suspend fun retryPendingSync(): Result<Unit> {
        return flushPendingYouTubeLikes()
    }

    override suspend fun getPendingFavoriteCount(accountId: String?): Int {
        val id = accountId ?: getActiveAccountId()
        return localDataSource.getYouTubeFavoritePendingForAccount(id).size
    }

    override suspend fun getPendingFavoriteOperations(accountId: String?): List<YouTubeFavoritePendingEntity> {
        val id = accountId ?: getActiveAccountId()
        return localDataSource.getYouTubeFavoritePendingForAccount(id)
    }

    private suspend fun flushPendingYouTubeLikesLocked(activeAccountId: String): Result<Unit> {
        if (dataStoreManager.combineLocalAndYouTubeLiked.first() != TRUE) {
            _youTubeSyncStatus.value = YouTubeSyncStatus.Idle
            return Result.success(Unit)
        }

        if (dataStoreManager.loggedIn.first() != TRUE || dataStoreManager.cookie.first().isBlank()) {
            val pendingCount = localDataSource.getYouTubeFavoritePendingForAccount(activeAccountId).size
            if (pendingCount > 0) {
                _youTubeSyncStatus.value = YouTubeSyncStatus.AuthenticationRequired
            } else {
                _youTubeSyncStatus.value = YouTubeSyncStatus.Idle
            }
            return Result.success(Unit)
        }

        val pendingList = localDataSource.getYouTubeFavoritePendingForAccount(activeAccountId)
        if (pendingList.isEmpty()) {
            _youTubeSyncStatus.value = YouTubeSyncStatus.Synced
            return Result.success(Unit)
        }

        _youTubeSyncStatus.value = YouTubeSyncStatus.Syncing(pendingList.size)

        var failureEncountered: Throwable? = null
        var lastStatusMessage: String? = null
        var authFailed = false
        var networkFailed = false

        for (pending in pendingList) {
            val currentSong = localDataSource.getSong(pending.videoId)
            val currentLiked = currentSong?.liked == true
            val desiredLiked = pending.targetState == 1

            val targetState = if (currentSong != null && currentLiked != desiredLiked) {
                val nowMs = Clock.System.now().toEpochMilliseconds()
                val updated = pending.copy(
                    targetState = if (currentLiked) 1 else 0,
                    updatedAt = nowMs,
                )
                localDataSource.insertYouTubeFavoritePending(updated)
                if (currentLiked) 1 else 0
            } else {
                pending.targetState
            }

            val attemptTime = Clock.System.now().toEpochMilliseconds()
            val responseResult = try {
                if (targetState == 1) {
                    youTube.addToLiked(pending.videoId)
                } else {
                    youTube.removeFromLiked(pending.videoId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }

            if (responseResult.isSuccess) {
                val statusCode = responseResult.getOrNull() ?: 0
                if (statusCode in 200..299) {
                    localDataSource.deleteYouTubeFavoritePending(activeAccountId, pending.videoId)
                    localDataSource.deleteFavoriteTombstone(FavoriteSyncType.SONG, pending.videoId)
                    Logger.i(TAG, "Successfully synced like status ($targetState) for ${pending.videoId} to YouTube Music")
                } else if (statusCode == 401 || statusCode == 403) {
                    authFailed = true
                    val err = "Authentication error ($statusCode) while syncing ${pending.videoId}"
                    failureEncountered = Exception(err)
                    lastStatusMessage = err
                    localDataSource.insertYouTubeFavoritePending(
                        pending.copy(
                            targetState = targetState,
                            retryCount = pending.retryCount + 1,
                            lastAttemptAt = attemptTime,
                            lastError = err,
                        ),
                    )
                    break
                } else {
                    val err = "HTTP $statusCode while syncing ${pending.videoId}"
                    failureEncountered = Exception(err)
                    lastStatusMessage = err
                    localDataSource.insertYouTubeFavoritePending(
                        pending.copy(
                            targetState = targetState,
                            retryCount = pending.retryCount + 1,
                            lastAttemptAt = attemptTime,
                            lastError = err,
                        ),
                    )
                }
            } else {
                val ex = responseResult.exceptionOrNull()
                if (ex is CancellationException) throw ex
                val msg = ex?.message ?: "Network error"
                failureEncountered = ex ?: Exception(msg)
                lastStatusMessage = msg

                if (msg.contains("401") || msg.contains("UNAUTHENTICATED", ignoreCase = true) || msg.contains("credentials", ignoreCase = true)) {
                    authFailed = true
                } else if (isNetworkError(ex)) {
                    networkFailed = true
                }

                localDataSource.insertYouTubeFavoritePending(
                    pending.copy(
                        targetState = targetState,
                        retryCount = pending.retryCount + 1,
                        lastAttemptAt = attemptTime,
                        lastError = msg,
                    ),
                )

                if (authFailed || networkFailed) {
                    break
                }
            }
        }

        val remainingPending = localDataSource.getYouTubeFavoritePendingForAccount(activeAccountId)
        return if (remainingPending.isEmpty()) {
            _youTubeSyncStatus.value = YouTubeSyncStatus.Synced
            Result.success(Unit)
        } else if (authFailed) {
            _youTubeSyncStatus.value = YouTubeSyncStatus.AuthenticationRequired
            Result.failure(failureEncountered ?: Exception("Authentication required"))
        } else if (networkFailed || isNetworkError(failureEncountered)) {
            _youTubeSyncStatus.value = YouTubeSyncStatus.WaitingForConnection
            Result.failure(failureEncountered ?: Exception("Waiting for connection"))
        } else {
            _youTubeSyncStatus.value = YouTubeSyncStatus.SyncFailed(remainingPending.size, lastStatusMessage)
            Result.failure(failureEncountered ?: Exception(lastStatusMessage ?: "Sync failed"))
        }
    }
    override fun getAllSongs(limit: Int): Flow<List<SongEntity>> =
        flow {
            emit(localDataSource.getAllSongs(limit))
        }.flowOn(Dispatchers.IO)

    override suspend fun setInLibrary(
        videoId: String,
        inLibrary: LocalDateTime,
    ) = withContext(Dispatchers.IO) { localDataSource.setInLibrary(videoId, inLibrary) }

    override fun getSongsByListVideoId(listVideoId: List<String>): Flow<List<SongEntity>> =
        flow {
            // SQLite's `WHERE videoId IN (:list)` does NOT preserve the input list order
            // (it returns rows in table/rowid order). Album tracks, local playlist items,
            // and queue snapshots all rely on caller-defined order, so we restore it here.
            val songs = localDataSource.getSongByListVideoIdFull(listVideoId)
            val byId = songs.associateBy { it.videoId }
            emit(listVideoId.mapNotNull { byId[it] })
        }.flowOn(Dispatchers.IO)

    override fun getDownloadedSongs(): Flow<List<SongEntity>?> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getDownloadedSongs(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override fun getDownloadingSongs(): Flow<List<SongEntity>?> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getDownloadingSongs(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override fun getPreparingSongs(): Flow<List<SongEntity>> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getPreparingSongs(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override fun getDownloadedVideoIdListFromListVideoIdAsFlow(listVideoId: List<String>) =
        localDataSource.getDownloadedVideoIdListFromListVideoIdAsFlow(listVideoId)

    /**
     * The liked songs of the library.
     *
     * A flow of the table, so a song that is liked - or imported from the account of the user
     * while the list is open - shows up in it. A one-shot read left the list at the state of the
     * moment it was read.
     */
    override fun getLikedSongs(): Flow<List<SongEntity>> =
        localDataSource.getLikedSongsAsFlow().flowOn(Dispatchers.IO)

    override suspend fun downloadAllLikedSongs(): Int =
        withContext(Dispatchers.IO) {
            val pending =
                getFullDataFromDB { limit, offset ->
                    localDataSource.getLikedSongs(limit, offset)
                }.filter { it.downloadState != DownloadState.STATE_DOWNLOADED }
            Logger.d(TAG, "Auto-download: queueing ${pending.size} liked songs")
            pending.forEach { song ->
                downloadHandler.downloadTrack(song.videoId, song.title, song.thumbnails.orEmpty())
            }
            pending.size
        }

    override suspend fun runSmartDownloads(count: Int): Int =
        withContext(Dispatchers.IO) {
            val mark = SongEntity.SMART_DOWNLOAD
            var queued = 0
            try {
                // Marks on songs that are no longer downloaded, or that the user now keeps (liked while
                // liked songs are auto-downloaded), come off first, so neither counts toward the limit
                // nor gets removed below. Album and playlist download states are not asked: opening an
                // album whose tracks are all downloaded marks it downloaded, which would claim songs
                // the user never claimed.
                localDataSource.clearCategoryFromNotDownloaded(mark)
                if (dataStoreManager.autoDownloadLikedSongs.first() == TRUE) localDataSource.clearCategoryFromLiked(mark)

                val nowTime = now()
                val since = LocalDateTime(nowTime.date.minus(SMART_DOWNLOADS_WINDOW_DAYS, DateTimeUnit.DAY), nowTime.time)
                val top = mutableListOf<SongEntity>()
                for (videoId in rankBySmartDownloadScore(localDataSource.getSongPlaysSince(since), nowTime)) {
                    if (top.size >= count) break
                    val song = localDataSource.getSong(videoId) ?: continue
                    // Only what YouTube confirms is a song: videos, podcasts, live streams and rows of
                    // unknown type stay out.
                    if (!MusicVideoType.isAudio(song.videoType) || song.isLive) continue
                    // Downloaded or downloading without the mark: the user downloaded it themselves.
                    if (song.downloadState != DownloadState.STATE_NOT_DOWNLOADED && song.category != mark) continue
                    top += song
                }

                top.filter { it.downloadState == DownloadState.STATE_NOT_DOWNLOADED }.forEach { song ->
                    // No row updated means the song was deleted meanwhile, and its file would belong to no row.
                    if (localDataSource.updateSongCategory(song.videoId, mark) > 0) {
                        downloadHandler.downloadTrack(song.videoId, song.title, song.thumbnails.orEmpty())
                        queued++
                    }
                }

                // FIFO: the oldest marked download goes first, but never one still in today's top.
                val keep = top.mapTo(HashSet()) { it.videoId }
                val marked = localDataSource.getSongsByCategory(mark)
                var removed = 0
                for (song in marked) {
                    if (marked.size - removed <= count) break
                    if (song.videoId in keep) continue
                    // File first, mark second: if the process dies in between, the next pass finds the
                    // mark on a song that is no longer downloaded and clears it.
                    downloadHandler.removeDownload(song.videoId)
                    localDataSource.updateSongCategory(song.videoId, null)
                    removed++
                }
                Logger.i(TAG, "Smart downloads: ${top.size} kept, $queued queued, $removed removed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A service start refused because the app went to the background, or a database error,
                // ends the pass here instead of crashing the app; the rest waits for the next pass.
                Logger.e(TAG, "Smart downloads stopped after $queued queued", e)
            }
            queued
        }

    override suspend fun clearSmartDownloads() {
        withContext(Dispatchers.IO) {
            val mark = SongEntity.SMART_DOWNLOAD
            try {
                // Songs the user now keeps only lose the mark; every other marked song is removed.
                if (dataStoreManager.autoDownloadLikedSongs.first() == TRUE) localDataSource.clearCategoryFromLiked(mark)
                val marked = localDataSource.getSongsByCategory(mark)
                marked.forEach { song ->
                    downloadHandler.removeDownload(song.videoId)
                    localDataSource.updateSongCategory(song.videoId, null)
                }
                Logger.i(TAG, "Smart downloads off: ${marked.size} removed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Smart downloads clear stopped", e)
            }
        }
    }

    /**
     * The order below is the whole feature.
     *
     * A song is orphaned only once nothing points at it, so every cached container has to go first —
     * sweeping songs on their own found zero to delete, because eleven thousand of them were held
     * alive by playlists nobody prunes. Artists lead so the two tables keyed on `channelId` can be
     * measured against what is still followed; podcasts, albums and playlists follow; songs last,
     * then the satellite rows that were already orphaned before any of this ran.
     */
    override suspend fun clearHistoryAndOrphanedSongs(): Int =
        withContext(Dispatchers.IO) {
            persistLiveQueueBeforeSweep()
            localDataSource.deleteAllPlaybackEvents()
            val artists = localDataSource.deleteUnfollowedArtists()
            val notifications = localDataSource.deleteNotificationsOfUnfollowedArtists()
            val artistReleases = localDataSource.deleteFollowedArtistReleasesOfUnfollowedArtists()
            val artistMotion = localDataSource.deleteOrphanedArtistMotion()
            val podcasts = localDataSource.deleteUnfavoritedPodcasts()
            val albums = localDataSource.deleteUnreferencedAlbums()
            val playlists = localDataSource.deleteUnreferencedPlaylists()
            val orphans = localDataSource.getOrphanedSongIds()
            val removed = localDataSource.deleteSongsAndRelatedData(orphans)
            val satellites = localDataSource.deleteStaleSongSatellites()
            Logger.d(
                TAG,
                "Clear history: removed $removed of ${orphans.size} candidate songs, " +
                    "$artists artists, $notifications notifications, $artistReleases artist releases, " +
                    "$artistMotion artist motion rows, " +
                    "$podcasts podcasts, $albums albums, $playlists playlists, $satellites stale rows",
            )
            // Last, and outside every statement above: SQLite refuses VACUUM inside a transaction.
            // Without it the file stays fragmented and less space comes back than the user expects.
            // The checkpoint first folds the WAL — which every delete above just filled — back into
            // the main file, so VACUUM actually has those pages to reclaim.
            //
            // Failure here is not failure of the operation: every delete has already committed, so
            // letting it throw would show an error toast for work that actually succeeded. It only
            // costs some disk space, and the next run reclaims it.
            runCatching {
                localDataSource.checkpoint()
                localDataSource.vacuum()
            }.onFailure { Logger.e(TAG, "VACUUM after clearing history failed: ${it.message}") }
            removed
        }

    /**
     * Write whatever is playing right now into the `queue` table, so the sweep cannot delete it.
     *
     * The sweep spares songs found in `queue.listTrack`, but that table is only written on pause,
     * on track change, and on exit — and only when the user has "save recent song and queue" on.
     * Two ways that loses the current session: with the setting off the table is empty, and even
     * with it on, the endless queue keeps appending tracks that no save has caught up with yet.
     * Either way the song playing through the speakers looks orphaned and goes.
     *
     * Deliberately ignores that setting: this is not "remember my queue for next launch", it is
     * "do not delete what is on screen". Writing nothing when the queue is empty matters too —
     * overwriting a previously saved queue with an empty one would strip the protection instead of
     * adding it.
     */
    private suspend fun persistLiveQueueBeforeSweep() {
        val liveQueue = mediaPlayerHandler.queueData.value?.data?.listTracks.orEmpty()
        if (liveQueue.isEmpty()) return
        Logger.d(TAG, "Clear history: pinning ${liveQueue.size} queued tracks before the sweep")
        localDataSource.recoverQueue(QueueEntity(listTrack = liveQueue))
    }

    override fun getCanvasSong(max: Int): Flow<List<SongEntity>> =
        flow {
            emit(localDataSource.getCanvasSong(max))
        }.flowOn(Dispatchers.IO)

    override fun getSongById(id: String): Flow<SongEntity?> =
        flow {
            emit(localDataSource.getSong(id))
        }.flowOn(Dispatchers.IO)

    override fun getSongAsFlow(id: String) = localDataSource.getSongAsFlow(id)

    override fun getLikedSongsByArtist(channelId: String): Flow<List<SongEntity>> = localDataSource.getLikedSongsByArtist(channelId)

    override fun insertSong(songEntity: SongEntity): Flow<Long> = flow<Long> { emit(localDataSource.insertSong(songEntity)) }.flowOn(Dispatchers.IO)

    override fun updateThumbnailsSongEntity(
        thumbnail: String,
        videoId: String,
    ): Flow<Int> = flow { emit(localDataSource.updateThumbnailsSongEntity(thumbnail, videoId)) }.flowOn(Dispatchers.IO)

    override fun updateVideoTypeSongEntity(
        videoType: String,
        videoId: String,
    ): Flow<Int> = flow { emit(localDataSource.updateVideoTypeSongEntity(videoType, videoId)) }.flowOn(Dispatchers.IO)

    override suspend fun updateListenCount(videoId: String) =
        withContext(Dispatchers.IO) {
            localDataSource.updateListenCount(videoId)
        }

    override suspend fun resetTotalPlayTime(videoId: String) =
        withContext(Dispatchers.IO) {
            localDataSource.resetTotalPlayTime(videoId)
        }

    /**
     * Resolved lazily rather than taken in the constructor: the download handler is implemented in
     * the platform media modules, and pulling it in at construction time would make this repository
     * depend on them being built first.
     */
    private val downloadHandler: DownloadHandler by lazy { downloadHandler ?: getKoin().get() }

    /**
     * Lazy for the same reason as [downloadHandler], and here it also breaks a cycle: the handler
     * already depends on this repository (it calls [recoverQueue] whenever it saves the queue).
     */
    private val mediaPlayerHandler: MediaPlayerHandler by lazy { getKoin().get() }

    /**
     * Note the side effect: liking a song can start a download, when the user has asked for that.
     *
     * Deliberately one-way. It fires only on the way *in* (`likeStatus == 1`) and only for songs
     * not already offline, so re-liking something you already have does not queue it twice, and
     * unliking never removes a file — the user downloaded it, only they should undo that.
     */
    override suspend fun updateLikeStatus(
        videoId: String,
        likeStatus: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateLiked(likeStatus, videoId)
        if (likeStatus == 1 && dataStoreManager.autoDownloadLikedSongs.first() == TRUE) {
            val song = localDataSource.getSong(videoId)
            if (song != null && song.downloadState != DownloadState.STATE_DOWNLOADED) {
                Logger.d(TAG, "Auto-downloading liked song: $videoId")
                downloadHandler.downloadTrack(videoId, song.title, song.thumbnails.orEmpty())
            }
        }

        if (videoId.isBlank()) {
            Logger.w(TAG, "Cannot sync like status: videoId is blank")
            return@withContext
        }

        if (dataStoreManager.combineLocalAndYouTubeLiked.first() != TRUE) {
            return@withContext
        }

        if (dataStoreManager.loggedIn.first() != TRUE) {
            Logger.d(TAG, "YouTube like sync skipped: not logged in")
            return@withContext
        }

        val cookie = dataStoreManager.cookie.first()
        if (cookie.isBlank()) {
            Logger.d(TAG, "YouTube like sync skipped: cookie is blank")
            return@withContext
        }

        withContext(Dispatchers.IO) {
            val activeAccountId = getActiveAccountId()
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val existing = localDataSource.getYouTubeFavoritePending(activeAccountId, videoId)
            val pendingEntity = existing?.copy(
                targetState = likeStatus,
                updatedAt = nowMs,
            ) ?: YouTubeFavoritePendingEntity(
                accountId = activeAccountId,
                videoId = videoId,
                targetState = likeStatus,
                createdAt = nowMs,
                updatedAt = nowMs,
            )
            localDataSource.insertYouTubeFavoritePending(pendingEntity)

            try {
                youtubeLikeSyncMutex.withLock {
                    if (dataStoreManager.combineLocalAndYouTubeLiked.first() != TRUE) {
                        Logger.d(TAG, "Skipping YouTube like sync for $videoId: sync preference was disabled while queued")
                        _youTubeSyncStatus.value = YouTubeSyncStatus.Idle
                        return@withLock
                    }

                    if (dataStoreManager.loggedIn.first() != TRUE) {
                        Logger.d(TAG, "Skipping YouTube like sync for $videoId: user logged out while queued")
                        _youTubeSyncStatus.value = YouTubeSyncStatus.AuthenticationRequired
                        return@withLock
                    }

                    if (dataStoreManager.cookie.first().isBlank()) {
                        Logger.d(TAG, "Skipping YouTube like sync for $videoId: cookie became blank while queued")
                        _youTubeSyncStatus.value = YouTubeSyncStatus.AuthenticationRequired
                        return@withLock
                    }

                    val currentSong = localDataSource.getSong(videoId)
                    if (currentSong != null) {
                        val currentLiked = currentSong.liked
                        if ((likeStatus == 1 && !currentLiked) || (likeStatus == 0 && currentLiked)) {
                            Logger.d(
                                TAG,
                                "Skipping superseded YouTube like sync for $videoId: requested likeStatus=$likeStatus but current local state is liked=$currentLiked",
                            )
                            return@withLock
                        }
                    }

                    flushPendingYouTubeLikesLocked(activeAccountId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Error during YouTube like sync for $videoId: ${e.message}")
            }
        }
    }

    override fun updateSongInLibrary(
        inLibrary: LocalDateTime,
        videoId: String,
    ): Flow<Int> = flow { emit(localDataSource.updateSongInLibrary(inLibrary, videoId)) }

    override suspend fun updateDurationSeconds(
        durationSeconds: Int,
        videoId: String,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateDurationSeconds(
            durationSeconds,
            videoId,
        )
    }

    override fun getMostPlayedSongs(): Flow<List<SongEntity>> = localDataSource.getMostPlayedSongs()

    override suspend fun updateDownloadState(
        videoId: String,
        downloadState: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updateDownloadState(
            downloadState,
            videoId,
        )
    }

    override suspend fun insertSongInfo(songInfo: SongInfoEntity) =
        withContext(Dispatchers.IO) {
            localDataSource.insertSongInfo(songInfo)
        }

    override suspend fun getSongInfoEntity(videoId: String): Flow<SongInfoEntity?> =
        flow { emit(localDataSource.getSongInfo(videoId)) }.flowOn(Dispatchers.Main)

    override suspend fun recoverQueue(temp: List<Track>) {
        val queueEntity = QueueEntity(listTrack = temp)
        withContext(Dispatchers.IO) { localDataSource.recoverQueue(queueEntity) }
    }

    override suspend fun removeQueue() {
        withContext(Dispatchers.IO) { localDataSource.deleteQueue() }
    }

    override suspend fun getSavedQueue(): Flow<List<QueueEntity>?> =
        flow {
            emit(localDataSource.getQueue())
        }.flowOn(Dispatchers.IO)

    /**
     * With "Play audio version instead of MV in radio" on, a radio plays each recording as a song:
     * a video row YouTube also shipped as a song ([SongItem.counterpart], sent to a logged-in
     * client only) is swapped for that song, and a video with no song version — a fan remix or
     * mashup — is skipped. [isRadio] gates it because the setting is deliberately radio-scoped: a
     * playlist or album the user picked themselves must still play exactly what it contains.
     *
     * Only entries YouTube *named* as a video count as one. A null `musicVideoType` means the
     * response never said, which is not a claim of "audio" — those are kept rather than guessed at
     * (see [MusicVideoType]).
     *
     * This used to drop every video, which was harmless in a radio started from a song — measured
     * logged in (197 entries over four pages), the only videos in one were UGC. A radio started
     * from a VIDEO is nothing but videos (measured on a UGC seed: 38 OMV, 11 UGC, 1 podcast
     * episode, no song), so dropping emptied the queue outright. A page that still ends up empty —
     * no counterparts, as for a client that is not logged in — is kept as it came: a radio that
     * plays videos beats one that plays nothing.
     */
    private suspend fun List<SongItem>.preferAudioWhenRadioAudioOnly(isRadio: Boolean): List<SongItem> {
        if (!isRadio) return this
        if (dataStoreManager.radioAudioOnly.first() != TRUE) return this
        val songs =
            mapNotNull { item ->
                if (MusicVideoType.isVideoSong(item.musicVideoType)) {
                    item.counterpart?.takeIf { MusicVideoType.isAudio(it.musicVideoType) }
                } else {
                    item
                }
            }.distinctBy { it.id }
        val swapped = count { MusicVideoType.isVideoSong(it.musicVideoType) && MusicVideoType.isAudio(it.counterpart?.musicVideoType) }
        Logger.d(TAG, "Radio audio version: $size rows, $swapped swapped to the song, ${songs.size} kept")
        return songs.ifEmpty { this }
    }

    override fun getContinueTrack(
        playlistId: String,
        continuation: String,
        fromPlaylist: Boolean,
    ): Flow<Pair<ArrayList<Track>?, String?>> =
        flow {
            runCatching {
                var newContinuation: String? = null
                Logger.d(TAG, "getContinueTrack -> playlistId: $playlistId")
                Logger.d(TAG, "getContinueTrack -> continuation: $continuation")
                if (!fromPlaylist) {
                    youTube
                        .next(
                            if (playlistId.startsWith("RRDAMVM")) {
                                WatchEndpoint(videoId = playlistId.removePrefix("RRDAMVM"))
                            } else {
                                WatchEndpoint(playlistId = playlistId)
                            },
                            continuation = continuation,
                        ).onSuccess { next ->
                            val data: ArrayList<SongItem> = arrayListOf()
                            // Only this branch can be a radio — the `else` below continues a real
                            // playlist. `RRDAMVM…` counts as one too: it is YouTube's other
                            // spelling for the radio of a single video, which `isRadioQueueId`
                            // deliberately does not match because it never appears as a queue's
                            // own playlistId.
                            val isRadio =
                                playlistId.startsWith("RRDAMVM") || playlistId.isRadioQueueId()
                            data.addAll(next.items.preferAudioWhenRadioAudioOnly(isRadio))
                            newContinuation = next.continuation
                            emit(Pair(data.toListTrack(), newContinuation))
                        }.onFailure { exception ->
                            exception.printStackTrace()
                            emit(Pair(null, null))
                        }
                } else {
                    youTube
                        .customQuery(
                            browseId = null,
                            continuation = continuation,
                            setLogin = true,
                        ).onSuccess { values ->
                            Logger.d(TAG, "getPlaylistData -> continue: $continuation")
                            Logger.d(TAG, "getPlaylistData -> values: ${values.onResponseReceivedActions}")
                            val dataMore: List<SongItem> =
                                values.onResponseReceivedActions
                                    ?.firstOrNull()
                                    ?.appendContinuationItemsAction
                                    ?.continuationItems
                                    ?.apply {
                                        Logger.w(TAG, "getContinueTrack -> dataMore: ${this.size}")
                                    }?.mapNotNull {
                                        NextPage.fromMusicResponsiveListItemRenderer(
                                            it.musicResponsiveListItemRenderer ?: return@mapNotNull null,
                                        )
                                    } ?: emptyList()
                            newContinuation =
                                values.getPlaylistContinuation()
                            emit(
                                Pair<ArrayList<Track>?, String?>(
                                    dataMore.toListTrack(),
                                    newContinuation,
                                ),
                            )
                        }.onFailure {
                            Logger.e(TAG, "getContinueTrack -> Error: ${it.message}")
                            emit(Pair(null, null))
                        }
                }
            }
        }.flowOn(Dispatchers.IO)

    override fun getSongInfo(videoId: String): Flow<SongInfoEntity?> =
        flow {
            runCatching {
                val id =
                    if (videoId.contains(MERGING_DATA_TYPE.VIDEO)) {
                        videoId.removePrefix(MERGING_DATA_TYPE.VIDEO)
                    } else {
                        videoId
                    }
                youTube
                    .getSongInfo(id)
                    .onSuccess { songInfo ->
                        val song =
                            SongInfoEntity(
                                videoId = songInfo.videoId,
                                author = songInfo.author,
                                authorId = songInfo.authorId,
                                authorThumbnail = songInfo.authorThumbnail,
                                description = songInfo.description,
                                uploadDate = songInfo.uploadDate,
                                subscribers = songInfo.subscribers,
                                viewCount = songInfo.viewCount,
                                like = songInfo.like,
                                dislike = songInfo.dislike,
                            )
                        emit(song)
                        insertSongInfo(
                            song,
                        )
                    }.onFailure {
                        it.printStackTrace()
                        emit(getSongInfoEntity(videoId).lastOrNull())
                    }
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun getLikeStatus(videoId: String): Flow<YouTubeLikeStatus> =
        flow {
            runCatching {
                youTube
                    .getLikedInfo(videoId)
                    .onSuccess {
                        emit(
                            when (it) {
                                LikeStatus.LIKE -> YouTubeLikeStatus.LIKE
                                LikeStatus.DISLIKE -> YouTubeLikeStatus.DISLIKE
                                LikeStatus.INDIFFERENT -> YouTubeLikeStatus.INDIFFERENT
                            },
                        )
                    }.onFailure {
                        it.printStackTrace()
                        emit(YouTubeLikeStatus.INDIFFERENT)
                    }
            }
        }

    override suspend fun addToYouTubeLiked(mediaId: String?): Flow<Int> =
        flow {
            if (!mediaId.isNullOrBlank()) {
                try {
                    youTube
                        .addToLiked(mediaId)
                        .onSuccess {
                            Logger.d(TAG, "Liked -> Success: $it")
                            emit(it)
                        }.onFailure {
                            if (it is CancellationException) throw it
                            Logger.w(TAG, "addToYouTubeLiked failed: ${it.message}")
                            emit(0)
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "addToYouTubeLiked error: ${e.message}")
                    emit(0)
                }
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun addToYouTubeDisliked(mediaId: String?): Flow<Int> =
        flow {
            if (!mediaId.isNullOrBlank()) {
                try {
                    youTube
                        .addToDisliked(mediaId)
                        .onSuccess {
                            Logger.d(TAG, "Disliked -> Success: $it")
                            emit(it)
                        }.onFailure {
                            if (it is CancellationException) throw it
                            Logger.w(TAG, "addToYouTubeDisliked failed: ${it.message}")
                            emit(0)
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "addToYouTubeDisliked error: ${e.message}")
                    emit(0)
                }
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun removeFromYouTubeLiked(mediaId: String?): Flow<Int> =
        flow {
            if (!mediaId.isNullOrBlank()) {
                try {
                    youTube
                        .removeFromLiked(mediaId)
                        .onSuccess {
                            Logger.d(TAG, "Removed liked -> Success: $it")
                            emit(it)
                        }.onFailure {
                            if (it is CancellationException) throw it
                            Logger.w(TAG, "removeFromYouTubeLiked failed: ${it.message}")
                            emit(0)
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "removeFromYouTubeLiked error: ${e.message}")
                    emit(0)
                }
            }
        }.flowOn(Dispatchers.IO)

    override fun downloadToFile(
        track: Track,
        path: String,
        videoId: String,
        isVideo: Boolean,
    ): Flow<DownloadProgress> =
        youTube
            .download(
                track.toSongItemForDownload(),
                path,
                videoId,
                isVideo,
            ).map {
                DownloadProgress(
                    audioDownloadProgress = it.audioDownloadProgress,
                    videoDownloadProgress = it.videoDownloadProgress,
                    downloadSpeed = it.downloadSpeed,
                    errorMessage = it.errorMessage,
                    isMerging = it.isMerging,
                    isError = it.isError,
                    isDone = it.isDone,
                )
            }

    override fun getRelatedData(videoId: String): Flow<Resource<Pair<List<Track>, String?>>> =
        flow {
            runCatching {
                youTube
                    .next(WatchEndpoint(videoId = videoId))
                    .onSuccess { next ->
                        val data: ArrayList<SongItem> = arrayListOf()
                        // Always a radio, though not directly: `next(videoId)` alone answers with
                        // just two rows — the song itself and an `automixPreviewVideoRenderer`
                        // pointing at its `RDAMVM…` radio. `YouTube.next` follows that pointer and
                        // splices the radio in, so everything here past the first row is radio
                        // content, and this is what extends the queue once it runs dry.
                        data.addAll(
                            next.items
                                .filter { it.id != videoId }
                                .toSet()
                                .toList()
                                .preferAudioWhenRadioAudioOnly(isRadio = true),
                        )
                        val nextContinuation = next.continuation
                        emit(Resource.Success<Pair<List<Track>, String?>>(Pair(data.toListTrack().toList(), nextContinuation)))
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        emit(Resource.Error<Pair<List<Track>, String?>>(exception.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getComments(videoId: String): Flow<Resource<YouTubeCommentsPage?>> =
        flow {
            youTube
                .comments(videoId)
                .onSuccess {
                    Logger.i(
                        COMMENTS_TAG,
                        if (it == null) "Comments for $videoId: turned off" else "Comments for $videoId: ${it.header?.countText}, ${it.comments.size} on the first page",
                    )
                    emit(Resource.Success<YouTubeCommentsPage?>(it?.toYouTubeCommentsPage()))
                }
                .onFailure {
                    Logger.e(COMMENTS_TAG, "Comments for $videoId failed", it)
                    emit(Resource.Error<YouTubeCommentsPage?>(it.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun getCommentsPage(token: String): Flow<Resource<YouTubeCommentsPage>> =
        flow {
            youTube
                .commentsPage(token)
                .onSuccess { emit(Resource.Success(it.toYouTubeCommentsPage())) }
                .onFailure {
                    Logger.e(COMMENTS_TAG, "Comments page failed", it)
                    emit(Resource.Error<YouTubeCommentsPage>(it.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun postComment(
        createCommentParams: String,
        text: String,
    ): Flow<Resource<YouTubeComment?>> =
        flow {
            youTube
                .createComment(createCommentParams, text)
                .onSuccess {
                    Logger.i(COMMENTS_TAG, "Comment posted, returned=${it != null}")
                    emit(Resource.Success<YouTubeComment?>(it?.toYouTubeComment()))
                }.onFailure {
                    Logger.e(COMMENTS_TAG, "Posting a comment failed", it)
                    emit(Resource.Error<YouTubeComment?>(it.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun postCommentReply(
        createReplyParams: String,
        text: String,
    ): Flow<Resource<YouTubeComment?>> =
        flow {
            youTube
                .createCommentReply(createReplyParams, text)
                .onSuccess {
                    Logger.i(COMMENTS_TAG, "Reply posted, returned=${it != null}")
                    emit(Resource.Success<YouTubeComment?>(it?.toYouTubeComment()))
                }.onFailure {
                    Logger.e(COMMENTS_TAG, "Posting a reply failed", it)
                    emit(Resource.Error<YouTubeComment?>(it.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun performCommentAction(action: String): Flow<Resource<Unit>> =
        flow {
            youTube
                .performCommentAction(action)
                .onSuccess { emit(Resource.Success(Unit)) }
                .onFailure {
                    Logger.e(COMMENTS_TAG, "Comment action failed", it)
                    emit(Resource.Error<Unit>(it.message.toString()))
                }
        }.flowOn(Dispatchers.IO)

    override fun getRadioFromEndpoint(endpoint: YouTubeWatchEndpoint): Flow<Resource<Pair<List<Track>, String?>>> =
        flow {
            runCatching {
                youTube
                    .next(endpoint.toWatchEndpoint())
                    .onSuccess { next ->
                        val items =
                            next.items.preferAudioWhenRadioAudioOnly(
                                isRadio = endpoint.playlistId?.isRadioQueueId() == true,
                            )
                        emit(Resource.Success(Pair(items.toListTrack(), next.continuation)))
                    }.onFailure {
                        it.printStackTrace()
                        emit(Resource.Error(it.message ?: "Error"))
                    }
            }
        }

    internal fun parseRemoteTrack(renderer: MusicResponsiveListItemRenderer): Track? {
        val songItem = PlaylistPage.fromMusicResponsiveListItemRenderer(renderer)
        if (songItem != null && songItem.id.isNotEmpty()) {
            return songItem.toTrack()
        }

        // Fallback: robust extraction using SongRunParser
        val videoId = renderer.videoId
            ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
            ?: renderer.navigationEndpoint?.watchPlaylistEndpoint?.videoId
            ?: renderer.overlay?.musicItemThumbnailOverlayRenderer?.content
                ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.videoId
            ?: return null
        if (videoId.isEmpty()) return null

        val columns = findSongColumns(renderer)
        val title = renderer.flexColumns.getOrNull(columns.titleIndex ?: 0)
            ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
            ?: renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
            ?: ""
        val album = parseSongAlbumAt(renderer, columns.albumIndex)
            ?: renderer.menu?.menuRenderer?.items
                ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "ALBUM" }
                ?.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint?.browseId
                ?.let { Album(id = it, name = "") }
        val artists = parseSongArtistsAt(renderer, columns.artistIndex)
        val durationText = renderer.fixedColumns?.firstOrNull()
            ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
        val thumbnails = renderer.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.toListThumbnail() ?: emptyList()
        val isExplicit = renderer.badges?.any { it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE" } == true

        return Track(
            album = album,
            artists = artists,
            duration = durationText.orEmpty(),
            durationSeconds = parseDurationSeconds(durationText),
            isAvailable = false,
            isExplicit = isExplicit,
            likeStatus = "LIKE",
            thumbnails = thumbnails,
            title = title,
            videoId = videoId,
            videoType = renderer.musicVideoType,
            category = null,
            feedbackTokens = null,
            resultType = if (renderer.isLive) LIVE_RESULT_TYPE else null,
            year = null,
        )
    }

    /**
     * Reads the liked songs of the signed-in account.
     *
     * The liked songs live on more than one page, and YouTube serves each of them in its own
     * shape: the library lists them in a shelf, the "Liked Music" page lists them as a playlist,
     * and either of them may open with another section before the songs. Reading one fixed path
     * therefore found nothing on the accounts where YouTube served the page another way - and an
     * empty answer cannot be told apart from an account without a single liked song, so the
     * Favorites list stayed empty while YouTube showed the likes.
     *
     * So every known place is read, the shelves are collected wherever they sit, and a page that
     * asks for a sign-in is reported as a failure instead of as an empty library.
     */
    internal suspend fun fetchRemoteLikedSongs(): Result<RemoteLikedSongs> = withContext(Dispatchers.IO) {
        Logger.i(TAG, "Reading the liked songs of the signed-in account")
        val rawTracks = mutableListOf<Track>()
        var unparseableCount = 0
        var pagesRead = 0
        var refusedSources = 0
        var failedSources = 0
        var readableSources = 0
        val sourcesRead = mutableListOf<String>()

        for (browseId in LikedSongsBrowseIds.ALL) {
            val source =
                readLikedSongsSource(
                    browseId = browseId,
                    rawTracks = rawTracks,
                    onUnparseable = { unparseableCount++ },
                )
            when (source) {
                is LikedSongsSource.Read -> {
                    pagesRead += source.pages
                    readableSources++
                    sourcesRead += "$browseId(${source.tracks} tracks)"
                }

                is LikedSongsSource.Refused -> {
                    refusedSources++
                    sourcesRead += "$browseId(refused)"
                }

                is LikedSongsSource.Failed -> {
                    failedSources++
                    sourcesRead += "$browseId(failed)"
                }
            }
            // The first source that answered with songs is enough: the others are the same
            // library in other shapes.
            if (rawTracks.isNotEmpty()) break
        }

        // Deduplicate while preserving remote order
        val uniqueTracks = mutableListOf<Track>()
        val seenVideoIds = mutableSetOf<String>()
        var duplicateCount = 0
        for (track in rawTracks) {
            if (seenVideoIds.add(track.videoId)) {
                uniqueTracks.add(track)
            } else {
                duplicateCount++
            }
        }

        Logger.i(
            TAG,
            "fetchRemoteLikedSongs: ${uniqueTracks.size} track(s) from ${sourcesRead.joinToString(", ")} " +
                "($pagesRead page(s), $duplicateCount duplicates, $unparseableCount unparseable, " +
                "$refusedSources refused, $failedSources failed)",
        )

        if (uniqueTracks.isEmpty() && (refusedSources > 0 || failedSources > 0) && readableSources == 0) {
            // Every place was refused or failed: the library was not read. That is not an account
            // without liked songs.
            return@withContext Result.failure(
                LikedSongsUnreadableException(
                    refused = refusedSources > 0 && failedSources == 0,
                    message = "The liked songs could not be read (${sourcesRead.joinToString(", ")})",
                ),
            )
        }

        Result.success(
            RemoteLikedSongs(
                tracks = uniqueTracks,
                // The library answered: an empty list of tracks is an empty library, not an
                // unread one. The two are told apart here, because only the second may not take
                // the local favorites away.
                libraryRead = readableSources > 0,
            ),
        )
    }

    /** What one place of the library answered. */
    private sealed interface LikedSongsSource {
        /** The place answered with songs. */
        data class Read(
            val tracks: Int,
            val pages: Int,
        ) : LikedSongsSource

        /** The place answered, but asked for a sign-in. */
        data object Refused : LikedSongsSource

        /** The place could not be read. */
        data object Failed : LikedSongsSource
    }

    /**
     * Reads one place of the library and every page that follows it.
     */
    private suspend fun readLikedSongsSource(
        browseId: String,
        rawTracks: MutableList<Track>,
        onUnparseable: () -> Unit,
    ): LikedSongsSource {
        val first = youTube.fetchLibraryPage(browseId = browseId)
        val page =
            first.getOrElse { error ->
                Logger.w(TAG, "fetchRemoteLikedSongs: $browseId could not be read: ${error.message}")
                return LikedSongsSource.Failed
            }
        if (!page.usable) {
            Logger.w(
                TAG,
                "fetchRemoteLikedSongs: $browseId was refused (HTTP ${page.httpStatus}, sign-in: ${page.signInRequired})",
            )
            return if (page.signInRequired) LikedSongsSource.Refused else LikedSongsSource.Failed
        }

        var tracks = 0
        var pages = 1
        page.rows.forEach { renderer ->
            val track = parseRemoteTrackOrDrop(renderer, onUnparseable)
            if (track != null) {
                rawTracks.add(track)
                tracks++
            }
        }

        // The pages that follow.
        val visited = mutableSetOf<String>()
        var continuation = page.continuations.firstOrNull()
        while (continuation != null && visited.add(continuation) && visited.size <= MAX_LIKED_SONGS_PAGES) {
            val next = youTube.fetchLibraryPage(browseId = null, continuation = continuation)
            val nextPage =
                next.getOrElse { error ->
                    Logger.w(TAG, "fetchRemoteLikedSongs: page $pages of $browseId failed: ${error.message}")
                    break
                }
            if (!nextPage.usable) {
                Logger.w(TAG, "fetchRemoteLikedSongs: page $pages of $browseId was refused")
                break
            }
            pages++
            nextPage.rows.forEach { renderer ->
                val track = parseRemoteTrackOrDrop(renderer, onUnparseable)
                if (track != null) {
                    rawTracks.add(track)
                    tracks++
                }
            }
            continuation = nextPage.continuations.firstOrNull()
        }

        return LikedSongsSource.Read(tracks = tracks, pages = pages)
    }

    /**
     * Turns a row into a track. A row that cannot be read is dropped, never the whole page.
     */
    private fun parseRemoteTrackOrDrop(
        renderer: MusicResponsiveListItemRenderer,
        onUnparseable: () -> Unit,
    ): Track? =
        try {
            parseRemoteTrack(renderer).also {
                if (it == null) {
                    onUnparseable()
                    Logger.w(TAG, "fetchRemoteLikedSongs: dropped an unreadable row (videoId=${renderer.videoId})")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onUnparseable()
            Logger.w(TAG, "fetchRemoteLikedSongs: dropped a row that could not be parsed: ${e::class.simpleName}")
            null
        }
    private suspend fun backupAccountFavorites(
        accountId: String,
        likedSongs: List<SongEntity>,
        tombstones: List<FavoriteSyncTombstoneEntity>,
    ) {
        if (accountId.isBlank()) return
        try {
            val snapshot = AccountFavoritesSnapshot(
                accountId = accountId,
                savedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                songs = likedSongs.map {
                    AccountSongFavoriteSnapshot(
                        videoId = it.videoId,
                        title = it.title,
                        favoriteAtEpochMs = it.favoriteAt?.toInstant(TimeZone.currentSystemDefault())?.toEpochMilliseconds(),
                    )
                },
                tombstones = tombstones.map {
                    AccountTombstoneSnapshot(
                        type = it.type,
                        id = it.id,
                        isFavorite = it.isFavorite,
                        updatedAt = it.updatedAt,
                    )
                },
            )
            val jsonStr = Json.encodeToString(AccountFavoritesSnapshot.serializer(), snapshot)
            dataStoreManager.putString(KEY_ACCOUNT_FAV_BACKUP_PREFIX + accountId, jsonStr)
            Logger.i(TAG, "Backed up ${likedSongs.size} favorites and ${tombstones.size} tombstones for account $accountId")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to backup favorites for account $accountId: ${e.message}")
        }
    }

    private suspend fun restoreAccountFavorites(accountId: String) {
        if (accountId.isBlank()) return
        try {
            val jsonStr = dataStoreManager.getString(KEY_ACCOUNT_FAV_BACKUP_PREFIX + accountId).first().orEmpty()
            if (jsonStr.isBlank()) return
            val snapshot = Json.decodeFromString(AccountFavoritesSnapshot.serializer(), jsonStr)
            var restoredCount = 0
            for (item in snapshot.songs) {
                val existing = localDataSource.getSong(item.videoId)
                val favTime = item.favoriteAtEpochMs?.toLocalDateTimeSystem() ?: now()
                if (existing != null) {
                    if (!existing.liked) {
                        localDataSource.updateLiked(1, item.videoId, favTime)
                        restoredCount++
                    }
                } else {
                    val newSong = SongEntity(
                        videoId = item.videoId,
                        duration = "",
                        durationSeconds = 0,
                        isAvailable = true,
                        isExplicit = false,
                        likeStatus = YouTubeLikeStatus.INDIFFERENT.name,
                        title = item.title.ifBlank { item.videoId },
                        videoType = "MUSIC_VIDEO_TYPE_ATV",
                        category = null,
                        resultType = null,
                        liked = true,
                        favoriteAt = favTime,
                    )
                    localDataSource.insertSong(newSong)
                    localDataSource.updateLiked(1, item.videoId, favTime)
                    restoredCount++
                }
            }
            for (tomb in snapshot.tombstones) {
                localDataSource.insertFavoriteTombstone(
                    FavoriteSyncTombstoneEntity(
                        type = tomb.type,
                        id = tomb.id,
                        isFavorite = tomb.isFavorite,
                        updatedAt = tomb.updatedAt,
                    )
                )
            }
            Logger.i(TAG, "Restored $restoredCount favorites from backup for account $accountId")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to restore favorites for account $accountId: ${e.message}")
        }
    }

    override suspend fun syncAccountFavorites(force: Boolean): Result<FavoritesSyncResult> = withContext(Dispatchers.IO) {
        if (dataStoreManager.loggedIn.first() != TRUE) {
            Logger.d(TAG, "syncAccountFavorites skipped: not logged in")
            return@withContext Result.success(FavoritesSyncResult())
        }
        val cookie = dataStoreManager.cookie.first()
        if (cookie.isBlank()) {
            Logger.d(TAG, "syncAccountFavorites skipped: cookie is blank")
            return@withContext Result.success(FavoritesSyncResult())
        }

        val activeAccount = localDataSource.getUsedGoogleAccount()
        val activeAccountId = getAccountIdentifier(activeAccount)

        accountFavoritesSyncMutex.withLock {
            if (dataStoreManager.loggedIn.first() != TRUE || dataStoreManager.cookie.first().isBlank()) {
                return@withLock Result.success(FavoritesSyncResult())
            }

            // Flush pending reverse sync queue before reconciling with remote
            flushPendingYouTubeLikesLocked(activeAccountId)

            // Check if user switched accounts since last sync
            val lastSyncedAccount = dataStoreManager.getString(KEY_LAST_SYNCED_ACCOUNT).first().orEmpty().ifEmpty {
                dataStoreManager.getString(KEY_LEGACY_LAST_SYNCED_ACCOUNT).first().orEmpty()
            }
            if (lastSyncedAccount.isNotEmpty() && activeAccountId.isNotEmpty() && !isSameAccount(lastSyncedAccount, activeAccount)) {
                Logger.i(
                    TAG,
                    "Account switched from ${maskedAccountId(lastSyncedAccount)} to ${maskedAccountId(activeAccountId)} " +
                        "— preserving and isolating favorites",
                )
                val currentLiked = localDataSource.getAllLikedSongs()
                val currentTombs = localDataSource.getAllFavoriteTombstones()
                backupAccountFavorites(lastSyncedAccount, currentLiked, currentTombs)

                localDataSource.clearAllLikedSongs()
                dataStoreManager.putString(KEY_LAST_FAVORITES_SYNC_TIME, "0")
                dataStoreManager.putString(KEY_LAST_SYNCED_ACCOUNT, activeAccountId)

                restoreAccountFavorites(activeAccountId)
            }

            val lastSyncTimestamp = dataStoreManager.getString(KEY_LAST_FAVORITES_SYNC_TIME).first()?.toLongOrNull() ?: 0L
            val nowMs = Clock.System.now().toEpochMilliseconds()
            // Throttle rapid redundant syncs if already ran within last 3 seconds for the same account (unless forced)
            if (!force && nowMs - lastSyncTimestamp < 3000L && isSameAccount(lastSyncedAccount, activeAccount) && lastSyncTimestamp > 0L) {
                Logger.d(
                    TAG,
                    "Favorites sync ran recently (${nowMs - lastSyncTimestamp} ms ago) for ${maskedAccountId(activeAccountId)}, skipping",
                )
                return@withLock Result.success(FavoritesSyncResult())
            }

            // Fetch remote liked songs with pagination
            val remoteResult = fetchRemoteLikedSongs()
            if (remoteResult.isFailure) {
                val err = remoteResult.exceptionOrNull() ?: Exception("Remote fetch failed")
                Logger.w(TAG, "syncAccountFavorites aborted: ${err.message}")
                val refused = (err as? LikedSongsUnreadableException)?.refused == true
                if (refused) {
                    Logger.w(TAG, "The session was refused by the library: the account has to sign in again")
                    _youTubeSyncStatus.value = YouTubeSyncStatus.AuthenticationRequired
                } else if (isNetworkError(err)) {
                    _youTubeSyncStatus.value = YouTubeSyncStatus.WaitingForConnection
                } else if (err.message?.contains("401") == true || err.message?.contains("UNAUTHENTICATED") == true) {
                    _youTubeSyncStatus.value = YouTubeSyncStatus.AuthenticationRequired
                } else {
                    val pendingCount = localDataSource.getYouTubeFavoritePendingForAccount(activeAccountId).size
                    _youTubeSyncStatus.value = YouTubeSyncStatus.SyncFailed(pendingCount, err.message)
                }
                return@withLock Result.failure(err)
            }
            val remoteLikedSongs = remoteResult.getOrThrow()
            val remoteTracks = remoteLikedSongs.tracks
            val remoteLibraryReadable = remoteLikedSongs.libraryRead
            val remoteVideoIds = remoteTracks.map { it.videoId }.toSet()

            // Verify account did not change during in-flight network request
            val currentAccount = localDataSource.getUsedGoogleAccount()
            if (getAccountIdentifier(currentAccount) != activeAccountId) {
                Logger.w(TAG, "Aborting favorites sync: account changed from $activeAccountId to ${getAccountIdentifier(currentAccount)}")
                return@withLock Result.failure(CancellationException("Account changed during favorites sync"))
            }

            var songsAddedOrUpdated = 0
            var songsRemoved = 0
            var importFailures = 0

            val localLikedSongs = localDataSource.getAllLikedSongs()
            val localTombstones = localDataSource.getAllFavoriteTombstones().associateBy { "${it.type}:${it.id}" }
            val syncCycleEpoch = Clock.System.now().toEpochMilliseconds()

            Logger.i(
                TAG,
                "syncAccountFavorites starting: ${remoteTracks.size} remote tracks (${remoteVideoIds.size} unique IDs), " +
                    "${localLikedSongs.size} local favorites, ${localTombstones.size} tombstones",
            )

            // 1. Process remote tracks into local DB
            for (track in remoteTracks) {
                val pending = localDataSource.getYouTubeFavoritePending(activeAccountId, track.videoId)
                if (pending != null && pending.targetState == 0) {
                    // Local user unliked, pending push to remote: do not re-add or re-like locally
                    continue
                }

                val key = "${FavoriteSyncType.SONG}:${track.videoId}"
                val tomb = localTombstones[key]
                if (tomb != null && !tomb.isFavorite) {
                    // Local user explicitly unliked while offline or since last sync
                    if (dataStoreManager.combineLocalAndYouTubeLiked.first() == TRUE) {
                        try {
                            val res = youTube.removeFromLiked(track.videoId)
                            if (res.isSuccess && (res.getOrNull() ?: 0) in 200..299) {
                                localDataSource.deleteFavoriteTombstone(FavoriteSyncType.SONG, track.videoId)
                                localDataSource.deleteYouTubeFavoritePending(activeAccountId, track.videoId)
                            } else {
                                localDataSource.insertFavoriteTombstone(
                                    FavoriteSyncTombstoneEntity(
                                        type = FavoriteSyncType.SONG,
                                        id = track.videoId,
                                        isFavorite = false,
                                        updatedAt = syncCycleEpoch + 1,
                                    )
                                )
                                localDataSource.insertYouTubeFavoritePending(
                                    YouTubeFavoritePendingEntity(
                                        accountId = activeAccountId,
                                        videoId = track.videoId,
                                        targetState = 0,
                                        createdAt = syncCycleEpoch,
                                        updatedAt = syncCycleEpoch + 1,
                                        lastError = "Remote unlike returned status ${res.getOrNull()}",
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Logger.w(TAG, "Failed to propagate local unlike to YouTube for ${track.videoId}: ${e.message}")
                            localDataSource.insertFavoriteTombstone(
                                FavoriteSyncTombstoneEntity(
                                    type = FavoriteSyncType.SONG,
                                    id = track.videoId,
                                    isFavorite = false,
                                    updatedAt = syncCycleEpoch + 1,
                                )
                            )
                            localDataSource.insertYouTubeFavoritePending(
                                YouTubeFavoritePendingEntity(
                                    accountId = activeAccountId,
                                    videoId = track.videoId,
                                    targetState = 0,
                                    createdAt = syncCycleEpoch,
                                    updatedAt = syncCycleEpoch + 1,
                                    lastError = e.message,
                                )
                            )
                        }
                    } else {
                        localDataSource.deleteFavoriteTombstone(FavoriteSyncType.SONG, track.videoId)
                        localDataSource.deleteYouTubeFavoritePending(activeAccountId, track.videoId)
                    }
                    continue
                }

                try {
                    val existing = localDataSource.getSong(track.videoId)
                    if (existing == null) {
                        localDataSource.insertSong(track.toSongEntity().copy(liked = true, favoriteAt = now()))
                        localDataSource.updateLikedWithoutTombstone(liked = 1, videoId = track.videoId)
                        localDataSource.deleteFavoriteTombstone(FavoriteSyncType.SONG, track.videoId)
                        localDataSource.deleteYouTubeFavoritePending(activeAccountId, track.videoId)
                        songsAddedOrUpdated++
                    } else {
                        if (!existing.liked) {
                            localDataSource.updateLikedWithoutTombstone(liked = 1, videoId = track.videoId)
                            songsAddedOrUpdated++
                        }
                        localDataSource.deleteFavoriteTombstone(FavoriteSyncType.SONG, track.videoId)
                        localDataSource.deleteYouTubeFavoritePending(activeAccountId, track.videoId)
                    }
                } catch (e: Exception) {
                    importFailures++
                    Logger.e(TAG, "Failed to import remote track ${track.videoId} (${track.title}): ${e.message}", e)
                }
            }

            // 2. Process local songs absent from remote LM
            //
            // An empty remote list is not proof that the local likes are gone: an unreadable
            // library and a library without a single like look the same from here, and taking the
            // local likes away on that basis loses the favorites of the user. The local likes are
            // therefore only taken away when the remote library was actually read - and it was
            // read when it answered with a shelf, however empty that shelf is.
            val remoteLibraryWasRead = remoteLibraryReadable
            if (!remoteLibraryWasRead) {
                Logger.w(
                    TAG,
                    "The remote library was not read: keeping ${localLikedSongs.size} local favorite(s) untouched",
                )
            }
            for (localSong in localLikedSongs) {
                // An unreadable library says nothing about the local likes: they stay.
                if (!remoteLibraryWasRead) continue
                if (localSong.videoId !in remoteVideoIds) {
                    val pending = localDataSource.getYouTubeFavoritePending(activeAccountId, localSong.videoId)
                    if (pending != null && pending.targetState == 1) {
                        // Local user liked, pending push to remote: protect from local deletion!
                        continue
                    }

                    if (lastSyncTimestamp > 0L) {
                        val key = "${FavoriteSyncType.SONG}:${localSong.videoId}"
                        val tomb = localTombstones[key]
                        val localFavEpoch = tomb?.updatedAt ?: localSong.favoriteAt?.toInstant(TimeZone.currentSystemDefault())?.toEpochMilliseconds() ?: 0L
                        if ((tomb != null && tomb.isFavorite) || localFavEpoch > lastSyncTimestamp) {
                            // Added locally while offline or since last sync -> push to YouTube
                            if (dataStoreManager.combineLocalAndYouTubeLiked.first() == TRUE) {
                                try {
                                    val addResult = youTube.addToLiked(localSong.videoId)
                                    if (addResult.isSuccess && (addResult.getOrNull() ?: 0) in 200..299) {
                                        localDataSource.deleteFavoriteTombstone(FavoriteSyncType.SONG, localSong.videoId)
                                        localDataSource.deleteYouTubeFavoritePending(activeAccountId, localSong.videoId)
                                    } else {
                                        localDataSource.insertFavoriteTombstone(
                                            FavoriteSyncTombstoneEntity(
                                                type = FavoriteSyncType.SONG,
                                                id = localSong.videoId,
                                                isFavorite = true,
                                                updatedAt = syncCycleEpoch + 1,
                                            )
                                        )
                                        localDataSource.insertYouTubeFavoritePending(
                                            YouTubeFavoritePendingEntity(
                                                accountId = activeAccountId,
                                                videoId = localSong.videoId,
                                                targetState = 1,
                                                createdAt = syncCycleEpoch,
                                                updatedAt = syncCycleEpoch + 1,
                                                lastError = "Remote like returned status ${addResult.getOrNull()}",
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Logger.w(TAG, "Failed to propagate local like to YouTube for ${localSong.videoId}: ${e.message}")
                                    localDataSource.insertFavoriteTombstone(
                                        FavoriteSyncTombstoneEntity(
                                            type = FavoriteSyncType.SONG,
                                            id = localSong.videoId,
                                            isFavorite = true,
                                            updatedAt = syncCycleEpoch + 1,
                                        )
                                    )
                                    localDataSource.insertYouTubeFavoritePending(
                                        YouTubeFavoritePendingEntity(
                                            accountId = activeAccountId,
                                            videoId = localSong.videoId,
                                            targetState = 1,
                                            createdAt = syncCycleEpoch,
                                            updatedAt = syncCycleEpoch + 1,
                                            lastError = e.message,
                                        )
                                    )
                                }
                            }
                        } else {
                            // Was present in previous sync, now removed remotely -> unlike locally
                            localDataSource.updateLikedWithoutTombstone(liked = 0, videoId = localSong.videoId, favoriteAt = null)
                            localDataSource.deleteFavoriteTombstone(FavoriteSyncType.SONG, localSong.videoId)
                            localDataSource.deleteYouTubeFavoritePending(activeAccountId, localSong.videoId)
                            songsRemoved++
                        }
                    } else {
                        // First sync with pre-existing local favorites: push to remote if combine is active
                        if (dataStoreManager.combineLocalAndYouTubeLiked.first() == TRUE) {
                            try {
                                val addResult = youTube.addToLiked(localSong.videoId)
                                if (addResult.isSuccess && (addResult.getOrNull() ?: 0) in 200..299) {
                                    localDataSource.deleteFavoriteTombstone(FavoriteSyncType.SONG, localSong.videoId)
                                    localDataSource.deleteYouTubeFavoritePending(activeAccountId, localSong.videoId)
                                } else {
                                    localDataSource.insertFavoriteTombstone(
                                        FavoriteSyncTombstoneEntity(
                                            type = FavoriteSyncType.SONG,
                                            id = localSong.videoId,
                                            isFavorite = true,
                                            updatedAt = syncCycleEpoch + 1,
                                        )
                                    )
                                    localDataSource.insertYouTubeFavoritePending(
                                        YouTubeFavoritePendingEntity(
                                            accountId = activeAccountId,
                                            videoId = localSong.videoId,
                                            targetState = 1,
                                            createdAt = syncCycleEpoch,
                                            updatedAt = syncCycleEpoch + 1,
                                            lastError = "Remote like returned status ${addResult.getOrNull()}",
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                Logger.w(TAG, "Failed to upload pre-existing local like for ${localSong.videoId}: ${e.message}")
                                localDataSource.insertFavoriteTombstone(
                                    FavoriteSyncTombstoneEntity(
                                        type = FavoriteSyncType.SONG,
                                        id = localSong.videoId,
                                        isFavorite = true,
                                        updatedAt = syncCycleEpoch + 1,
                                    )
                                )
                                localDataSource.insertYouTubeFavoritePending(
                                    YouTubeFavoritePendingEntity(
                                        accountId = activeAccountId,
                                        videoId = localSong.videoId,
                                        targetState = 1,
                                        createdAt = syncCycleEpoch,
                                        updatedAt = syncCycleEpoch + 1,
                                        lastError = e.message,
                                    )
                                )
                            }
                        }
                    }
                }
            }

            val finishMs = Clock.System.now().toEpochMilliseconds()
            dataStoreManager.putString(KEY_LAST_FAVORITES_SYNC_TIME, finishMs.toString())
            dataStoreManager.putString(KEY_LAST_SYNCED_ACCOUNT, activeAccountId)

            val remainingPending = localDataSource.getYouTubeFavoritePendingForAccount(activeAccountId).size
            if (remainingPending == 0) {
                _youTubeSyncStatus.value = YouTubeSyncStatus.Synced
            }

            val finalLocalLiked = localDataSource.getAllLikedSongs()
            Logger.i(
                TAG,
                "syncAccountFavorites complete: +$songsAddedOrUpdated, -$songsRemoved, $importFailures import failures. " +
                    "Final local favorites: ${finalLocalLiked.size} (remote: ${remoteTracks.size}, library read: $remoteLibraryReadable)",
            )
            Result.success(FavoritesSyncResult(songsAddedOrUpdated = songsAddedOrUpdated, songsRemoved = songsRemoved))
        }
    }
}

private const val SMART_DOWNLOADS_WINDOW_DAYS = 30
private const val SMART_DOWNLOADS_HALF_LIFE_DAYS = 14.0
private const val MILLIS_PER_DAY = 86_400_000.0

/**
 * The songs in [plays], best Smart downloads score first. Each play adds how much of the song was
 * heard (0..1; a song with no stored length counts as heard in full) times how recent it is, halving
 * every 14 days: a song played through this week outranks one played more often a month ago, and a
 * play skipped at a quarter counts a quarter. Ties go to the song played most recently.
 *
 * Both sides are encoded the way `Converters` stores timestamps (the local wall clock read as UTC),
 * so their difference is the real age whatever the device's time zone.
 */
private fun rankBySmartDownloadScore(
    plays: List<SongPlaySample>,
    now: LocalDateTime,
): List<String> {
    val nowMillis = now.toInstant(TimeZone.UTC).toEpochMilliseconds()
    val scores = HashMap<String, Double>()
    val lastPlayed = HashMap<String, Long>()
    for (play in plays) {
        val playedMillis = play.timestamp.toInstant(TimeZone.UTC).toEpochMilliseconds()
        val heard =
            if (play.durationSecond > 0) (play.listenedSecond.toDouble() / play.durationSecond).coerceIn(0.0, 1.0) else 1.0
        val ageDays = ((nowMillis - playedMillis) / MILLIS_PER_DAY).coerceAtLeast(0.0)
        scores[play.videoId] = (scores[play.videoId] ?: 0.0) + heard * 0.5.pow(ageDays / SMART_DOWNLOADS_HALF_LIFE_DAYS)
        lastPlayed[play.videoId] = maxOf(lastPlayed[play.videoId] ?: playedMillis, playedMillis)
    }
    return scores.keys.sortedWith(
        compareByDescending<String> { scores.getValue(it) }.thenByDescending { lastPlayed.getValue(it) },
    )
}
