package com.maxrave.data.loginsync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.favoritesync.FavoriteSongDto
import com.maxrave.data.favoritesync.FavoriteSyncStore
import com.maxrave.data.favoritesync.FavoritesSyncBundle
import com.maxrave.data.favoritesync.favoritesSyncJson
import com.maxrave.domain.data.model.favoritesync.FavoritesSyncResult
import com.maxrave.domain.data.model.loginsync.LoginSyncHostState
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import kotlinx.coroutines.runBlocking
import org.simpmusic.loginsync.LoginSyncClient
import org.simpmusic.loginsync.LoginSyncTransportException
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.CommonRepository
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

class LoginSyncHostRepositoryTest {
    private lateinit var tempDir: File
    private lateinit var database: MusicDatabase
    private lateinit var syncStore: FavoriteSyncStore
    private lateinit var repository: LoginSyncHostRepositoryImpl

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("host-repo-test").toFile()
        val dbFile = File(tempDir, "host.db")
        database = Room.databaseBuilder<MusicDatabase>(dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addTypeConverter(Converters())
            .build()
        syncStore = FavoriteSyncStore(database.getDatabaseDao())

        val fakeDataStore = fakeProxy<DataStoreManager>()
        val fakeCommonRepo = fakeProxy<CommonRepository>()
        val localDataSource = LocalDataSource(database.getDatabaseDao(), database)
        val fakeLoginStore = object : LoginSyncStore(fakeDataStore, localDataSource, fakeCommonRepo) {
            override suspend fun restore(bundle: LoginBundle): List<LoginSyncService> =
                buildList {
                    if (bundle.youtube != null) add(LoginSyncService.YOUTUBE)
                    if (bundle.spotifySpDc != null) add(LoginSyncService.SPOTIFY)
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

    @Test
    fun cancelBeforeSynchronizationWindowsReturnsToWaiting() = runBlocking {
        repository.start()
        assertTrue(repository.state.value is LoginSyncHostState.Waiting)

        // Phone connects
        repository.onHello()
        assertTrue(repository.state.value is LoginSyncHostState.Connected)

        // User cancels / disconnects before sync or login completes
        repository.onCancel()
        assertTrue(repository.state.value is LoginSyncHostState.Waiting)
    }

    @Test
    fun completeFavoritesSyncThenDisconnectAndroidWindowsRemainsFavoritesSynced() = runBlocking {
        repository.start()
        repository.onHello()
        assertTrue(repository.state.value is LoginSyncHostState.Connected)

        // Exchange single batch containing 1 song
        val incomingBundle = FavoritesSyncBundle(
            songs = listOf(FavoriteSongDto(videoId = "song1", title = "Song 1", favoriteAtEpochMs = 1000L)),
            hasMore = false,
            requestBatchIndex = 0,
        )
        val payload = favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), incomingBundle).encodeToByteArray()
        val replyBytes = repository.onFavorites(payload)
        val replyBundle = favoritesSyncJson.decodeFromString(FavoritesSyncBundle.serializer(), replyBytes.decodeToString())
        assertFalse(replyBundle.hasMore)

        // Completed state
        val state = repository.state.value
        assertTrue(state is LoginSyncHostState.FavoritesSynced)
        assertEquals(1, state.result.totalAddedOrUpdated)

        // Android disconnects (sends CANCEL when screen closes)
        repository.onCancel()

        // Windows remains FavoritesSynced and does not reset to Waiting
        val stateAfterCancel = repository.state.value
        assertTrue(stateAfterCancel is LoginSyncHostState.FavoritesSynced)
        assertEquals(1, stateAfterCancel.result.totalAddedOrUpdated)
    }

    @Test
    fun completeFavoritesSyncWithZeroChangesThenDisconnectWindowsRemainsCompleted() = runBlocking {
        repository.start()
        repository.onHello()
        assertTrue(repository.state.value is LoginSyncHostState.Connected)

        // Empty favorites exchange producing zero additions or removals
        val incomingBundle = FavoritesSyncBundle(
            songs = emptyList(),
            hasMore = false,
            requestBatchIndex = 0,
        )
        val payload = favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), incomingBundle).encodeToByteArray()
        val replyBytes = repository.onFavorites(payload)
        val replyBundle = favoritesSyncJson.decodeFromString(FavoritesSyncBundle.serializer(), replyBytes.decodeToString())
        assertFalse(replyBundle.hasMore)

        val state = repository.state.value
        assertTrue(state is LoginSyncHostState.FavoritesSynced)
        assertTrue(state.result.isEmpty, "Result should be empty for zero-changes exchange")

        // Android disconnects
        repository.onCancel()

        // Windows remains completed even though result.isEmpty is true
        val stateAfterCancel = repository.state.value
        assertTrue(stateAfterCancel is LoginSyncHostState.FavoritesSynced)
        assertTrue(stateAfterCancel.result.isEmpty)
    }

    @Test
    fun completeLoginTransferThenDisconnectWindowsRemainsDone() = runBlocking {
        repository.start()
        repository.onHello()
        assertTrue(repository.state.value is LoginSyncHostState.Connected)

        val loginPayload = loginSyncJson.encodeToString(
            LoginBundle.serializer(),
            LoginBundle(youtube = YouTubeLogin(accounts = emptyList(), cookie = "test_cookie", pageId = null, authUser = 0, dataSyncId = "")),
        ).encodeToByteArray()

        repository.onPayload(loginPayload)
        repository.onDelivered()

        val state = repository.state.value
        assertTrue(state is LoginSyncHostState.Done)
        assertEquals(listOf(LoginSyncService.YOUTUBE), state.services)

        // Android disconnects
        repository.onCancel()

        // Windows remains Done
        val stateAfterCancel = repository.state.value
        assertTrue(stateAfterCancel is LoginSyncHostState.Done)
        assertEquals(listOf(LoginSyncService.YOUTUBE), stateAfterCancel.services)
    }

    @Test
    fun disconnectDuringIncompleteMultiBatchExchangeSessionRemainsRetryable() = runBlocking {
        repository.start()
        repository.onHello()
        assertTrue(repository.state.value is LoginSyncHostState.Connected)

        // Client sends batch 0 of multi-batch (hasMore = true)
        val batch0 = FavoritesSyncBundle(
            songs = listOf(FavoriteSongDto(videoId = "part1", title = "Part 1", favoriteAtEpochMs = 1000L)),
            batchIndex = 0,
            batchTotal = 2,
            hasMore = true,
            requestBatchIndex = 0,
        )
        val payload0 = favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), batch0).encodeToByteArray()
        repository.onFavorites(payload0)

        // Exchange is incomplete, so desktop state remains Connected
        assertTrue(repository.state.value is LoginSyncHostState.Connected)

        // Client disconnects prematurely mid-exchange
        repository.onCancel()

        // Incomplete exchange resets desktop to Waiting
        assertTrue(repository.state.value is LoginSyncHostState.Waiting)

        // Client reconnects and retries the multi-batch exchange from batch 0
        repository.onHello()
        assertTrue(repository.state.value is LoginSyncHostState.Connected)

        repository.onFavorites(payload0)
        assertTrue(repository.state.value is LoginSyncHostState.Connected)

        val batch1 = FavoritesSyncBundle(
            songs = listOf(FavoriteSongDto(videoId = "part2", title = "Part 2", favoriteAtEpochMs = 1000L)),
            batchIndex = 1,
            batchTotal = 2,
            hasMore = false,
            requestBatchIndex = 1,
        )
        val payload1 = favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), batch1).encodeToByteArray()
        repository.onFavorites(payload1)

        // Multi-batch exchange completes
        val stateCompleted = repository.state.value
        assertTrue(stateCompleted is LoginSyncHostState.FavoritesSynced)

        // Disconnecting after completion keeps FavoritesSynced
        repository.onCancel()
        assertTrue(repository.state.value is LoginSyncHostState.FavoritesSynced)
    }

    @Test
    fun syncFavoritesAgainAfterCompletedExchangeWorks() = runBlocking {
        repository.start()
        repository.onHello()

        // 1. First favorites exchange
        val bundle1 = FavoritesSyncBundle(
            songs = listOf(FavoriteSongDto(videoId = "s1", title = "S1", favoriteAtEpochMs = 1000L)),
            hasMore = false,
            requestBatchIndex = 0,
        )
        repository.onFavorites(favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), bundle1).encodeToByteArray())
        assertTrue(repository.state.value is LoginSyncHostState.FavoritesSynced)

        // Disconnect
        repository.onCancel()
        assertTrue(repository.state.value is LoginSyncHostState.FavoritesSynced)

        // 2. Start another favorites sync (Sync Again) over the same session
        val bundle2 = FavoritesSyncBundle(
            songs = listOf(FavoriteSongDto(videoId = "s2", title = "S2", favoriteAtEpochMs = 1000L)),
            hasMore = false,
            requestBatchIndex = 0,
        )
        repository.onFavorites(favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), bundle2).encodeToByteArray())

        val stateAfterSecondSync = repository.state.value
        assertTrue(stateAfterSecondSync is LoginSyncHostState.FavoritesSynced)

        // Disconnect after second sync
        repository.onCancel()
        assertTrue(repository.state.value is LoginSyncHostState.FavoritesSynced)
    }

    @Test
    fun existingOneTimeLoginProtectionRejectsDuplicateLogins() = runBlocking {
        repository.start()
        val waitingState = repository.state.value
        assertTrue(waitingState is LoginSyncHostState.Waiting)

        val client = LoginSyncClient()
        val invite = assertNotNull(org.simpmusic.loginsync.LoginSyncInvite.parse(waitingState.invite))
        client.connect(invite)

        val loginPayload = loginSyncJson.encodeToString(
            LoginBundle.serializer(),
            LoginBundle(spotifySpDc = "test_spdc"),
        ).encodeToByteArray()

        // First login transfer succeeds
        val ack = client.send(loginPayload)
        assertTrue(ack.isNotEmpty())
        assertTrue(repository.state.value is LoginSyncHostState.Done)

        // Duplicate login transfer is rejected with REFUSED (HTTP 410)
        val refused = assertFailsWith<LoginSyncTransportException> {
            client.send(loginPayload)
        }
        assertEquals(LoginSyncTransportException.Kind.REFUSED, refused.kind)

        // Client disconnects; Windows remains Done
        client.disconnect()
        kotlinx.coroutines.delay(50)
        assertTrue(repository.state.value is LoginSyncHostState.Done)
    }
}
