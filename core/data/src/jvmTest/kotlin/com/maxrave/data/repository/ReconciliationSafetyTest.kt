package com.maxrave.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.FavoriteSyncType
import com.maxrave.data.favoritesync.FavoritesSyncBundle
import com.maxrave.data.favoritesync.FavoriteSongDto
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.FavoriteSyncTombstoneEntity
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.YouTubeFavoritePendingEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.Runs
import com.maxrave.kotlinytmusicscraper.models.Thumbnail
import com.maxrave.kotlinytmusicscraper.models.ThumbnailRenderer
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.kotlinytmusicscraper.models.response.LibraryPage
import com.maxrave.kotlinytmusicscraper.models.response.libraryRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reconciliation of the favorites against the remote liked list, and the guarantee the
 * incident of 2026-11-21 broke: an incomplete remote snapshot must never take a local favorite
 * away.
 *
 * The shapes below replay that incident with deterministic fakes - a canonical playlist served
 * truncated at its first 100 rows beside a fallback that read the whole list - and pin the
 * safeguards: the completeness of the fetch, the cross-source check, the shrink baseline, the
 * tombstone lifecycle, and the cancellation of a push loop. Nothing here touches the live
 * account; every remote answer is built in this file.
 */
private fun renderer(videoId: String): MusicResponsiveListItemRenderer =
    MusicResponsiveListItemRenderer(
        badges = null,
        fixedColumns = null,
        flexColumns =
            listOf(
                MusicResponsiveListItemRenderer.FlexColumn(
                    MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                        Runs(listOf(Run("Song $videoId", null))),
                    ),
                ),
            ),
        thumbnail =
            ThumbnailRenderer(
                musicThumbnailRenderer =
                    ThumbnailRenderer.MusicThumbnailRenderer(
                        thumbnail = Thumbnails(listOf(Thumbnail("https://i.test/$videoId.jpg", 120, 120))),
                        thumbnailCrop = null,
                        thumbnailScale = null,
                    ),
                musicAnimatedThumbnailRenderer = null,
                croppedSquareThumbnailRenderer = null,
            ),
        menu = null,
        playlistItemData =
            MusicResponsiveListItemRenderer.PlaylistItemData(
                playlistSetVideoId = null,
                videoId = videoId,
            ),
        overlay = null,
        navigationEndpoint = null,
    )


/**
 * A row that looks like a song but cannot be parsed: no videoId in the playlist item data, the
 * navigation endpoint or the overlay - every fallback of `parseRemoteTrack` comes up empty and
 * the row is dropped. This is the shape a future YouTube response change would serve.
 */
private fun malformedRenderer(): MusicResponsiveListItemRenderer =
    MusicResponsiveListItemRenderer(
        badges = null,
        fixedColumns = null,
        flexColumns =
            listOf(
                MusicResponsiveListItemRenderer.FlexColumn(
                    MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                        Runs(listOf(Run("Song with no identifiers", null))),
                    ),
                ),
            ),
        thumbnail = null,
        menu = null,
        playlistItemData = null,
        overlay = null,
        navigationEndpoint = null,
    )


class ReconciliationSafetyTest {
    private lateinit var tempDir: File
    private lateinit var database: MusicDatabase
    private lateinit var localDataSource: LocalDataSource

    /** Long ago: any favorite at this epoch is older than every lastSync the tests seed. */
    private val oldEpochMs = 1_600_000_000_000L
    private val oldFavoriteAt = Instant.fromEpochMilliseconds(oldEpochMs).toLocalDateTime(TimeZone.UTC)

    @BeforeTest
    fun setUp() {
        runBlocking {
            tempDir = Files.createTempDirectory("reconciliation-safety-test").toFile()
            database =
                Room
                    .databaseBuilder<MusicDatabase>(File(tempDir, "test.db").absolutePath)
                    .setDriver(BundledSQLiteDriver())
                    .addTypeConverter(Converters())
                    .build()
            localDataSource = LocalDataSource(database.getDatabaseDao(), database)
        }
    }

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private suspend fun setGoogleAccount(email: String = "listener@gmail.com") {
        localDataSource.insertGoogleAccount(
            GoogleAccountEntity(
                email = email,
                name = "Listener",
                thumbnailUrl = "",
                isUsed = true,
                cache = "cookie",
                authUser = 0,
                pageId = null,
            ),
        )
    }

    private suspend fun insertLocalFavorite(
        videoId: String,
        favoriteAt: LocalDateTime = oldFavoriteAt,
    ) {
        localDataSource.insertSong(
            SongEntity(
                videoId = videoId,
                title = "Song $videoId",
                duration = "3:00",
                durationSeconds = 180,
                isAvailable = true,
                isExplicit = false,
                likeStatus = "LIKE",
                videoType = "MUSIC_VIDEO_TYPE_ATV",
                category = null,
                resultType = null,
                liked = true,
                favoriteAt = favoriteAt,
                downloadState = DownloadState.STATE_NOT_DOWNLOADED,
            ),
        )
        localDataSource.updateLikedWithoutTombstone(liked = 1, videoId = videoId, favoriteAt = favoriteAt)
    }

    private suspend fun insertTombstone(videoId: String, isFavorite: Boolean, updatedAt: Long) {
        localDataSource.insertFavoriteTombstone(
            FavoriteSyncTombstoneEntity(
                type = FavoriteSyncType.SONG,
                id = videoId,
                isFavorite = isFavorite,
                updatedAt = updatedAt,
            ),
        )
    }

    /** Seeds a lastSync a minute ago: old enough to pass the throttle, new enough to trim. */
    private fun recentLastSync(): String = (System.currentTimeMillis() - 60_000L).toString()

    /**
     * The remote of the liked songs, page by page.
     *
     * A page chain per browse id (the canonical playlist and the recently-saved shelf do not
     * answer the same way), or the dynamic list the fake holds - which every like and unlike
     * changes, the way YouTube's does.
     */
    private class FakeReconciliationYouTube : YouTube() {
        class PageSpec(val rows: List<MusicResponsiveListItemRenderer>, val nextToken: String?)

        val pagesByBrowseId: MutableMap<String, List<PageSpec>> = mutableMapOf()

        /** The browse id whose first page was requested last; continuation pages belong to it. */
        var lastBrowseId: String? = null

        /** When set, every browse id serves this list in pages of 100, tokens while more remain. */
        var dynamicRemote: MutableList<String>? = null

        /** The next continuation page fails to fetch at all. */
        var failContinuations = false

        /**
         * The next continuation page answers with an appendContinuationItemsAction envelope that
         * holds no rows and no token - the page the incident's app parsed as an empty end of list.
         */
        var emptyAppendActionContinuation = false

        var addToLikedError: Throwable? = null

        /** Blocks the (N+1)-th addToLiked forever, for the cancellation test. */
        var blockAddToLikedAfter: Int? = null

        val addToLikedCalls = mutableListOf<String>()
        val removeFromLikedCalls = mutableListOf<String>()
        val remoteHolds = mutableListOf<String>()

        override suspend fun fetchLibraryPage(
            browseId: String?,
            continuation: String?,
            customCookie: String?,
        ): Result<LibraryPage> {
            if (continuation != null && failContinuations) {
                return Result.failure(IOException("The continuation page could not be fetched"))
            }
            if (continuation != null && emptyAppendActionContinuation) {
                // The real envelope of a playlist continuation page, built as the model and read
                // the way the app reads every page: through libraryRows().
                val response =
                    BrowseResponse(
                        contents = null,
                        continuationContents = null,
                        header = null,
                        microformat = null,
                        responseContext = com.maxrave.kotlinytmusicscraper.models.ResponseContext(serviceTrackingParams = null),
                        background = null,
                        onResponseReceivedActions =
                            listOf(
                                BrowseResponse.OnResponseReceivedActions(
                                    appendContinuationItemsAction =
                                        BrowseResponse.OnResponseReceivedActions.AppendContinuationItemsAction(
                                            continuationItems = emptyList(),
                                            targetId = "",
                                        ),
                                ),
                            ),
                    )
                val answer = Json { ignoreUnknownKeys = true; explicitNulls = false }.encodeToJsonElement(response)
                val rows = answer.libraryRows()
                return Result.success(
                    LibraryPage(
                        httpStatus = 200,
                        rows = rows.rows,
                        continuations = rows.continuations,
                        shelfCount = rows.shelfCount,
                        signInRequired = false,
                        shape = "",
                    ),
                )
            }
            dynamicRemote?.let { list ->
                val index = continuation?.removePrefix("dyn_")?.toIntOrNull() ?: 0
                val from = index * PAGE_SIZE
                val rows = list.drop(from).take(PAGE_SIZE).map { renderer(it) }
                val nextToken = if (from + PAGE_SIZE < list.size) "dyn_${index + 1}" else null
                return Result.success(
                    LibraryPage(
                        httpStatus = 200,
                        rows = rows,
                        continuations = listOfNotNull(nextToken),
                        shelfCount = 1,
                        signInRequired = false,
                        shape = "",
                    ),
                )
            }
            // A continuation request carries no browse id; it belongs to the source whose
            // first page was asked for last. The fetch reads its sources one after another,
            // so that attribution is deterministic.
            val chain =
                (browseId?.let { pagesByBrowseId[it] } ?: lastBrowseId?.let { pagesByBrowseId[it] })
                    ?: listOf(PageSpec(emptyList(), null))
            if (browseId != null) lastBrowseId = browseId
            val index = continuation?.removePrefix("token_")?.toIntOrNull() ?: 0
            val spec = chain.getOrNull(index)
            return Result.success(
                LibraryPage(
                    httpStatus = 200,
                    rows = spec?.rows.orEmpty(),
                    continuations = listOfNotNull(spec?.nextToken),
                    shelfCount = if (spec != null) 1 else 0,
                    signInRequired = false,
                    shape = "",
                ),
            )
        }

        override suspend fun addToLiked(mediaId: String): Result<Int> {
            blockAddToLikedAfter?.let { threshold ->
                if (addToLikedCalls.size >= threshold) {
                    awaitCancellation()
                }
            }
            addToLikedError?.let { return Result.failure(it) }
            addToLikedCalls.add(mediaId)
            if (!remoteHolds.contains(mediaId)) remoteHolds.add(mediaId)
            dynamicRemote?.let { list -> if (!list.contains(mediaId)) list.add(mediaId) }
            return Result.success(200)
        }

        override suspend fun removeFromLiked(mediaId: String): Result<Int> {
            removeFromLikedCalls.add(mediaId)
            remoteHolds.remove(mediaId)
            dynamicRemote?.let { list -> list.remove(mediaId) }
            return Result.success(200)
        }

        companion object {
            const val PAGE_SIZE = 100
        }
    }

    private fun repository(youTube: YouTube): SongRepositoryImpl {
        val dataStore = TestStore().create()
        return SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = youTube,
        )
    }

    /** The chain of the canonical playlist as the truncated app of the incident saw it. */
    private fun truncatedCanonical(): List<FakeReconciliationYouTube.PageSpec> =
        listOf(FakeReconciliationYouTube.PageSpec((1..100).map { renderer("liked_$it") }, null))

    /** The whole canonical playlist: 620 songs over seven pages, tokens between them. */
    private fun completeCanonical620(): List<FakeReconciliationYouTube.PageSpec> {
        val sizes = listOf(100, 100, 100, 100, 100, 100, 20)
        var songIndex = 0
        return sizes.mapIndexed { page, size ->
            val rows = (1..size).map { renderer("liked_${++songIndex}") }
            FakeReconciliationYouTube.PageSpec(rows, if (page < sizes.size - 1) "token_${page + 1}" else null)
        }
    }

    /** The recently-saved shelf that answers more rows than the truncated canonical playlist. */
    private fun fallbackOf(tracks: Int): List<FakeReconciliationYouTube.PageSpec> {
        val specs = mutableListOf<FakeReconciliationYouTube.PageSpec>()
        var left = tracks
        var page = 0
        while (left > 0) {
            val size = minOf(PAGE_SIZE_TEST, left)
            specs += FakeReconciliationYouTube.PageSpec((1..size).map { renderer("shelf_${page}_$it") }, if (left > size) "token_${page + 1}" else null)
            left -= size
            page++
        }
        return specs
    }

    private companion object {
        const val PAGE_SIZE_TEST = 100
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    /**
     * A. The incident, replayed. The library of the desktop arrives as 624 favorites with 620
     * tombstones; the first sync pushes them to YouTube; the second sync's canonical read comes
     * back truncated at 100 of 620 rows while the recently-saved shelf read 599. The truncated
     * read must defer its removals, not unlike 524 favorites the account still holds.
     */
    @Test
    fun incidentReplay_truncatedCanonicalSnapshot_cannotRemoveLocalFavorites() = runBlocking {
        setGoogleAccount()
        val youTube = FakeReconciliationYouTube()
        val repo = repository(youTube)

        // The library as the login-sync merge left it: 624 favorites, 620 tombstones that say
        // "this is a favorite" (the desktop's own epochs, long ago).
        repeat(624) { index -> insertLocalFavorite("liked_${index + 1}") }
        repeat(620) { index -> insertTombstone("liked_${index + 1}", isFavorite = true, updatedAt = oldEpochMs) }

        // Sync #1: YouTube holds 53 of them already. Everything else is pushed, verified and
        // its tombstone consumed - the run that took twelve minutes on the device.
        youTube.pagesByBrowseId["VLLM"] = listOf(FakeReconciliationYouTube.PageSpec((1..53).map { renderer("liked_$it") }, null))
        youTube.pagesByBrowseId["FEmusic_liked_videos"] = listOf(FakeReconciliationYouTube.PageSpec((1..53).map { renderer("liked_$it") }, null))
        val first = repo.syncAccountFavorites(force = true, source = "login-sync")

        assertTrue(first.isSuccess)
        assertEquals(624, localDataSource.getAllLikedSongs().size, "the first sync must push, not trim")
        assertEquals(571, first.getOrThrow().pushesConfirmed, "571 local-only favorites pushed")
        assertEquals(0, localDataSource.getAllFavoriteTombstones().size, "tombstones consumed by verified pushes")
        assertEquals(571, youTube.remoteHolds.size, "the pushes reached the remote; the 53 it already held are served statically")

        // Sync #2: the canonical playlist answers one full page of 100 rows with no continuation,
        // exactly as the incident's truncated read did, while the shelf paginated 599.
        youTube.pagesByBrowseId["VLLM"] = truncatedCanonical()
        youTube.pagesByBrowseId["FEmusic_liked_videos"] = fallbackOf(599)
        val second = repo.syncAccountFavorites(force = true, source = "library")

        assertTrue(second.isSuccess)
        val result = second.getOrThrow()
        assertEquals(0, result.songsRemoved, "a truncated snapshot must not remove anything")
        assertTrue(result.removalsDeferred, "the removals must be reported as deferred")
        assertFalse(result.snapshotComplete, "the snapshot must be reported as incomplete")
        assertEquals(0, result.pushesConfirmed, "a sync that cannot trust the snapshot must not push against it either")
        assertEquals(
            624,
            localDataSource.getAllLikedSongs().size,
            "the favorites must survive the truncated snapshot; the incident left 100",
        )
    }

    /** B. The same library against a complete 620-row playlist: nothing is trimmed, nothing deferred. */
    @Test
    fun completeRemoteSnapshot_reconcilesWithoutTrimming() = runBlocking {
        setGoogleAccount()
        val youTube = FakeReconciliationYouTube()
        val repo = repository(youTube)

        repeat(624) { index -> insertLocalFavorite("liked_${index + 1}") }
        repeat(620) { index -> insertTombstone("liked_${index + 1}", isFavorite = true, updatedAt = oldEpochMs) }

        // The remote holds 53; the first sync pushes the rest.
        youTube.pagesByBrowseId["VLLM"] = listOf(FakeReconciliationYouTube.PageSpec((1..53).map { renderer("liked_$it") }, null))
        youTube.pagesByBrowseId["FEmusic_liked_videos"] = listOf(FakeReconciliationYouTube.PageSpec((1..53).map { renderer("liked_$it") }, null))
        repo.syncAccountFavorites(force = true, source = "login-sync")

        // Sync #2 reads the whole playlist - the union of what YouTube held and what the
        // pushes added, as the account's real list now is.
        youTube.dynamicRemote = MutableList(624) { index -> "liked_${index + 1}" }
        val second = repo.syncAccountFavorites(force = true, source = "library")

        assertTrue(second.isSuccess)
        assertTrue(second.getOrThrow().snapshotComplete)
        assertFalse(second.getOrThrow().removalsDeferred)
        assertEquals(0, second.getOrThrow().songsRemoved)
        assertEquals(624, localDataSource.getAllLikedSongs().size)
    }

    /** C. The continuation page of the canonical playlist fails to fetch: removals deferred. */
    @Test
    fun failedContinuationPage_defersRemovalsAndKeepsFavorites() = runBlocking {
        setGoogleAccount()
        val youTube = FakeReconciliationYouTube()
        val repo = repository(youTube)

        repeat(300) { index -> insertLocalFavorite("liked_${index + 1}") }
        dataStoreSeed(KEY_LAST_FAVORITES_SYNC_TIME, recentLastSync())
        dataStoreSeed(KEY_LAST_SYNCED_ACCOUNT, "listener@gmail.com#")

        // The canonical playlist: 100 rows and a token; the page after it cannot be fetched.
        youTube.pagesByBrowseId["VLLM"] =
            listOf(
                FakeReconciliationYouTube.PageSpec((1..100).map { renderer("liked_$it") }, "token_1"),
            )
        // The shelf: one complete page, smaller than the canonical read - so only the failed
        // continuation can flag the read.
        youTube.pagesByBrowseId["FEmusic_liked_videos"] =
            listOf(FakeReconciliationYouTube.PageSpec((1..100).map { renderer("liked_$it") }, null))
        youTube.failContinuations = true

        val result = repo.syncAccountFavorites(force = true, source = "test")

        assertTrue(result.isSuccess, "an incomplete read is a deferred reconciliation, not a failed sync")
        assertTrue(result.getOrThrow().removalsDeferred)
        assertFalse(result.getOrThrow().snapshotComplete)
        assertEquals(300, localDataSource.getAllLikedSongs().size, "the favorites must survive")
    }

    /**
     * D. The page the incident's parser saw: a continuation token outstanding, and the next page
     * answering with an appendContinuationItemsAction envelope holding no rows and no token.
     * That page is not the end of the playlist, and the read must say so.
     */
    @Test
    fun emptyAppendActionContinuation_isIncompleteAndDefersRemovals() = runBlocking {
        setGoogleAccount()
        val youTube = FakeReconciliationYouTube()
        val repo = repository(youTube)

        repeat(300) { index -> insertLocalFavorite("liked_${index + 1}") }
        dataStoreSeed(KEY_LAST_FAVORITES_SYNC_TIME, recentLastSync())
        dataStoreSeed(KEY_LAST_SYNCED_ACCOUNT, "listener@gmail.com#")

        youTube.pagesByBrowseId["VLLM"] =
            listOf(FakeReconciliationYouTube.PageSpec((1..100).map { renderer("liked_$it") }, "token_1"))
        youTube.pagesByBrowseId["FEmusic_liked_videos"] =
            listOf(FakeReconciliationYouTube.PageSpec((1..100).map { renderer("liked_$it") }, null))
        youTube.emptyAppendActionContinuation = true

        val result = repo.syncAccountFavorites(force = true, source = "test")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().removalsDeferred)
        assertFalse(result.getOrThrow().snapshotComplete)
        assertEquals(300, localDataSource.getAllLikedSongs().size)
    }

    /**
     * E. A genuinely complete, authoritative empty playlist is a different thing from an
     * incomplete fetch: the likes are gone from the account, and the local favorites follow.
     */
    @Test
    fun genuinelyEmptyCompletePlaylist_clearsLocalFavorites() = runBlocking {
        setGoogleAccount()
        val youTube = FakeReconciliationYouTube()
        val repo = repository(youTube)

        insertLocalFavorite("gone_1")
        insertLocalFavorite("gone_2")
        insertLocalFavorite("gone_3")
        dataStoreSeed(KEY_LAST_FAVORITES_SYNC_TIME, recentLastSync())
        dataStoreSeed(KEY_LAST_SYNCED_ACCOUNT, "listener@gmail.com#")

        youTube.pagesByBrowseId["VLLM"] = listOf(FakeReconciliationYouTube.PageSpec(emptyList(), null))
        youTube.pagesByBrowseId["FEmusic_liked_videos"] = listOf(FakeReconciliationYouTube.PageSpec(emptyList(), null))

        val result = repo.syncAccountFavorites(force = true, source = "test")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().snapshotComplete, "an empty first page with no token is a complete read")
        assertFalse(result.getOrThrow().removalsDeferred, "an authoritative empty library removes")
        assertEquals(0, localDataSource.getAllLikedSongs().size, "the account unliked them; the local follows")
        assertEquals(3, result.getOrThrow().songsRemoved)
    }

    /**
     * K. One song row the parser cannot read, in an otherwise complete 620-row playlist over
     * 624 local favorites. The unreadable row may be a like the account still holds, so the
     * snapshot must say incomplete: the removals are deferred, every favorite survives, the
     * readable rows are still imported, and the unreadable row's tombstone stays for the
     * cycle that can read it.
     */
    @Test
    fun unparseableRow_marksSnapshotIncomplete_andProtectsFavorites() = runBlocking {
        setGoogleAccount()
        val youTube = FakeReconciliationYouTube()
        val repo = repository(youTube)

        repeat(624) { index -> insertLocalFavorite("liked_${index + 1}") }
        repeat(620) { index -> insertTombstone("liked_${index + 1}", isFavorite = true, updatedAt = oldEpochMs) }
        dataStoreSeed(KEY_LAST_FAVORITES_SYNC_TIME, recentLastSync())
        dataStoreSeed(KEY_LAST_SYNCED_ACCOUNT, "listener@gmail.com#")

        // The whole playlist, but page 1's row 7 (liked_7) is served without a single
        // identifier the parser can read.
        youTube.pagesByBrowseId["VLLM"] =
            completeCanonical620().mapIndexed { pageIndex, spec ->
                if (pageIndex == 0) {
                    FakeReconciliationYouTube.PageSpec(
                        spec.rows.mapIndexed { index, row -> if (index == 6) malformedRenderer() else row },
                        spec.nextToken,
                    )
                } else {
                    spec
                }
            }

        val result = repo.syncAccountFavorites(force = true, source = "test")

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().snapshotComplete, "a read that dropped a song row is not complete")
        assertTrue(result.getOrThrow().removalsDeferred, "the removals must wait for a readable read")
        assertEquals(624, localDataSource.getAllLikedSongs().size, "no favorite may be lost to an unreadable row")
        // The 619 readable rows were still processed: their tombstones reached the end of
        // their lifecycle, and the unreadable row's tombstone is the one left standing.
        val remainingTombstones = localDataSource.getAllFavoriteTombstones()
        assertEquals(1, remainingTombstones.size)
        assertEquals("liked_7", remainingTombstones.single().id, "the unreadable row's tombstone must survive")
        Unit
    }

    /**
     * L. A canonical playlist that answers empty and complete while the recently-saved shelf
     * read 300 rows: the shelf's rows prove the account still holds likes the canonical
     * answer said nothing about, so the snapshot is untrusted and the removals are deferred.
     * The baseline is seeded below the shrink-guard threshold so only the cross-source guard
     * can defer, and a genuinely empty account (both sources empty - test E) stays exempt.
     */
    @Test
    fun emptyCanonicalAgainstNonEmptyFallback_defersRemovals() = runBlocking {
        setGoogleAccount()
        val youTube = FakeReconciliationYouTube()
        val repo = repository(youTube)

        repeat(300) { index -> insertLocalFavorite("fav_$index") }
        dataStoreSeed(KEY_LAST_FAVORITES_SYNC_TIME, recentLastSync())
        dataStoreSeed(KEY_LAST_SYNCED_ACCOUNT, "listener@gmail.com#")
        dataStoreSeed(KEY_LAST_REMOTE_LIKED_COUNT, "2")

        youTube.pagesByBrowseId["VLLM"] = listOf(FakeReconciliationYouTube.PageSpec(emptyList(), null))
        youTube.pagesByBrowseId["FEmusic_liked_videos"] = fallbackOf(300)

        val result = repo.syncAccountFavorites(force = true, source = "test")

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().snapshotComplete, "an empty answer beside a non-empty shelf is not a complete read")
        assertTrue(result.getOrThrow().removalsDeferred, "the removals must wait for an agreed read")
        assertEquals(300, localDataSource.getAllLikedSongs().size, "the favorites must survive the empty canonical answer")
        assertEquals(
            "2",
            store[KEY_LAST_REMOTE_LIKED_COUNT],
            "an incomplete read must not move the shrink baseline",
        )
        Unit
    }

    /** F. A push that does not verify keeps its tombstone and its pending op for the next sync. */
    @Test
    fun failedPush_keepsTombstoneAndPendingForRetry() = runBlocking {
        setGoogleAccount()
        val youTube = FakeReconciliationYouTube().apply { addToLikedError = IOException("YouTube refused the like") }
        val repo = repository(youTube)

        insertLocalFavorite("push_me")
        insertTombstone("push_me", isFavorite = true, updatedAt = oldEpochMs)
        dataStoreSeed(KEY_LAST_FAVORITES_SYNC_TIME, recentLastSync())
        dataStoreSeed(KEY_LAST_SYNCED_ACCOUNT, "listener@gmail.com#")
        youTube.pagesByBrowseId["VLLM"] = listOf(FakeReconciliationYouTube.PageSpec(emptyList(), null))
        youTube.pagesByBrowseId["FEmusic_liked_videos"] = listOf(FakeReconciliationYouTube.PageSpec(emptyList(), null))

        val result = repo.syncAccountFavorites(force = true, source = "test")

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().pushesFailed)
        assertEquals(0, result.getOrThrow().pushesConfirmed)
        val tombstone = localDataSource.getFavoriteTombstone(FavoriteSyncType.SONG, "push_me")
        assertNotNull(tombstone, "the tombstone must stay until the like verifies")
        assertTrue(tombstone.isFavorite)
        val pending = localDataSource.getYouTubeFavoritePending("listener@gmail.com#", "push_me")
        assertNotNull(pending, "the pending op must stay for the retry")
        assertEquals(1, pending.targetState)
        assertTrue(
            localDataSource.getAllLikedSongs().any { it.videoId == "push_me" },
            "a failed push must not unlike the local favorite",
        )
        assertTrue(youTube.remoteHolds.isEmpty(), "nothing reached the remote")
    }

    /**
     * G. Cancelling a push loop stops it: the pushes that verified are recorded, the rest keep
     * their tombstones, nothing is removed, and the cancellation is not swallowed as a failure.
     */
    @Test
    fun cancellationDuringPushLoop_stopsWithoutDestructiveChanges() = runBlocking {
        setGoogleAccount()
        val youTube = FakeReconciliationYouTube().apply { blockAddToLikedAfter = 1 }
        val repo = repository(youTube)

        repeat(5) { index -> insertLocalFavorite("cancel_$index") }
        repeat(5) { index -> insertTombstone("cancel_$index", isFavorite = true, updatedAt = oldEpochMs) }
        dataStoreSeed(KEY_LAST_FAVORITES_SYNC_TIME, recentLastSync())
        dataStoreSeed(KEY_LAST_SYNCED_ACCOUNT, "listener@gmail.com#")
        youTube.pagesByBrowseId["VLLM"] = listOf(FakeReconciliationYouTube.PageSpec(emptyList(), null))
        youTube.pagesByBrowseId["FEmusic_liked_videos"] = listOf(FakeReconciliationYouTube.PageSpec(emptyList(), null))

        val job =
            launch(Dispatchers.IO) {
                repo.syncAccountFavorites(force = true, source = "test")
            }
        withTimeout(10_000) {
            while (youTube.addToLikedCalls.size < 1) delay(20)
        }
        job.cancelAndJoin()

        assertTrue(job.isCancelled, "the cancellation must leave the sync, not be recorded as a failed push")
        assertEquals(1, youTube.addToLikedCalls.size, "only the push that verified happened")
        assertNull(
            localDataSource.getFavoriteTombstone(FavoriteSyncType.SONG, youTube.addToLikedCalls.first()),
            "the verified push consumed its tombstone",
        )
        for (index in 0..4) {
            val id = "cancel_$index"
            if (youTube.addToLikedCalls.contains(id)) continue
            assertNotNull(
                localDataSource.getFavoriteTombstone(FavoriteSyncType.SONG, id),
                "$id was not pushed; its tombstone must remain for the next sync",
            )
        }
        assertEquals(5, localDataSource.getAllLikedSongs().size, "no favorite may be lost to a cancellation")
    }

    /**
     * H. Pending operations state the user's latest intent and survive the reconciliation: a
     * pending like is neither trimmed nor pushed twice, and a pending unlike is not re-imported.
     */
    @Test
    fun pendingOperationsSurviveReconciliation() = runBlocking {
        setGoogleAccount()
        val youTube = FakeReconciliationYouTube()
        val repo = repository(youTube)

        // A favorite that exists locally with a pending like: the remote does not list it, and
        // the sync must leave it alone.
        insertLocalFavorite("pending_like")
        localDataSource.insertYouTubeFavoritePending(
            YouTubeFavoritePendingEntity(
                accountId = "listener@gmail.com#",
                videoId = "pending_like",
                targetState = 1,
                createdAt = oldEpochMs,
                updatedAt = oldEpochMs,
            ),
        )
        // A song the remote holds but the user unliked locally, waiting to be pushed as an
        // unlike: the import must not re-like it.
        localDataSource.insertSong(
            SongEntity(
                videoId = "pending_unlike",
                title = "Song pending_unlike",
                duration = "3:00",
                durationSeconds = 180,
                isAvailable = true,
                isExplicit = false,
                likeStatus = "INDIFFERENT",
                videoType = "MUSIC_VIDEO_TYPE_ATV",
                category = null,
                resultType = null,
                liked = false,
                downloadState = DownloadState.STATE_NOT_DOWNLOADED,
            ),
        )
        localDataSource.insertYouTubeFavoritePending(
            YouTubeFavoritePendingEntity(
                accountId = "listener@gmail.com#",
                videoId = "pending_unlike",
                targetState = 0,
                createdAt = oldEpochMs,
                updatedAt = oldEpochMs,
            ),
        )
        dataStoreSeed(KEY_LAST_FAVORITES_SYNC_TIME, recentLastSync())
        dataStoreSeed(KEY_LAST_SYNCED_ACCOUNT, "listener@gmail.com#")
        // The remote the fetch serves. The pending flush runs first and pushes the pending like
        // into it, so by the time the reconciliation walks the local list the remote holds both.
        youTube.dynamicRemote = mutableListOf("pending_unlike")

        val result = repo.syncAccountFavorites(force = true, source = "test")

        assertTrue(result.isSuccess)
        val liked = localDataSource.getAllLikedSongs()
        assertTrue(liked.none { it.videoId == "pending_unlike" }, "a pending unlike must not be resurrected")
        assertTrue(
            liked.any { it.videoId == "pending_like" },
            "a pending like must end up liked locally and remotely",
        )
        assertEquals(
            1,
            youTube.addToLikedCalls.count { it == "pending_like" },
            "the pending flush pushes it once; the reconciliation must not push it again",
        )
        assertNull(
            localDataSource.getYouTubeFavoritePending("listener@gmail.com#", "pending_like"),
            "the verified push consumed the pending like",
        )
        assertNull(
            localDataSource.getYouTubeFavoritePending("listener@gmail.com#", "pending_unlike"),
            "the verified unlike consumed the pending unlike",
        )
        assertEquals(
            1,
            youTube.removeFromLikedCalls.count { it == "pending_unlike" },
            "the unlike was carried to the remote exactly once",
        )
        Unit
    }

    /**
     * I. The shrink baseline belongs to one account: switching accounts clears it, so the new
     * account's first (small) read is not mistaken for a collapse of the old one's library.
     */
    @Test
    fun accountSwitch_clearsTheShrinkBaseline() = runBlocking {
        setGoogleAccount("old@gmail.com")
        val youTube = FakeReconciliationYouTube()
        val repo = repository(youTube)

        // A complete read of 200 songs for the old account sets the baseline.
        youTube.dynamicRemote = MutableList(200) { "old_song_$it" }
        dataStoreSeed(KEY_LAST_FAVORITES_SYNC_TIME, recentLastSync())
        dataStoreSeed(KEY_LAST_SYNCED_ACCOUNT, "old@gmail.com#")
        val first = repo.syncAccountFavorites(force = true, source = "test")
        assertTrue(first.isSuccess)

        // The user signs into another account.
        localDataSource.updateGoogleAccountUsed("old@gmail.com", false)
        localDataSource.insertGoogleAccount(
            GoogleAccountEntity(
                email = "new@gmail.com",
                name = "New",
                thumbnailUrl = "",
                isUsed = true,
                cache = "cookie",
                authUser = 0,
                pageId = null,
            ),
        )
        val result = repo.syncAccountFavorites(force = true, source = "sign-in")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().removalsDeferred.not(), "the new account's small library must not be treated as a shrink")
        // The baseline was cleared with the account switch and re-seeded by this sync's complete
        // read: the next cycle compares against the new account's own list.
        assertEquals("200", store[KEY_LAST_REMOTE_LIKED_COUNT], "the baseline now belongs to the new account")
    }

    /**
     * J. The full convergence cycle: the desktop's bundle merges in, the phone pushes what the
     * remote lacks, and a complete read reconciles - without deleting anything that was not
     * authoritatively deleted.
     */
    @Test
    fun convergenceCycle_mergePushReconcile_withoutAccidentalDeletion() = runBlocking {
        setGoogleAccount()
        val youTube = FakeReconciliationYouTube()
        val repo = repository(youTube)

        // Three songs the phone already likes; the desktop bundle carries 624.
        repeat(3) { index -> insertLocalFavorite("liked_${index + 1}") }
        val bundle =
            FavoritesSyncBundle(
                songs =
                    (1..624).map { index ->
                        FavoriteSongDto(
                            videoId = "liked_$index",
                            title = "Song liked_$index",
                            durationSeconds = 180,
                            likeStatus = "LIKE",
                            videoType = "MUSIC_VIDEO_TYPE_ATV",
                            favoriteAtEpochMs = oldEpochMs + index,
                        )
                    },
            )
        database.getDatabaseDao().mergeFavoritesBundle(bundle)

        assertEquals(624, localDataSource.getAllLikedSongs().size, "the merge fills the library")
        assertEquals(624, localDataSource.getAllFavoriteTombstones().size, "the merge tombstones what it merged")

        // The remote holds 53 of them; the first sync pushes the rest, verified one by one.
        youTube.pagesByBrowseId["VLLM"] = listOf(FakeReconciliationYouTube.PageSpec((1..53).map { renderer("liked_$it") }, null))
        youTube.pagesByBrowseId["FEmusic_liked_videos"] = listOf(FakeReconciliationYouTube.PageSpec((1..53).map { renderer("liked_$it") }, null))
        val first = repo.syncAccountFavorites(force = true, source = "login-sync")
        assertTrue(first.isSuccess)
        assertEquals(571, first.getOrThrow().pushesConfirmed)

        // The second sync reads everything back - the account's list is now the union.
        youTube.dynamicRemote = MutableList(624) { index -> "liked_${index + 1}" }
        val second = repo.syncAccountFavorites(force = true, source = "library")
        assertTrue(second.isSuccess)
        assertEquals(0, second.getOrThrow().songsRemoved)
        assertFalse(second.getOrThrow().removalsDeferred)
        assertEquals(624, localDataSource.getAllLikedSongs().size)
        assertEquals(624, youTube.dynamicRemote?.size, "phone and remote converged on the union")
        assertEquals(0, localDataSource.getAllFavoriteTombstones().size, "every tombstone reached the end of its lifecycle")
    }

    // ------------------------------------------------------------------
    // DataStore proxy
    // ------------------------------------------------------------------

    private val store = mutableMapOf<String, String>()

    private suspend fun dataStoreSeed(key: String, value: String) {
        store[key] = value
    }

    private inner class TestStore {
        fun create(): DataStoreManager {
            val handler =
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "getCombineLocalAndYouTubeLiked" -> kotlinx.coroutines.flow.flowOf(DataStoreManager.TRUE)
                        "getLoggedIn" -> kotlinx.coroutines.flow.flowOf(DataStoreManager.TRUE)
                        "getCookie" -> kotlinx.coroutines.flow.flowOf("cookie_abc")
                        "getPageId" -> kotlinx.coroutines.flow.flowOf("")
                        "getAuthUser" -> kotlinx.coroutines.flow.flowOf("0")
                        "putString" -> {
                            store[args?.get(0) as? String ?: ""] = args?.get(1) as? String ?: ""
                            Unit
                        }
                        "getString" -> kotlinx.coroutines.flow.flowOf(store[args?.get(0) as? String ?: ""])
                        "toString" -> "TestStore"
                        "hashCode" -> System.identityHashCode(this)
                        "equals" -> args?.get(0) === this
                        else -> {
                            if (method.returnType == kotlinx.coroutines.flow.Flow::class.java) {
                                kotlinx.coroutines.flow.flowOf("")
                            } else if (method.returnType == java.lang.Boolean.TYPE) {
                                false
                            } else if (method.returnType == java.lang.Integer.TYPE) {
                                0
                            } else {
                                null
                            }
                        }
                    }
                }
            return Proxy.newProxyInstance(
                DataStoreManager::class.java.classLoader,
                arrayOf(DataStoreManager::class.java),
                handler,
            ) as DataStoreManager
        }
    }
}
