package com.maxrave.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.kotlinytmusicscraper.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.ConnectException
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class YouTubeLikeSyncTest {

    private lateinit var tempDir: File
    private lateinit var database: MusicDatabase
    private lateinit var localDataSource: LocalDataSource

    private val testVideoId = "test_vid_123"

    @BeforeTest
    fun setUp() {
        runBlocking {
            tempDir = Files.createTempDirectory("yt-like-sync-test").toFile()
            val dbFile = File(tempDir, "test.db")
            database = Room
                .databaseBuilder<MusicDatabase>(dbFile.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addTypeConverter(Converters())
                .build()
            localDataSource = LocalDataSource(database.getDatabaseDao(), database)

            insertTestSong(testVideoId, isLiked = false)
        }
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

    @AfterTest
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun syncDisabled_likingOrUnlikingChangesLocalDb_noYouTubeCall() = runBlocking {
        val fakeYt = FakeYouTube()
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.FALSE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        // Like song
        repo.updateLikeStatus(testVideoId, 1)
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true, "Local DB must record song as liked")
        assertTrue(fakeYt.addToLikedCalls.isEmpty(), "No remote like call when sync is disabled")

        // Unlike song
        repo.updateLikeStatus(testVideoId, 0)
        assertFalse(localDataSource.getSong(testVideoId)?.liked == true, "Local DB must record song as unliked")
        assertTrue(fakeYt.removeFromLikedCalls.isEmpty(), "No remote unlike call when sync is disabled")
    }

    @Test
    fun syncEnabledAndLoggedIn_likingTriggersYouTubeLike() = runBlocking {
        val fakeYt = FakeYouTube()
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        repo.updateLikeStatus(testVideoId, 1)
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true, "Local DB must be liked")
        assertEquals(listOf(testVideoId), fakeYt.addToLikedCalls, "YouTube like request must be sent with videoId")
        assertTrue(fakeYt.removeFromLikedCalls.isEmpty())
    }

    @Test
    fun syncEnabledAndLoggedIn_unlikingTriggersYouTubeRemoveLike() = runBlocking {
        val fakeYt = FakeYouTube()
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        // Pre-set liked locally
        localDataSource.updateLiked(1, testVideoId)
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true)

        // Now unlike
        repo.updateLikeStatus(testVideoId, 0)
        assertFalse(localDataSource.getSong(testVideoId)?.liked == true, "Local DB must be unliked")
        assertEquals(listOf(testVideoId), fakeYt.removeFromLikedCalls, "YouTube remove-like request must be sent with videoId")
    }

    @Test
    fun loggedOut_localFavouriteStillWorksWithoutRemoteRequest() = runBlocking {
        val fakeYt = FakeYouTube()
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.FALSE,
            cookieVal = "",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        repo.updateLikeStatus(testVideoId, 1)
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true, "Local DB must be updated even when logged out")
        assertTrue(fakeYt.addToLikedCalls.isEmpty(), "No remote call must be attempted when logged out")

        repo.updateLikeStatus(testVideoId, 0)
        assertFalse(localDataSource.getSong(testVideoId)?.liked == true, "Local DB must be updated on unlike")
        assertTrue(fakeYt.removeFromLikedCalls.isEmpty(), "No remote call must be attempted on unlike when logged out")
    }

    @Test
    fun youtubeRequestFailure_localFavouriteRemainsUnchanged() = runBlocking {
        val fakeYt = FakeYouTube().apply {
            shouldThrow = IOException("Simulated network timeout/disconnect")
        }
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        // When liking: even if YouTube throws an exception, local favourite must remain saved!
        repo.updateLikeStatus(testVideoId, 1)
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true, "Local favorite must succeed independently of YouTube network failure")
        assertEquals(listOf(testVideoId), fakeYt.addToLikedCalls)

        // When unliking: even if YouTube fails, local unlike must remain!
        fakeYt.shouldThrow = IOException("Another network failure")
        repo.updateLikeStatus(testVideoId, 0)
        assertFalse(localDataSource.getSong(testVideoId)?.liked == true, "Local unlike must succeed independently of YouTube network failure")
        assertEquals(listOf(testVideoId), fakeYt.removeFromLikedCalls)
    }

    @Test
    fun httpFailureStatus_isHandledAndLocalFavouritePreserved() = runBlocking {
        val fakeYt = FakeYouTube().apply {
            simulatedStatus = 401 // Unauthorized / expired session
        }
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "expired_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        repo.updateLikeStatus(testVideoId, 1)
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true, "Local favorite must not be rolled back on HTTP 401")
        assertEquals(listOf(testVideoId), fakeYt.addToLikedCalls)
    }

    @Test
    fun existingAutoDownloadBehaviour_remainsIntact() = runBlocking {
        val fakeYt = FakeYouTube()
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
            autoDownloadLikedSongsVal = DataStoreManager.TRUE,
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        // Liking triggers auto-download
        repo.updateLikeStatus(testVideoId, 1)
        assertEquals(listOf(testVideoId), fakeDownloads.downloadedTracks, "Auto-download handler must be invoked on like")

        // Unliking does NOT trigger auto-download
        repo.updateLikeStatus(testVideoId, 0)
        assertEquals(1, fakeDownloads.downloadedTracks.size, "Unliking must never queue new downloads")
    }

    @Test
    fun blankOrInvalidVideoId_doesNotTriggerYouTubeRequest() = runBlocking {
        val fakeYt = FakeYouTube()
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        repo.updateLikeStatus("", 1)
        repo.updateLikeStatus("   ", 1)
        assertTrue(fakeYt.addToLikedCalls.isEmpty(), "Blank video ID must not trigger a YouTube like request")
    }

    @Test
    fun rapidLikeUnlikeLike_onSameSong_resultsInFinalLikedStateAndCorrectCalls() = runBlocking {
        val fakeYt = FakeYouTube()
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        // Rapid sequence: Like -> Unlike -> Like
        repo.updateLikeStatus(testVideoId, 1)
        repo.updateLikeStatus(testVideoId, 0)
        repo.updateLikeStatus(testVideoId, 1)

        assertTrue(localDataSource.getSong(testVideoId)?.liked == true, "Final local DB state must be liked")
        assertEquals(2, fakeYt.addToLikedCalls.size, "Should have 2 addToLiked calls")
        assertEquals(1, fakeYt.removeFromLikedCalls.size, "Should have 1 removeFromLiked call")
        assertEquals(testVideoId, fakeYt.addToLikedCalls.last(), "Last executed like call was for test track")
    }

    @Test
    fun obsoleteQueuedRequest_isSkippedWhenLocalStateChanges() = runBlocking {
        insertTestSong("holder_track")
        val lockHeld = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()

        val fakeYt = FakeYouTube().apply {
            beforeCallHook = { mediaId ->
                if (mediaId == "holder_track") {
                    lockHeld.complete(Unit)
                    releaseHolder.await()
                }
            }
        }
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        // 1. Start holder track which holds the mutex inside addToLiked
        val holderJob = launch {
            repo.updateLikeStatus("holder_track", 1)
        }
        lockHeld.await() // Mutex is now held by holderJob

        // 2. Queue a like for testVideoId while mutex is locked
        val likeJob = launch {
            repo.updateLikeStatus(testVideoId, 1)
        }

        // 3. User immediately unlikes testVideoId (updates DB immediately to 0, then queues for mutex)
        val unlikeJob = launch {
            repo.updateLikeStatus(testVideoId, 0)
        }

        // 4. Release holder job so queued jobs proceed
        releaseHolder.complete(Unit)
        holderJob.join()
        likeJob.join()
        unlikeJob.join()

        // Local DB must reflect the final unliked state
        assertFalse(localDataSource.getSong(testVideoId)?.liked == true, "Final local DB state must be unliked")

        // The queued like for testVideoId was superseded and should have been skipped!
        assertFalse(
            fakeYt.addToLikedCalls.contains(testVideoId),
            "Obsolete queued addToLiked request for $testVideoId must be skipped",
        )
        // Only the unlike request should have run for testVideoId
        assertEquals(listOf(testVideoId), fakeYt.removeFromLikedCalls, "removeFromLiked must run for $testVideoId")
    }

    @Test
    fun multipleSongsLikedRapidly_areProcessedConcurrentlyAndRecorded() = runBlocking {
        insertTestSong("song_A")
        insertTestSong("song_B")
        insertTestSong("song_C")

        val fakeYt = FakeYouTube()
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        val songIds = listOf("song_A", "song_B", "song_C")
        val jobs = songIds.map { songId ->
            launch(Dispatchers.IO) {
                repo.updateLikeStatus(songId, 1)
            }
        }
        jobs.joinAll()

        // All 3 songs must be marked liked in local DB
        for (songId in songIds) {
            assertTrue(localDataSource.getSong(songId)?.liked == true, "Song $songId must be liked in local DB")
        }
        // All 3 remote calls must have been made
        assertEquals(songIds.toSet(), fakeYt.addToLikedCalls.toSet(), "All 3 songs must receive YouTube like calls")
    }

    @Test
    fun coroutineCancellation_isPropagatedCorrectly() = runBlocking {
        val fakeYt = FakeYouTube().apply {
            shouldThrow = CancellationException("Simulated coroutine cancellation")
        }
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        try {
            repo.updateLikeStatus(testVideoId, 1)
            fail("Expected CancellationException to be re-thrown")
        } catch (e: CancellationException) {
            assertEquals("Simulated coroutine cancellation", e.message)
        }

        // Even though cancellation occurred during remote sync, local DB was already updated
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true, "Local DB update occurs before sync and is preserved")
    }

    @Test
    fun logoutReset_turnsOffCombineLocalAndYouTubeLiked() = runBlocking {
        val fakeYt = FakeYouTube()
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
        )
        val dataStore = testDataStore.create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        // Verify initial state: sync is enabled
        dataStore.setCombineLocalAndYouTubeLiked(true)
        dataStore.setLoggedIn(true)
        dataStore.setCookie("valid_cookie", null)

        // Simulate logout teardown (same actions performed in SettingsViewModel.setUsedAccount(null) and logOutAllYouTube())
        dataStore.setCombineLocalAndYouTubeLiked(false)
        dataStore.setLoggedIn(false)
        dataStore.setCookie("", null)

        // Post-logout like action
        repo.updateLikeStatus(testVideoId, 1)

        // Local DB updated, but remote request skipped
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true, "Local DB is updated")
        assertTrue(fakeYt.addToLikedCalls.isEmpty(), "No remote call must occur after logout resets the sync preference")
    }

    @Test
    fun http401And403Responses_doNotCrashAppAndLocalFavouriteIsPreserved() = runBlocking {
        val fakeYt = FakeYouTube()
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        // 401 Unauthorized
        fakeYt.simulatedStatus = 401
        repo.updateLikeStatus(testVideoId, 1)
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true, "Local favourite preserved on 401")
        assertEquals(1, fakeYt.addToLikedCalls.size)

        // 403 Forbidden
        fakeYt.simulatedStatus = 403
        repo.updateLikeStatus(testVideoId, 0)
        assertFalse(localDataSource.getSong(testVideoId)?.liked == true, "Local unlike preserved on 403")
        assertEquals(1, fakeYt.removeFromLikedCalls.size)
    }

    @Test
    fun offlineNetworkFailure_preservesLocalDbAndDoesNotRetryRepeatedly() = runBlocking {
        val fakeYt = FakeYouTube().apply {
            shouldThrow = ConnectException("Failed to connect to /142.250.190.46:443 (network offline)")
        }
        val fakeDownloads = FakeDownloadHandler()
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_sapisid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
        )

        repo.updateLikeStatus(testVideoId, 1)
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true, "Local DB favourite is preserved offline")
        assertEquals(1, fakeYt.addToLikedCalls.size, "Single attempt made; not queued for repeated automatic retry")
    }

    // Test Doubles
    private class FakeYouTube : YouTube() {
        val addToLikedCalls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val removeFromLikedCalls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        var simulatedStatus: Int = 200
        var shouldThrow: Throwable? = null
        var beforeCallHook: (suspend (String) -> Unit)? = null

        override suspend fun addToLiked(mediaId: String): Result<Int> {
            beforeCallHook?.invoke(mediaId)
            addToLikedCalls.add(mediaId)
            shouldThrow?.let { return Result.failure(it) }
            return Result.success(simulatedStatus)
        }

        override suspend fun removeFromLiked(mediaId: String): Result<Int> {
            beforeCallHook?.invoke(mediaId)
            removeFromLikedCalls.add(mediaId)
            shouldThrow?.let { return Result.failure(it) }
            return Result.success(simulatedStatus)
        }
    }

    private class FakeDownloadHandler : DownloadHandler {
        val downloadedTracks = mutableListOf<String>()

        override suspend fun downloadTrack(videoId: String, title: String, thumbnail: String) {
            downloadedTracks.add(videoId)
        }

        override fun removeDownload(videoId: String) {}
        override fun removeAllDownloads() {}

        override val downloads: StateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>>
            get() = MutableStateFlow(emptyMap())

        override val downloadTask: StateFlow<Map<String, Int>>
            get() = MutableStateFlow(emptyMap())
    }

    private class TestDataStoreManager(
        var combineLocalAndYouTubeLikedVal: String = DataStoreManager.FALSE,
        var loggedInVal: String = DataStoreManager.FALSE,
        var cookieVal: String = "",
        var autoDownloadLikedSongsVal: String = DataStoreManager.FALSE,
    ) {
        fun create(): DataStoreManager {
            val handler = InvocationHandler { _, method, args ->
                when (method.name) {
                    "getCombineLocalAndYouTubeLiked" -> flowOf(combineLocalAndYouTubeLikedVal)
                    "getLoggedIn" -> flowOf(loggedInVal)
                    "getCookie" -> flowOf(cookieVal)
                    "getAutoDownloadLikedSongs" -> flowOf(autoDownloadLikedSongsVal)
                    "setCombineLocalAndYouTubeLiked" -> {
                        val bool = args?.get(0) as? Boolean ?: false
                        combineLocalAndYouTubeLikedVal = if (bool) DataStoreManager.TRUE else DataStoreManager.FALSE
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
