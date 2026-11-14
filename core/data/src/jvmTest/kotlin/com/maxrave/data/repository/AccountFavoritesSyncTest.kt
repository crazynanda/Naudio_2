package com.maxrave.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.Continuation
import com.maxrave.kotlinytmusicscraper.models.MusicPlaylistShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.NavigationEndpoint
import com.maxrave.kotlinytmusicscraper.models.ResponseContext
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.Runs
import com.maxrave.kotlinytmusicscraper.models.SectionListRenderer
import com.maxrave.kotlinytmusicscraper.models.Tabs
import com.maxrave.kotlinytmusicscraper.models.Thumbnail
import com.maxrave.kotlinytmusicscraper.models.ThumbnailRenderer
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountFavoritesSyncTest {

    private lateinit var tempDir: File
    private lateinit var database: MusicDatabase
    private lateinit var localDataSource: LocalDataSource

    @BeforeTest
    fun setUp() {
        runBlocking {
            tempDir = Files.createTempDirectory("account-fav-sync-test").toFile()
            val dbFile = File(tempDir, "test.db")
            database = Room
                .databaseBuilder<MusicDatabase>(dbFile.absolutePath)
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

    private suspend fun setGoogleAccount(email: String, name: String = "Test User", pageId: String? = null) {
        localDataSource.insertGoogleAccount(
            GoogleAccountEntity(
                email = email,
                name = name,
                thumbnailUrl = "https://img.test/$email.png",
                isUsed = true,
                cache = "valid_cookie_$email",
                authUser = 0,
                pageId = pageId,
            ),
        )
    }

    private fun createTestRenderer(videoId: String, title: String): MusicResponsiveListItemRenderer =
        MusicResponsiveListItemRenderer(
            badges = null,
            fixedColumns = null,
            flexColumns = listOf(
                MusicResponsiveListItemRenderer.FlexColumn(
                    MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                        Runs(listOf(Run(title, null))),
                    ),
                ),
                MusicResponsiveListItemRenderer.FlexColumn(
                    MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                        Runs(listOf(Run("Artist $videoId", null))),
                    ),
                ),
            ),
            thumbnail = ThumbnailRenderer(
                musicThumbnailRenderer = ThumbnailRenderer.MusicThumbnailRenderer(
                    thumbnail = Thumbnails(listOf(Thumbnail("https://img.test/$videoId.jpg", 120, 120))),
                    thumbnailCrop = null,
                    thumbnailScale = null,
                ),
                musicAnimatedThumbnailRenderer = null,
                croppedSquareThumbnailRenderer = null,
            ),
            menu = null,
            playlistItemData = MusicResponsiveListItemRenderer.PlaylistItemData(playlistSetVideoId = null, videoId = videoId),
            overlay = null,
            navigationEndpoint = NavigationEndpoint(
                watchEndpoint = WatchEndpoint(videoId = videoId),
            ),
        )

    private fun createInitialBrowseResponse(videoIds: List<String>, continuationToken: String? = null): BrowseResponse {
        val contents = videoIds.map { vid ->
            MusicShelfRenderer.Content(
                musicResponsiveListItemRenderer = createTestRenderer(vid, "Title $vid"),
                musicMultiRowListItemRenderer = null,
                continuationItemRenderer = null,
            )
        }
        val continuations = continuationToken?.let { token ->
            listOf(
                Continuation(
                    nextContinuationData = Continuation.NextContinuationData(
                        continuation = token,
                    ),
                    reloadContinuationData = null,
                ),
            )
        }
        return BrowseResponse(
            contents = BrowseResponse.Contents(
                singleColumnBrowseResultsRenderer = Tabs(
                    listOf(
                        Tabs.Tab(
                            tabRenderer = Tabs.Tab.TabRenderer(
                                title = null,
                                content = Tabs.Tab.TabRenderer.Content(
                                    sectionListRenderer = SectionListRenderer(
                                        header = null,
                                        contents = listOf(
                                            SectionListRenderer.Content(
                                                musicCarouselShelfRenderer = null,
                                                musicShelfRenderer = null,
                                                musicCardShelfRenderer = null,
                                                musicPlaylistShelfRenderer = MusicPlaylistShelfRenderer(
                                                    playlistId = "VLLM",
                                                    contents = contents,
                                                    collapsedItemCount = 0,
                                                    continuations = continuations,
                                                ),
                                                musicDescriptionShelfRenderer = null,
                                                musicResponsiveHeaderRenderer = null,
                                                musicEditablePlaylistDetailHeaderRenderer = null,
                                                gridRenderer = null,
                                            ),
                                        ),
                                        continuations = null,
                                    ),
                                    musicQueueRenderer = null,
                                ),
                                endpoint = null,
                            ),
                        ),
                    ),
                ),
                twoColumnBrowseResultsRenderer = null,
                sectionListRenderer = null,
            ),
            continuationContents = null,
            header = null,
            microformat = null,
            responseContext = ResponseContext(serviceTrackingParams = null),
            background = null,
            onResponseReceivedActions = null,
        )
    }

    private fun createContinuationBrowseResponse(videoIds: List<String>, nextContinuationToken: String? = null): BrowseResponse {
        val contents = videoIds.map { vid ->
            MusicShelfRenderer.Content(
                musicResponsiveListItemRenderer = createTestRenderer(vid, "Title $vid"),
                musicMultiRowListItemRenderer = null,
                continuationItemRenderer = null,
            )
        }
        val continuations = nextContinuationToken?.let { token ->
            listOf(
                Continuation(
                    nextContinuationData = Continuation.NextContinuationData(
                        continuation = token,
                    ),
                    reloadContinuationData = null,
                ),
            )
        }
        return BrowseResponse(
            contents = null,
            continuationContents = BrowseResponse.ContinuationContents(
                sectionListContinuation = null,
                musicPlaylistShelfContinuation = BrowseResponse.ContinuationContents.MusicPlaylistShelfContinuation(
                    contents = contents,
                    continuations = continuations,
                ),
                musicShelfContinuation = null,
                gridContinuation = null,
            ),
            header = null,
            microformat = null,
            responseContext = ResponseContext(serviceTrackingParams = null),
            background = null,
            onResponseReceivedActions = null,
        )
    }

    @Test
    fun androidFavoritesDownloadedByWindows_appearInWindowsLibrary() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_1", "song_2", "song_3"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Windows local DB starts empty
        assertEquals(0, localDataSource.getAllLikedSongs().size)

        // Perform synchronization
        val result = repo.syncAccountFavorites()
        assertTrue(result.isSuccess, "Sync must succeed")
        assertEquals(3, result.getOrThrow().songsAddedOrUpdated)

        // Verify Windows Library now contains all 3 songs
        val windowsLiked = localDataSource.getAllLikedSongs()
        assertEquals(3, windowsLiked.size, "Windows database must contain 3 liked songs")
        val likedIds = windowsLiked.map { it.videoId }.toSet()
        assertTrue(likedIds.containsAll(listOf("song_1", "song_2", "song_3")))
        assertEquals(3, localDataSource.countLikedSongs().first())
    }

    @Test
    fun emptyWindowsDatabaseDoesNotEraseRemoteFavorites() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("cloud_1", "cloud_2"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Local DB has 0 items
        assertEquals(0, localDataSource.getAllLikedSongs().size)

        // Run sync
        val result = repo.syncAccountFavorites()
        assertTrue(result.isSuccess)

        // Remote favorites must NOT be deleted
        assertTrue(fakeYt.removeFromLikedCalls.isEmpty(), "Never call removeFromLiked for an empty local DB recovery")
        assertEquals(listOf("cloud_1", "cloud_2"), fakeYt.remoteTracks)
        assertEquals(2, localDataSource.getAllLikedSongs().size)
    }

    @Test
    fun addingFavoriteOnAndroidUpdatesWindows() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_1", "song_2"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // First sync establishes baseline
        repo.syncAccountFavorites()
        assertEquals(2, localDataSource.getAllLikedSongs().size)

        // Android user likes a new song remotely
        fakeYt.remoteTracks.add("song_new_android")

        val updateResult = repo.syncAccountFavorites(force = true)
        assertTrue(updateResult.isSuccess)

        // Windows now contains the newly added Android favorite
        val updated = localDataSource.getAllLikedSongs().map { it.videoId }
        assertEquals(3, updated.size)
        assertTrue(updated.contains("song_new_android"))
    }

    @Test
    fun removingFavoriteOnAndroidUpdatesWindows() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_1", "song_2", "song_3"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        repo.syncAccountFavorites()
        assertEquals(3, localDataSource.getAllLikedSongs().size)

        // Android user unlikes "song_2" remotely
        fakeYt.remoteTracks.remove("song_2")
        kotlinx.coroutines.delay(20)

        val updateResult = repo.syncAccountFavorites(force = true)
        assertTrue(updateResult.isSuccess)
        assertEquals(1, updateResult.getOrThrow().songsRemoved)

        // Windows local DB unlikes song_2
        val updated = localDataSource.getAllLikedSongs().map { it.videoId }
        assertEquals(2, updated.size)
        assertFalse(updated.contains("song_2"))
        assertFalse(localDataSource.getSong("song_2")?.liked == true)
    }

    @Test
    fun addingFavoriteOnWindowsUpdatesRemoteAndAndroid() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_1"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Initial sync
        repo.syncAccountFavorites()
        assertEquals(1, localDataSource.getAllLikedSongs().size)

        // Windows user likes a song locally
        insertTestSong("song_windows_liked", isLiked = false)
        repo.updateLikeStatus("song_windows_liked", 1)

        // Verify addToLiked was dispatched to YouTube
        assertTrue(fakeYt.addToLikedCalls.contains("song_windows_liked"), "Like on Windows must upload to remote account")
        assertTrue(fakeYt.remoteTracks.contains("song_windows_liked"), "Remote account must now reflect the new favorite")
    }

    @Test
    fun offlineWindowsFavoriteAdded_isUploadedOnceOnline() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_remote"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Initial sync establishes baseline
        repo.syncAccountFavorites()
        kotlinx.coroutines.delay(20)

        // User goes offline: liking a song fails remote call, but updates local DB and tombstone
        fakeYt.shouldThrow = IOException("Offline / network disconnected")
        insertTestSong("song_offline_local", isLiked = false)
        repo.updateLikeStatus("song_offline_local", 1)
        assertTrue(localDataSource.getSong("song_offline_local")?.liked == true, "Local DB is liked offline")

        // Network restored
        fakeYt.shouldThrow = null
        fakeYt.addToLikedCalls.clear()

        val syncResult = repo.syncAccountFavorites(force = true)
        assertTrue(syncResult.isSuccess)

        // Sync detected offline local favorite and uploaded it to YouTube
        assertTrue(fakeYt.addToLikedCalls.contains("song_offline_local"), "Offline local like must be uploaded when sync runs")
    }

    @Test
    fun offlineWindowsFavoriteRemoved_isPropagatedOnceOnline() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_to_unlike"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Initial sync
        repo.syncAccountFavorites()
        assertTrue(localDataSource.getSong("song_to_unlike")?.liked == true)

        // User unlikes while offline
        fakeYt.shouldThrow = IOException("Offline / network disconnected")
        repo.updateLikeStatus("song_to_unlike", 0)
        assertFalse(localDataSource.getSong("song_to_unlike")?.liked == true)

        // Network restored
        fakeYt.shouldThrow = null
        fakeYt.removeFromLikedCalls.clear()

        // Sync runs: remote still has "song_to_unlike", but local has unlike tombstone newer than lastSync
        val syncResult = repo.syncAccountFavorites(force = true)
        assertTrue(syncResult.isSuccess)

        // Song must NOT be resurrected locally!
        assertFalse(localDataSource.getSong("song_to_unlike")?.liked == true, "Offline unlike must not be resurrected")
        // Removal must be propagated to YouTube
        assertTrue(fakeYt.removeFromLikedCalls.contains("song_to_unlike"), "Offline unlike must be sent to YouTube")
    }

    @Test
    fun accountSwitching_isolatesFavoritesBetweenAccounts() = runBlocking {
        // User A logs in with songs A1, A2
        setGoogleAccount("accountA@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_A1", "song_A2"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_A",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        repo.syncAccountFavorites()
        assertEquals(setOf("song_A1", "song_A2"), localDataSource.getAllLikedSongs().map { it.videoId }.toSet())

        // User switches to Account B with songs B1, B2, B3
        localDataSource.deleteGoogleAccount("accountA@gmail.com")
        setGoogleAccount("accountB@gmail.com")
        fakeYt.remoteTracks.clear()
        fakeYt.remoteTracks.addAll(listOf("song_B1", "song_B2", "song_B3"))

        // Sync under Account B
        val resultB = repo.syncAccountFavorites()
        assertTrue(resultB.isSuccess)

        // Local DB must ONLY contain Account B's favorites!
        val currentLiked = localDataSource.getAllLikedSongs().map { it.videoId }.toSet()
        assertEquals(setOf("song_B1", "song_B2", "song_B3"), currentLiked, "Account B favorites must replace Account A favorites without mixing")
        assertFalse(currentLiked.contains("song_A1"))
        assertFalse(currentLiked.contains("song_A2"))
    }

    @Test
    fun pagination_handlesMultiplePagesWithContinuation() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            // 6 tracks split into pages of 2
            remotePages = listOf(
                listOf("page1_s1", "page1_s2"),
                listOf("page2_s1", "page2_s2"),
                listOf("page3_s1", "page3_s2"),
            )
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        val result = repo.syncAccountFavorites()
        assertTrue(result.isSuccess)
        assertEquals(6, result.getOrThrow().songsAddedOrUpdated)

        val likedSongs = localDataSource.getAllLikedSongs().map { it.videoId }
        assertEquals(6, likedSongs.size, "All 6 songs across 3 pages must be imported")
        assertTrue(likedSongs.containsAll(listOf("page1_s1", "page1_s2", "page2_s1", "page2_s2", "page3_s1", "page3_s2")))
    }

    @Test
    fun remoteApiFailure_preservesLocalDatabase() = runBlocking {
        setGoogleAccount("user@gmail.com")
        insertTestSong("local_song_preserved", isLiked = true)
        localDataSource.updateLiked(1, "local_song_preserved")

        val fakeYt = FakeAccountYouTube().apply {
            shouldThrow = IOException("503 Service Unavailable / Network error")
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        val result = repo.syncAccountFavorites()
        assertTrue(result.isFailure, "Sync should report failure on network error")

        // Local DB remains intact
        assertTrue(localDataSource.getSong("local_song_preserved")?.liked == true, "Local favorites must not be cleared on remote failure")
    }

    @Test
    fun duplicatePrevention_repeatedSyncsDoNotCreateDuplicateSongs() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_dup_1", "song_dup_2"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        repo.syncAccountFavorites()
        assertEquals(2, localDataSource.getAllLikedSongs().size)

        // Re-run sync (bypassing throttle)
        dataStore.putString("last_favorites_sync_time", "0")
        val secondResult = repo.syncAccountFavorites()
        assertTrue(secondResult.isSuccess)

        // Still exactly 2 songs
        assertEquals(2, localDataSource.getAllLikedSongs().size)
    }

    @Test
    fun loggingOutAndBackIn_restoresCorrectAccountFavorites() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_1", "song_2"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Initial sync
        repo.syncAccountFavorites()
        assertEquals(2, localDataSource.getAllLikedSongs().size)

        // User logs out: sync disabled, logged out
        dataStore.setLoggedIn(false)
        dataStore.setCookie("", null)
        dataStore.putString("last_synced_account_email", "")

        // Sync when logged out does nothing
        val loggedOutResult = repo.syncAccountFavorites()
        assertTrue(loggedOutResult.isSuccess)
        assertEquals(0, loggedOutResult.getOrThrow().songsAddedOrUpdated)

        // User logs back in
        dataStore.setLoggedIn(true)
        dataStore.setCookie("cookie_abc", null)
        dataStore.setCombineLocalAndYouTubeLiked(true)
        dataStore.putString("last_favorites_sync_time", "0")

        val loginResult = repo.syncAccountFavorites()
        assertTrue(loginResult.isSuccess)
        assertEquals(2, localDataSource.getAllLikedSongs().size)
    }

    @Test
    fun concurrentSyncRequests_areCoalescedAndExecuteSafely() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_c1", "song_c2", "song_c3"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Launch 5 concurrent sync requests (e.g. app startup + library open + login)
        val jobs = (1..5).map {
            launch(Dispatchers.IO) {
                repo.syncAccountFavorites()
            }
        }
        jobs.joinAll()

        // Database must have exactly 3 songs, no corruption or exceptions
        assertEquals(3, localDataSource.getAllLikedSongs().size)
    }

    @Test
    fun accountSwitch_preservesUnsynchronizedFavoritesAndRestoresOnReturn() = runBlocking {
        // Step 1: User A is logged in and syncs 2 songs
        setGoogleAccount("accountA@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_A1", "song_A2"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_A",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        repo.syncAccountFavorites()
        assertEquals(2, localDataSource.getAllLikedSongs().size)

        // User A adds a song offline (unsynchronized local favorite)
        insertTestSong("unsynced_A_local", isLiked = true)
        localDataSource.updateLiked(1, "unsynced_A_local")
        assertEquals(3, localDataSource.getAllLikedSongs().size)

        // Step 2: User switches to Account B
        localDataSource.deleteGoogleAccount("accountA@gmail.com")
        setGoogleAccount("accountB@gmail.com")
        fakeYt.remoteTracks.clear()
        fakeYt.remoteTracks.addAll(listOf("song_B1", "song_B2"))

        repo.syncAccountFavorites()

        // Account B database has only Account B's songs
        val bLiked = localDataSource.getAllLikedSongs().map { it.videoId }.toSet()
        assertEquals(setOf("song_B1", "song_B2"), bLiked)
        assertFalse(bLiked.contains("unsynced_A_local"))

        // Step 3: User switches BACK to Account A
        localDataSource.deleteGoogleAccount("accountB@gmail.com")
        setGoogleAccount("accountA@gmail.com")
        fakeYt.remoteTracks.clear()
        fakeYt.remoteTracks.addAll(listOf("song_A1", "song_A2"))

        repo.syncAccountFavorites()

        // Account A's unsynchronized favorite MUST be restored and preserved!
        val restoredALiked = localDataSource.getAllLikedSongs().map { it.videoId }.toSet()
        assertTrue(restoredALiked.contains("unsynced_A_local"), "Unsynchronized local favorites must be preserved and restored across account switches")
        assertTrue(restoredALiked.contains("song_A1"))
        assertTrue(restoredALiked.contains("song_A2"))
    }

    @Test
    fun brandChannelAccountIsolation() = runBlocking {
        // User logged in to personal account (pageId = null)
        setGoogleAccount("multi@gmail.com", pageId = null)
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("personal_song_1", "personal_song_2"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_multi",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        repo.syncAccountFavorites()
        assertEquals(2, localDataSource.getAllLikedSongs().size)

        // Switch to Brand Channel under the same email (pageId = "brand_channel_99")
        localDataSource.deleteGoogleAccount("multi@gmail.com")
        setGoogleAccount("multi@gmail.com", pageId = "brand_channel_99")
        fakeYt.remoteTracks.clear()
        fakeYt.remoteTracks.addAll(listOf("brand_song_1"))

        repo.syncAccountFavorites()

        // Must isolate brand channel favorites from personal favorites
        val brandLiked = localDataSource.getAllLikedSongs().map { it.videoId }.toSet()
        assertEquals(setOf("brand_song_1"), brandLiked, "Brand channel favorites must be isolated from personal channel")
        assertFalse(brandLiked.contains("personal_song_1"))
    }

    @Test
    fun initialLogin_preservesPreExistingLocalFavorites() = runBlocking {
        // Pre-existing local favorites before logging in
        insertTestSong("pre_login_fav_1", isLiked = true)
        insertTestSong("pre_login_fav_2", isLiked = true)
        localDataSource.updateLiked(1, "pre_login_fav_1")
        localDataSource.updateLiked(1, "pre_login_fav_2")
        assertEquals(2, localDataSource.getAllLikedSongs().size)

        // Initial login to Account A (lastSyncedAccount is empty)
        setGoogleAccount("newuser@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.add("remote_song_1")
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_new",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        val result = repo.syncAccountFavorites()
        assertTrue(result.isSuccess)

        // Pre-existing local favorites must NOT be deleted!
        val currentLiked = localDataSource.getAllLikedSongs().map { it.videoId }.toSet()
        assertEquals(3, currentLiked.size, "Initial login must combine pre-existing local favorites with remote")
        assertTrue(currentLiked.contains("pre_login_fav_1"))
        assertTrue(currentLiked.contains("pre_login_fav_2"))
        assertTrue(currentLiked.contains("remote_song_1"))

        // And pre-existing local favorites should be uploaded to YouTube
        assertTrue(fakeYt.addToLikedCalls.contains("pre_login_fav_1"))
        assertTrue(fakeYt.addToLikedCalls.contains("pre_login_fav_2"))
    }

    @Test
    fun successfulRemoteUnlike_clearsTombstone() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_unlike_1", "song_unlike_2"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Initial sync
        repo.syncAccountFavorites()
        assertEquals(2, localDataSource.getAllLikedSongs().size)

        // User unlikes "song_unlike_1" locally while offline
        localDataSource.updateLiked(0, "song_unlike_1")
        val tombBefore = localDataSource.getFavoriteTombstone(com.maxrave.domain.data.entities.FavoriteSyncType.SONG, "song_unlike_1")
        assertTrue(tombBefore != null && !tombBefore.isFavorite)

        // Sync runs and calls removeFromLiked on YouTube
        val syncResult = repo.syncAccountFavorites(force = true)
        assertTrue(syncResult.isSuccess)
        assertTrue(fakeYt.removeFromLikedCalls.contains("song_unlike_1"))

        // Tombstone should be cleanly deleted upon successful remote unlike
        val tombAfter = localDataSource.getFavoriteTombstone(com.maxrave.domain.data.entities.FavoriteSyncType.SONG, "song_unlike_1")
        assertTrue(tombAfter == null, "Successful remote unlike must delete the tombstone")
    }

    @Test
    fun failedRemoteUnlike_advancesTombstoneWithoutLocalResurrection() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("song_fail_unlike"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Initial sync
        repo.syncAccountFavorites()
        assertEquals(1, localDataSource.getAllLikedSongs().size)

        // User unlikes song locally
        localDataSource.updateLiked(0, "song_fail_unlike")

        // YouTube throws an error on removeFromLiked
        fakeYt.removeFromLikedError = IOException("500 Internal Server Error")

        // Sync runs and remote unlike fails
        val syncResult = repo.syncAccountFavorites(force = true)
        assertTrue(syncResult.isSuccess)
        fakeYt.removeFromLikedError = null // reset for next sync

        // Local song MUST stay unliked (not resurrected!)
        val songAfter = localDataSource.getSong("song_fail_unlike")
        assertEquals(false, songAfter?.liked, "Local song must remain unliked even when remote unlike fails")

        // And tombstone must have been advanced
        val tomb = localDataSource.getFavoriteTombstone(com.maxrave.domain.data.entities.FavoriteSyncType.SONG, "song_fail_unlike")
        assertTrue(tomb != null && !tomb.isFavorite, "Tombstone must be kept and advanced so song isn't resurrected on next sync")

        // On next sync, song is still not resurrected and remote unlike succeeds
        repo.syncAccountFavorites(force = true)
        val songAfterNextSync = localDataSource.getSong("song_fail_unlike")
        assertEquals(false, songAfterNextSync?.liked, "Song must not be resurrected on subsequent syncs")
        assertTrue(fakeYt.removeFromLikedCalls.contains("song_fail_unlike"), "Remote unlike must be retried on subsequent sync")
        val tombAfterNextSync = localDataSource.getFavoriteTombstone(com.maxrave.domain.data.entities.FavoriteSyncType.SONG, "song_fail_unlike")
        assertTrue(tombAfterNextSync == null, "Tombstone must be cleared once remote unlike succeeds")
    }

    @Test
    fun failedRemoteLike_preservesPendingTombstoneAndDoesNotUnlikeOnSubsequentSync() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("remote_track_1"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Initial sync
        repo.syncAccountFavorites()
        assertEquals(1, localDataSource.getAllLikedSongs().size)

        // User likes new song locally while offline
        insertTestSong("offline_liked_song", isLiked = false)
        localDataSource.updateLiked(1, "offline_liked_song")
        assertEquals(2, localDataSource.getAllLikedSongs().size)

        // YouTube throws error when pushing to remote
        fakeYt.addToLikedError = IOException("503 Service Unavailable")

        // Sync runs, remote upload fails
        val syncResult = repo.syncAccountFavorites(force = true)
        assertTrue(syncResult.isSuccess)
        fakeYt.addToLikedError = null // recover

        // Song must STILL be liked locally!
        val songAfterFail = localDataSource.getSong("offline_liked_song")
        assertEquals(true, songAfterFail?.liked, "Failed remote upload must NEVER delete or unlike local favorite")

        // Tombstone must be preserved with isFavorite = true
        val tomb = localDataSource.getFavoriteTombstone(com.maxrave.domain.data.entities.FavoriteSyncType.SONG, "offline_liked_song")
        assertTrue(tomb != null && tomb.isFavorite, "Pending upload tombstone must be retained")

        // On subsequent sync, upload retries and succeeds
        val retryResult = repo.syncAccountFavorites(force = true)
        assertTrue(retryResult.isSuccess)
        assertTrue(fakeYt.addToLikedCalls.contains("offline_liked_song"))

        // Tombstone is cleared upon success, and song remains liked
        val tombAfterSuccess = localDataSource.getFavoriteTombstone(com.maxrave.domain.data.entities.FavoriteSyncType.SONG, "offline_liked_song")
        assertTrue(tombAfterSuccess == null, "Successful upload must clear pending tombstone")
        val songAfterSuccess = localDataSource.getSong("offline_liked_song")
        assertEquals(true, songAfterSuccess?.liked)
    }

    @Test
    fun sameMillisecondUnlike_isPropagatedWithoutRace() = runBlocking {
        setGoogleAccount("user@gmail.com")
        val fakeYt = FakeAccountYouTube().apply {
            remoteTracks.addAll(listOf("race_song"))
        }
        val dataStore = TestDataStoreManager(
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_abc",
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
        ).create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Initial sync
        repo.syncAccountFavorites()
        val lastSyncStr = dataStore.getString(KEY_LAST_FAVORITES_SYNC_TIME).first() ?: "0"
        val lastSyncMs = lastSyncStr.toLong()

        // User unlikes song with exact same millisecond timestamp as lastSync
        localDataSource.updateLiked(0, "race_song")
        localDataSource.insertFavoriteTombstone(
            com.maxrave.domain.data.entities.FavoriteSyncTombstoneEntity(
                type = com.maxrave.domain.data.entities.FavoriteSyncType.SONG,
                id = "race_song",
                isFavorite = false,
                updatedAt = lastSyncMs,
            )
        )

        // Sync runs
        val syncResult = repo.syncAccountFavorites(force = true)
        assertTrue(syncResult.isSuccess)
        assertTrue(fakeYt.removeFromLikedCalls.contains("race_song"), "Tombstone matching lastSync timestamp must still propagate unlike")

        val tombAfter = localDataSource.getFavoriteTombstone(com.maxrave.domain.data.entities.FavoriteSyncType.SONG, "race_song")
        assertTrue(tombAfter == null, "Tombstone must be cleared on successful remote unlike")
        assertEquals(false, localDataSource.getSong("race_song")?.liked)
    }

    private suspend fun insertTestSong(videoId: String, isLiked: Boolean = false) {
        val song = SongEntity(
            videoId = videoId,
            title = "Test Track $videoId",
            duration = "3:30",
            durationSeconds = 210,
            isAvailable = true,
            isExplicit = false,
            likeStatus = if (isLiked) "LIKE" else "INDIFFERENT",
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = null,
            resultType = null,
            liked = isLiked,
            downloadState = DownloadState.STATE_NOT_DOWNLOADED,
        )
        database.getDatabaseDao().insertSong(song)
    }

    private inner class FakeAccountYouTube : YouTube() {
        val remoteTracks: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        var remotePages: List<List<String>>? = null
        val addToLikedCalls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val removeFromLikedCalls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        var shouldThrow: Throwable? = null
        var addToLikedError: Throwable? = null
        var removeFromLikedError: Throwable? = null

        override suspend fun customQuery(
            browseId: String?,
            params: String?,
            continuation: String?,
            country: String?,
            setLogin: Boolean,
        ): Result<BrowseResponse> {
            shouldThrow?.let { return Result.failure(it) }

            val pages = remotePages
            if (pages != null) {
                if (browseId == "VLLM") {
                    val page1 = pages.getOrNull(0) ?: emptyList()
                    val nextToken = if (pages.size > 1) "page_token_1" else null
                    return Result.success(createInitialBrowseResponse(page1, nextToken))
                } else if (continuation != null) {
                    val pageIndex = continuation.removePrefix("page_token_").toIntOrNull() ?: 1
                    val pageItems = pages.getOrNull(pageIndex) ?: emptyList()
                    val nextToken = if (pageIndex + 1 < pages.size) "page_token_${pageIndex + 1}" else null
                    return Result.success(createContinuationBrowseResponse(pageItems, nextToken))
                }
            }

            if (browseId == "VLLM") {
                return Result.success(createInitialBrowseResponse(remoteTracks.toList()))
            }
            return Result.failure(Exception("Not found"))
        }

        override suspend fun addToLiked(mediaId: String): Result<Int> {
            addToLikedError?.let { return Result.failure(it) }
            shouldThrow?.let { return Result.failure(it) }
            addToLikedCalls.add(mediaId)
            if (!remoteTracks.contains(mediaId)) {
                remoteTracks.add(mediaId)
            }
            return Result.success(200)
        }

        override suspend fun removeFromLiked(mediaId: String): Result<Int> {
            removeFromLikedError?.let { return Result.failure(it) }
            shouldThrow?.let { return Result.failure(it) }
            removeFromLikedCalls.add(mediaId)
            remoteTracks.remove(mediaId)
            return Result.success(200)
        }
    }

    private class TestDataStoreManager(
        var combineLocalAndYouTubeLikedVal: String = DataStoreManager.FALSE,
        var loggedInVal: String = DataStoreManager.FALSE,
        var cookieVal: String = "",
        val stringMap: MutableMap<String, String> = mutableMapOf(),
    ) {
        fun create(): DataStoreManager {
            val handler = InvocationHandler { _, method, args ->
                when (method.name) {
                    "getCombineLocalAndYouTubeLiked" -> flowOf(combineLocalAndYouTubeLikedVal)
                    "getLoggedIn" -> flowOf(loggedInVal)
                    "getCookie" -> flowOf(cookieVal)
                    "setCombineLocalAndYouTubeLiked" -> {
                        val bool = args?.get(0) as? Boolean ?: false
                        combineLocalAndYouTubeLikedVal = if (bool) DataStoreManager.TRUE else DataStoreManager.FALSE
                        stringMap[DataStoreManager.KEY_COMBINE_LOCAL_AND_YOUTUBE_LIKED] = combineLocalAndYouTubeLikedVal
                        Unit
                    }
                    "setLoggedIn" -> {
                        val bool = args?.get(0) as? Boolean ?: false
                        loggedInVal = if (bool) DataStoreManager.TRUE else DataStoreManager.FALSE
                        Unit
                    }
                    "setCookie" -> {
                        cookieVal = args?.get(0) as? String ?: ""
                        Unit
                    }
                    "putString" -> {
                        val key = args?.get(0) as? String ?: ""
                        val value = args?.get(1) as? String ?: ""
                        stringMap[key] = value
                        Unit
                    }
                    "getString" -> {
                        val key = args?.get(0) as? String ?: ""
                        flowOf(stringMap[key])
                    }
                    "toString" -> "TestDataStoreManager"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> args?.get(0) === this
                    else -> {
                        if (method.returnType == Flow::class.java) {
                            flowOf("")
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
