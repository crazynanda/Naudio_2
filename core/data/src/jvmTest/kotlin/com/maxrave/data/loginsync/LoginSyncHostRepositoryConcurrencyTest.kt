package com.maxrave.data.loginsync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.favoritesync.FavoriteSongDto
import com.maxrave.data.favoritesync.FavoriteSyncStore
import com.maxrave.data.favoritesync.FavoritesSyncBundle
import com.maxrave.data.favoritesync.favoritesSyncJson
import com.maxrave.domain.data.model.favoritesync.FavoritesSyncResult
import com.maxrave.domain.data.model.loginsync.LoginSyncHostState
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.CommonRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.simpmusic.loginsync.LoginSyncClient
import org.simpmusic.loginsync.LoginSyncInvite
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private inline fun <reified T> fakeProxy(): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> false
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> 0
            java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
            String::class.java -> ""
            kotlinx.coroutines.flow.Flow::class.java -> kotlinx.coroutines.flow.flowOf("")
            List::class.java -> emptyList<Any>()
            Set::class.java -> emptySet<Any>()
            else -> null
        }
    } as T

class LoginSyncHostRepositoryConcurrencyTest {
    private lateinit var tempDir: File
    private lateinit var database: MusicDatabase
    private lateinit var syncStore: FavoriteSyncStore
    private lateinit var localDataSource: LocalDataSource
    private lateinit var repository: LoginSyncHostRepositoryImpl
    private val restoreGateStarted = CompletableDeferred<Unit>()
    private val canCompleteRestore = CompletableDeferred<Unit>()
    private var gateRestore = false

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("host-repo-concurrency-test").toFile()
        val dbFile = File(tempDir, "host.db")
        database = Room.databaseBuilder<MusicDatabase>(dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addTypeConverter(Converters())
            .build()
        syncStore = FavoriteSyncStore(database.getDatabaseDao())

        val fakeDataStore = fakeProxy<DataStoreManager>()
        val fakeCommonRepo = fakeProxy<CommonRepository>()
        localDataSource = LocalDataSource(database.getDatabaseDao(), database)
        val fakeLoginStore = object : LoginSyncStore(fakeDataStore, localDataSource, fakeCommonRepo) {
            override suspend fun restore(bundle: LoginBundle): List<LoginSyncService> {
                if (gateRestore) {
                    restoreGateStarted.complete(Unit)
                    canCompleteRestore.await()
                }
                return buildList {
                    if (bundle.youtube != null) add(LoginSyncService.YOUTUBE)
                    if (bundle.spotifySpDc != null) add(LoginSyncService.SPOTIFY)
                }
            }
        }

        repository = LoginSyncHostRepositoryImpl(
            store = fakeLoginStore,
            favoriteSyncStore = syncStore,
            allowLoopbackFallback = true,
        )
    }

    @AfterTest
    fun tearDown() {
        repository.stop()
        database.close()
        tempDir.deleteRecursively()
    }

    /**
     * Scenario 1: CANCEL arrives while a favorites batch is being merged.
     * The desktop session must NOT reset to waiting while the merge is running.
     */
    @Test
    fun cancelArrivesWhileFavoritesBatchIsMerging() = runBlocking {
        repository.start()
        val waitingState = repository.state.value as LoginSyncHostState.Waiting
        val invite = assertNotNull(LoginSyncInvite.parse(waitingState.invite))

        val favClient = LoginSyncClient()
        val cancelClient = LoginSyncClient()
        favClient.connect(invite)
        cancelClient.connect(invite)

        // Add 50 songs in database to simulate non-trivial sync
        val incomingSongs = (1..50).map {
            FavoriteSongDto(videoId = "vid_$it", title = "Song $it", favoriteAtEpochMs = 1000L + it)
        }
        val bundle = FavoritesSyncBundle(
            songs = incomingSongs,
            hasMore = false,
            requestBatchIndex = 0,
        )
        val payload = favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), bundle).encodeToByteArray()

        val favTask = async(Dispatchers.IO) {
            favClient.sendFavorites(payload)
        }

        // Concurrently send CANCEL
        val cancelTask = async(Dispatchers.IO) {
            delay(10) // Small delay to let favorites hit the server
            cancelClient.cancelSync()
        }

        val replyBytes = favTask.await()
        assertTrue(replyBytes.isNotEmpty())

        cancelTask.await()

        // Since the favorites exchange completed (hasMore = false on both sides),
        // CANCEL arriving after completion must retain the FavoritesSynced state!
        val finalState = repository.state.value
        assertTrue(finalState is LoginSyncHostState.FavoritesSynced, "Final state must remain FavoritesSynced")
        assertEquals(50, finalState.result.songsAddedOrUpdated)
    }

    /**
     * Scenario 2: CANCEL arrives between favorites batch preparation and reply completion.
     */
    @Test
    fun cancelArrivesBetweenFavoritesBatchPreparationAndReplyCompletion() = runBlocking {
        repository.start()
        val waitingState = repository.state.value as LoginSyncHostState.Waiting
        val invite = assertNotNull(LoginSyncInvite.parse(waitingState.invite))

        val favClient = LoginSyncClient()
        val cancelClient = LoginSyncClient()
        favClient.connect(invite)
        cancelClient.connect(invite)

        val bundle = FavoritesSyncBundle(
            songs = listOf(FavoriteSongDto(videoId = "prep_song", title = "Prep Song", favoriteAtEpochMs = 5000L)),
            hasMore = false,
            requestBatchIndex = 0,
        )
        val payload = favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), bundle).encodeToByteArray()

        val favTask = async(Dispatchers.IO) {
            favClient.sendFavorites(payload)
        }
        val cancelTask = async(Dispatchers.IO) {
            cancelClient.cancelSync()
        }

        val reply = favTask.await()
        assertTrue(reply.isNotEmpty())
        cancelTask.await()

        assertTrue(repository.state.value is LoginSyncHostState.FavoritesSynced)
    }

    /**
     * Scenario 3: CANCEL arrives while a login payload is being restored.
     */
    @Test
    fun cancelArrivesWhileLoginPayloadIsBeingRestored() = runBlocking {
        gateRestore = true
        repository.start()
        val waitingState = repository.state.value as LoginSyncHostState.Waiting
        val invite = assertNotNull(LoginSyncInvite.parse(waitingState.invite))

        val loginClient = LoginSyncClient()
        val cancelClient = LoginSyncClient()
        loginClient.connect(invite)
        cancelClient.connect(invite)

        val loginPayload = loginSyncJson.encodeToString(
            LoginBundle.serializer(),
            LoginBundle(youtube = YouTubeLogin(accounts = emptyList(), cookie = "token", pageId = null, authUser = 0, dataSyncId = "")),
        ).encodeToByteArray()

        val loginTask = async(Dispatchers.IO) {
            loginClient.send(loginPayload)
        }

        // Wait until login restore has begun
        restoreGateStarted.await()

        // Send cancel concurrently while restore is suspended
        val cancelTask = async(Dispatchers.IO) {
            cancelClient.cancelSync()
        }

        delay(50)
        // Verify repository is still in Connected state, not reset to Waiting
        assertTrue(repository.state.value is LoginSyncHostState.Connected)

        // Release the restore gate
        canCompleteRestore.complete(Unit)

        val ack = loginTask.await()
        assertTrue(ack.isNotEmpty())

        cancelTask.await()

        // Login finished and delivered, so CANCEL retains Done state
        val finalState = repository.state.value
        assertTrue(finalState is LoginSyncHostState.Done, "State must remain Done after completed login")
        assertEquals(listOf(LoginSyncService.YOUTUBE), finalState.services)
    }

    /**
     * Scenario 4: CANCEL arrives after successful favorites completion.
     */
    @Test
    fun cancelArrivesAfterSuccessfulFavoritesCompletion() = runBlocking {
        repository.start()
        val waitingState = repository.state.value as LoginSyncHostState.Waiting
        val invite = assertNotNull(LoginSyncInvite.parse(waitingState.invite))

        val client = LoginSyncClient()
        client.connect(invite)

        val bundle = FavoritesSyncBundle(
            songs = listOf(FavoriteSongDto(videoId = "s4", title = "S4", favoriteAtEpochMs = 1234L)),
            hasMore = false,
            requestBatchIndex = 0,
        )
        val payload = favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), bundle).encodeToByteArray()
        val reply = client.sendFavorites(payload)
        assertTrue(reply.isNotEmpty())
        assertTrue(repository.state.value is LoginSyncHostState.FavoritesSynced)

        // Phone disconnects/cancels after completion
        client.cancelSync()

        // State remains FavoritesSynced
        val stateAfter = repository.state.value
        assertTrue(stateAfter is LoginSyncHostState.FavoritesSynced)
        assertEquals(1, stateAfter.result.songsAddedOrUpdated)
    }

    /**
     * Scenario 5: CANCEL arrives after successful login delivery.
     */
    @Test
    fun cancelArrivesAfterSuccessfulLoginDelivery() = runBlocking {
        repository.start()
        val waitingState = repository.state.value as LoginSyncHostState.Waiting
        val invite = assertNotNull(LoginSyncInvite.parse(waitingState.invite))

        val client = LoginSyncClient()
        client.connect(invite)

        val loginPayload = loginSyncJson.encodeToString(
            LoginBundle.serializer(),
            LoginBundle(spotifySpDc = "spotify_cookie_123"),
        ).encodeToByteArray()

        val ack = client.send(loginPayload)
        assertTrue(ack.isNotEmpty())
        assertTrue(repository.state.value is LoginSyncHostState.Done)

        // Client cancels after delivery
        client.cancelSync()

        val stateAfter = repository.state.value
        assertTrue(stateAfter is LoginSyncHostState.Done)
        assertEquals(listOf(LoginSyncService.SPOTIFY), stateAfter.services)
    }

    /**
     * Scenario 6: A cancelled partial multi-batch exchange remains retryable.
     */
    @Test
    fun cancelledPartialMultiBatchExchangeRemainsRetryable() = runBlocking {
        repository.start()
        val waitingState = repository.state.value as LoginSyncHostState.Waiting
        val invite = assertNotNull(LoginSyncInvite.parse(waitingState.invite))

        val client = LoginSyncClient()
        client.connect(invite)

        // Batch 0 of multi-batch (hasMore = true)
        val batch0 = FavoritesSyncBundle(
            songs = listOf(FavoriteSongDto(videoId = "multi_1", title = "Multi 1", favoriteAtEpochMs = 1000L)),
            batchIndex = 0,
            batchTotal = 2,
            hasMore = true,
            requestBatchIndex = 0,
        )
        val payload0 = favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), batch0).encodeToByteArray()
        val reply0 = client.sendFavorites(payload0)
        assertTrue(reply0.isNotEmpty())
        assertTrue(repository.state.value is LoginSyncHostState.Connected)

        // Client cancels mid-exchange
        client.cancelSync()

        // Incomplete exchange resets to Waiting
        assertTrue(repository.state.value is LoginSyncHostState.Waiting)

        // Client retries entire exchange from batch 0
        val retryClient = LoginSyncClient()
        retryClient.connect(invite)
        assertTrue(repository.state.value is LoginSyncHostState.Connected)

        retryClient.sendFavorites(payload0)
        assertTrue(repository.state.value is LoginSyncHostState.Connected)

        // Batch 1 completes the exchange (hasMore = false)
        val batch1 = FavoritesSyncBundle(
            songs = listOf(FavoriteSongDto(videoId = "multi_2", title = "Multi 2", favoriteAtEpochMs = 2000L)),
            batchIndex = 1,
            batchTotal = 2,
            hasMore = false,
            requestBatchIndex = 1,
        )
        val payload1 = favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), batch1).encodeToByteArray()
        retryClient.sendFavorites(payload1)

        // Now exchange is complete. Because multi_1 was already applied in the first attempt,
        // retry is idempotent: only the newly arriving multi_2 increments songsAddedOrUpdated.
        val finalState = repository.state.value
        assertTrue(finalState is LoginSyncHostState.FavoritesSynced)
        assertEquals(1, finalState.result.songsAddedOrUpdated)
        assertNotNull(database.getDatabaseDao().getSong("multi_1"))
        assertNotNull(database.getDatabaseDao().getSong("multi_2"))

        // Disconnecting after completion keeps FavoritesSynced
        retryClient.cancelSync()
        assertTrue(repository.state.value is LoginSyncHostState.FavoritesSynced)
    }

    /**
     * Scenario 7: A completed exchange remains completed after disconnect.
     */
    @Test
    fun completedExchangeRemainsCompletedAfterDisconnect() = runBlocking {
        repository.start()
        val waitingState = repository.state.value as LoginSyncHostState.Waiting
        val invite = assertNotNull(LoginSyncInvite.parse(waitingState.invite))

        val client = LoginSyncClient()
        client.connect(invite)

        // Zero-changes completed exchange
        val emptyBundle = FavoritesSyncBundle(
            songs = emptyList(),
            hasMore = false,
            requestBatchIndex = 0,
        )
        client.sendFavorites(favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), emptyBundle).encodeToByteArray())

        assertTrue(repository.state.value is LoginSyncHostState.FavoritesSynced)

        // Disconnect using standard disconnect()
        client.disconnect()
        delay(50)

        assertTrue(repository.state.value is LoginSyncHostState.FavoritesSynced)
    }
}
