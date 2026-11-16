package com.maxrave.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.favoritesync.YouTubeSyncStatus
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.kotlinytmusicscraper.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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

    private suspend fun setGoogleAccount(email: String, pageId: String? = null) {
        val account = GoogleAccountEntity(
            email = email,
            name = "Test User",
            thumbnailUrl = "",
            isUsed = true,
            pageId = pageId,
        )
        database.getDatabaseDao().insertGoogleAccount(account)
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
        val testMutex = TestMutex()
        val holderInLock = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()

        val fakeYt = FakeYouTube().apply {
            beforeCallHook = { mediaId ->
                if (mediaId == "holder_track") {
                    holderInLock.complete(Unit)
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
            youtubeLikeSyncMutex = testMutex,
        )

        // 1. Start holder track which holds the mutex inside addToLiked
        val holderJob = launch {
            repo.updateLikeStatus("holder_track", 1)
        }
        testMutex.lockAttemptChannel.receive() // holder requested lock
        holderInLock.await()                   // holder acquired lock and is executing inside withLock

        // 2. Queue a like for testVideoId while mutex is locked
        val likeJob = launch {
            repo.updateLikeStatus(testVideoId, 1)
        }
        testMutex.lockAttemptChannel.receive() // testVideoId has completed pre-checks and is waiting on Mutex

        // 3. User immediately unlikes testVideoId (updates DB immediately to 0, then queues for mutex)
        val unlikeJob = launch {
            repo.updateLikeStatus(testVideoId, 0)
        }
        testMutex.lockAttemptChannel.receive() // unlikeJob has completed pre-checks and is waiting on Mutex

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

    @Test
    fun queuedRequest_syncDisabledWhileWaiting_isSkippedWhenLockAcquired() = runBlocking {
        insertTestSong("holder_track")
        val testMutex = TestMutex()
        val holderInLock = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()

        val fakeYt = FakeYouTube().apply {
            beforeCallHook = { mediaId ->
                if (mediaId == "holder_track") {
                    holderInLock.complete(Unit)
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
        val dataStore = testDataStore.create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
            youtubeLikeSyncMutex = testMutex,
        )

        // 1. Holder starts and acquires the Mutex
        val holderJob = launch {
            repo.updateLikeStatus("holder_track", 1)
        }
        testMutex.lockAttemptChannel.receive() // holder requested lock
        holderInLock.await()                   // holder acquired lock and is executing inside withLock

        // 2. Queue the second request for testVideoId while Mutex is held
        val likeJob = launch {
            repo.updateLikeStatus(testVideoId, 1)
        }
        // Explicitly synchronize: wait until second request finishes pre-checks and reaches lock()
        testMutex.lockAttemptChannel.receive()

        // 3. At this exact point, second request is verified waiting on the Mutex.
        // User now disables synchronisation.
        dataStore.setCombineLocalAndYouTubeLiked(false)

        // 4. Release holder to allow second request to acquire the Mutex
        releaseHolder.complete(Unit)
        holderJob.join()
        likeJob.join()

        // 5. Local favourite must remain saved
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true, "Local favourite must be preserved")

        // 6. Verify second request aborted inside-Mutex without calling YouTube API
        assertFalse(
            fakeYt.addToLikedCalls.contains(testVideoId),
            "Queued like request must be skipped when sync was disabled while waiting",
        )
    }

    @Test
    fun queuedRequest_logoutWhileWaiting_isSkippedWhenLockAcquired() = runBlocking {
        insertTestSong("holder_track")
        val testMutex = TestMutex()
        val holderInLock = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()

        val fakeYt = FakeYouTube().apply {
            beforeCallHook = { mediaId ->
                if (mediaId == "holder_track") {
                    holderInLock.complete(Unit)
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
        val dataStore = testDataStore.create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
            downloadHandler = fakeDownloads,
            youtubeLikeSyncMutex = testMutex,
        )

        // 1. Holder starts and acquires the Mutex
        val holderJob = launch {
            repo.updateLikeStatus("holder_track", 1)
        }
        testMutex.lockAttemptChannel.receive()
        holderInLock.await()

        // 2. Queue the second request for testVideoId while Mutex is held
        val likeJob = launch {
            repo.updateLikeStatus(testVideoId, 1)
        }
        // Explicitly synchronize: wait until second request finishes pre-checks and reaches lock()
        testMutex.lockAttemptChannel.receive()

        // 3. User logs out while second request is waiting on the Mutex
        dataStore.setLoggedIn(false)
        dataStore.setCookie("", null)
        dataStore.setCombineLocalAndYouTubeLiked(false)

        // 4. Release holder to allow second request to acquire the Mutex
        releaseHolder.complete(Unit)
        holderJob.join()
        likeJob.join()

        // 5. Local favourite must remain saved
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true, "Local favourite must be preserved")

        // 6. Verify second request aborted inside-Mutex without calling YouTube API
        assertFalse(
            fakeYt.addToLikedCalls.contains(testVideoId),
            "Queued like request must be skipped when logout occurs while waiting",
        )
    }

    @Test
    fun offlineLike_queuesOperationAndFlushesOnReconnect() = runBlocking {
        setGoogleAccount("offline_user@gmail.com")
        val fakeYt = FakeYouTube().apply {
            shouldThrow = ConnectException("Offline")
        }
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Offline like
        repo.updateLikeStatus(testVideoId, 1)

        // 1. Local favorite preserved
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true)

        // 2. Pending operation in SQLite
        val pendingList = repo.getPendingFavoriteOperations()
        assertEquals(1, pendingList.size)
        assertEquals(testVideoId, pendingList.first().videoId)
        assertEquals(1, pendingList.first().targetState)

        // 3. Status is WaitingForConnection
        assertEquals(YouTubeSyncStatus.WaitingForConnection, repo.youTubeSyncStatus.value)

        // 4. Reconnect and flush
        fakeYt.shouldThrow = null
        fakeYt.addToLikedCalls.clear()
        val flushResult = repo.retryPendingSync()
        assertTrue(flushResult.isSuccess)

        // 5. Remote API was called
        assertEquals(listOf(testVideoId), fakeYt.addToLikedCalls)
        // 6. Queue is now empty and status Synced
        assertEquals(0, repo.getPendingFavoriteCount())
        assertEquals(YouTubeSyncStatus.Synced, repo.youTubeSyncStatus.value)
    }

    @Test
    fun offlineUnlike_queuesOperationAndFlushesOnReconnect() = runBlocking {
        setGoogleAccount("offline_user@gmail.com")
        // Start liked
        localDataSource.updateLiked(1, testVideoId)

        val fakeYt = FakeYouTube().apply {
            shouldThrow = IOException("Connection timed out")
        }
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Offline unlike
        repo.updateLikeStatus(testVideoId, 0)

        // Local unliked
        assertFalse(localDataSource.getSong(testVideoId)?.liked == true)

        // Pending operation in SQLite
        val pendingList = repo.getPendingFavoriteOperations()
        assertEquals(1, pendingList.size)
        assertEquals(testVideoId, pendingList.first().videoId)
        assertEquals(0, pendingList.first().targetState)

        // Status is WaitingForConnection
        assertEquals(YouTubeSyncStatus.WaitingForConnection, repo.youTubeSyncStatus.value)

        // Reconnect and flush
        fakeYt.shouldThrow = null
        fakeYt.removeFromLikedCalls.clear()
        val flushResult = repo.flushPendingYouTubeLikes()
        assertTrue(flushResult.isSuccess)

        assertEquals(listOf(testVideoId), fakeYt.removeFromLikedCalls)
        assertEquals(0, repo.getPendingFavoriteCount())
        assertEquals(YouTubeSyncStatus.Synced, repo.youTubeSyncStatus.value)
    }

    @Test
    fun applicationRestart_retainsPendingOperationsInDatabaseAndFlushesOnStart() = runBlocking {
        setGoogleAccount("persist_user@gmail.com")
        val fakeYt = FakeYouTube().apply {
            shouldThrow = ConnectException("Offline during shutdown")
        }
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_cookie",
        )
        val repo1 = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Like offline in first app session
        repo1.updateLikeStatus(testVideoId, 1)
        assertEquals(1, repo1.getPendingFavoriteCount())

        // Simulate app shutdown: close database
        val dbFilePath = File(tempDir, "test.db").absolutePath
        database.close()

        // Simulate app restart: open database afresh from the same SQLite file
        val restartedDb = Room
            .databaseBuilder<MusicDatabase>(dbFilePath)
            .setDriver(BundledSQLiteDriver())
            .addTypeConverter(Converters())
            .build()
        val restartedLocalDataSource = LocalDataSource(restartedDb.getDatabaseDao(), restartedDb)

        // Verify pending operation survived SQLite restart
        val restoredPending = restartedLocalDataSource.getAllYouTubeFavoritePending()
        assertEquals(1, restoredPending.size, "Pending operation must persist across app restart in SQLite")
        assertEquals(testVideoId, restoredPending.first().videoId)
        assertEquals(1, restoredPending.first().targetState)

        // Network reconnected on new app session
        val fakeYtRestart = FakeYouTube()
        val repo2 = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = restartedLocalDataSource,
            youTube = fakeYtRestart,
        )

        // Flush on start
        val flushResult = repo2.flushPendingYouTubeLikes()
        assertTrue(flushResult.isSuccess)
        assertEquals(listOf(testVideoId), fakeYtRestart.addToLikedCalls)
        assertEquals(0, repo2.getPendingFavoriteCount())
        assertEquals(YouTubeSyncStatus.Synced, repo2.youTubeSyncStatus.value)

        restartedDb.close()
    }

    @Test
    fun expiredAuthenticationCookies_transitionsToAuthenticationRequiredWithoutDataLoss() = runBlocking {
        setGoogleAccount("auth_user@gmail.com")
        val fakeYt = FakeYouTube().apply {
            simulatedStatus = 401
        }
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "expired_cookie",
        )
        val dataStore = testDataStore.create()
        val repo = SongRepositoryImpl(
            dataStoreManager = dataStore,
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // Like with expired cookie
        repo.updateLikeStatus(testVideoId, 1)

        // Local song is NOT lost
        assertTrue(localDataSource.getSong(testVideoId)?.liked == true)

        // Status is AuthenticationRequired
        assertEquals(YouTubeSyncStatus.AuthenticationRequired, repo.youTubeSyncStatus.value)
        assertEquals(1, repo.getPendingFavoriteCount())

        // User logs in again / updates cookie
        dataStore.setCookie("fresh_sapisid_cookie", null)
        fakeYt.simulatedStatus = 200
        fakeYt.addToLikedCalls.clear()

        val retryResult = repo.retryPendingSync()
        assertTrue(retryResult.isSuccess)
        assertEquals(listOf(testVideoId), fakeYt.addToLikedCalls)
        assertEquals(0, repo.getPendingFavoriteCount())
        assertEquals(YouTubeSyncStatus.Synced, repo.youTubeSyncStatus.value)
    }

    @Test
    fun accountSwitching_isolatesPendingOperationsByAccountId() = runBlocking {
        // Account A logs in
        setGoogleAccount("accountA@gmail.com", pageId = null)
        val fakeYt = FakeYouTube().apply {
            shouldThrow = ConnectException("Offline")
        }
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "cookie_A",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        insertTestSong("song_A")
        insertTestSong("song_B")

        // Account A likes song_A while offline
        repo.updateLikeStatus("song_A", 1)
        val accountAId = "accounta@gmail.com#"
        val pendingA = localDataSource.getYouTubeFavoritePendingForAccount(accountAId)
        assertEquals(1, pendingA.size)
        assertEquals("song_A", pendingA.first().videoId)

        // Switch to Account B
        localDataSource.deleteGoogleAccount("accountA@gmail.com")
        setGoogleAccount("accountB@gmail.com", pageId = "brand_123")
        val accountBId = "accountb@gmail.com#brand_123"

        // Account B pending queue must be empty!
        assertEquals(0, localDataSource.getYouTubeFavoritePendingForAccount(accountBId).size)

        // Account B likes song_B while offline
        repo.updateLikeStatus("song_B", 1)
        val pendingB = localDataSource.getYouTubeFavoritePendingForAccount(accountBId)
        assertEquals(1, pendingB.size)
        assertEquals("song_B", pendingB.first().videoId)

        // Account A's pending operation still intact and isolated in database
        val pendingAStillThere = localDataSource.getYouTubeFavoritePendingForAccount(accountAId)
        assertEquals(1, pendingAStillThere.size)
        assertEquals("song_A", pendingAStillThere.first().videoId)

        // Now Account B reconnects and flushes
        fakeYt.shouldThrow = null
        fakeYt.addToLikedCalls.clear()
        repo.flushPendingYouTubeLikes()

        // ONLY song_B was synced! song_A was NOT sent with Account B's cookies!
        assertEquals(listOf("song_B"), fakeYt.addToLikedCalls)
        assertEquals(0, localDataSource.getYouTubeFavoritePendingForAccount(accountBId).size)

        // Account A's item remains preserved in DB waiting for Account A
        assertEquals(1, localDataSource.getYouTubeFavoritePendingForAccount(accountAId).size)

        // Switch back to Account A and verify Account A flushes song_A
        localDataSource.deleteGoogleAccount("accountB@gmail.com")
        setGoogleAccount("accountA@gmail.com", pageId = null)
        fakeYt.addToLikedCalls.clear()
        repo.flushPendingYouTubeLikes()
        assertEquals(listOf("song_A"), fakeYt.addToLikedCalls)
        assertEquals(0, localDataSource.getYouTubeFavoritePendingForAccount(accountAId).size)
    }

    @Test
    fun rapidSuperSededLikeAndUnlike_flushesOnlyLatestDesiredState() = runBlocking {
        setGoogleAccount("supersede_user@gmail.com")
        val fakeYt = FakeYouTube().apply {
            shouldThrow = ConnectException("Offline")
        }
        val testDataStore = TestDataStoreManager(
            combineLocalAndYouTubeLikedVal = DataStoreManager.TRUE,
            loggedInVal = DataStoreManager.TRUE,
            cookieVal = "valid_cookie",
        )
        val repo = SongRepositoryImpl(
            dataStoreManager = testDataStore.create(),
            localDataSource = localDataSource,
            youTube = fakeYt,
        )

        // User likes, then unlikes while offline
        repo.updateLikeStatus(testVideoId, 1)
        repo.updateLikeStatus(testVideoId, 0)

        // In DB, only 1 pending record should exist for this track with targetState = 0
        val pending = repo.getPendingFavoriteOperations()
        assertEquals(1, pending.size)
        assertEquals(0, pending.first().targetState)
        assertFalse(localDataSource.getSong(testVideoId)?.liked == true)

        // Reconnect and flush
        fakeYt.shouldThrow = null
        fakeYt.addToLikedCalls.clear()
        fakeYt.removeFromLikedCalls.clear()

        repo.flushPendingYouTubeLikes()

        // Only removeFromLiked was called, addToLiked was NOT called
        assertTrue(fakeYt.addToLikedCalls.isEmpty(), "Superseded like call must not be made")
        assertEquals(listOf(testVideoId), fakeYt.removeFromLikedCalls)
        assertEquals(0, repo.getPendingFavoriteCount())
    }

    // Test Doubles
    private class TestMutex(private val delegate: Mutex = Mutex()) : Mutex by delegate {
        val lockAttemptChannel = Channel<Unit>(Channel.UNLIMITED)

        override suspend fun lock(owner: Any?) {
            lockAttemptChannel.send(Unit)
            delegate.lock(owner)
        }
    }

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
