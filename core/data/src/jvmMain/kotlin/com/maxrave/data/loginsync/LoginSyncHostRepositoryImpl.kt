package com.maxrave.data.loginsync

import com.maxrave.data.favoritesync.FavoriteSyncStore
import com.maxrave.data.favoritesync.FavoritesSyncBundle
import com.maxrave.data.favoritesync.favoritesSyncJson
import com.maxrave.domain.data.model.favoritesync.FavoritesSyncResult
import com.maxrave.domain.data.model.loginsync.LoginSyncHostState
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.data.model.loginsync.NetAddress
import com.maxrave.domain.repository.LoginSyncHostRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.simpmusic.loginsync.LoginSyncServer
import org.simpmusic.loginsync.reachableAddresses

private const val TAG = "LoginSyncHost"

/** Desktop: runs the loginSync service's server and restores whatever sign-ins the phone sends. */
internal class LoginSyncHostRepositoryImpl(
    private val store: LoginSyncStore,
    private val favoriteSyncStore: FavoriteSyncStore,
    private val allowLoopbackFallback: Boolean = false,
) : LoginSyncHostRepository,
    LoginSyncServer.Handler {
    private val _state = MutableStateFlow<LoginSyncHostState>(LoginSyncHostState.Starting)
    override val state: StateFlow<LoginSyncHostState> = _state.asStateFlow()

    private val server = LoginSyncServer(this)

    // Owns stop(): it must finish even when the caller's scope is being cancelled.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val favoritesMutex = Mutex()

    // Bumped by every start(), so a stop() queued behind a quick reopen cannot kill the new session.
    @Volatile
    private var generation = 0
    private var session: LoginSyncServer.Session? = null
    private var addresses = emptyList<NetAddress>()
    private var selected: NetAddress? = null
    private var restored = emptyList<LoginSyncService>()
    private var hostBatches: List<FavoritesSyncBundle>? = null
    private var cumulativeHostMergeResult = FavoritesSyncResult()

    @Volatile
    private var favoritesCompleted = false

    @Volatile
    private var loginCompleted = false

    @Volatile
    private var exchangeInProgress = false

    override suspend fun start() =
        lifecycle.withLock {
            server.stop()
            generation++
            hostBatches = null
            cumulativeHostMergeResult = FavoritesSyncResult()
            restored = emptyList()
            favoritesCompleted = false
            loginCompleted = false
            exchangeInProgress = false
            _state.value = LoginSyncHostState.Starting
            val rawFound = withContext(Dispatchers.IO) { reachableAddresses() }.map { NetAddress(it.interfaceName, it.ip) }
            val found = if (rawFound.isNotEmpty() || !allowLoopbackFallback) rawFound else listOf(NetAddress("Loopback", "127.0.0.1"))
            if (found.isEmpty()) {
                _state.value = LoginSyncHostState.NoNetwork
                return@withLock
            }
            addresses = found
            // Keep the user's pick across a "new code" if that address is still up.
            selected = selected?.takeIf { it in found } ?: found.first()
            try {
                session = server.start()
                _state.value = waiting()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "start", e)
                _state.value = LoginSyncHostState.Failed(e.message ?: e.toString())
            }
        }

    override fun select(address: NetAddress) {
        if (_state.value !is LoginSyncHostState.Waiting || address !in addresses) return
        selected = address
        _state.value = waiting()
    }

    override suspend fun expire() =
        lifecycle.withLock {
            server.stop()
            _state.value = LoginSyncHostState.Expired
        }

    override fun stop() {
        val target = generation
        scope.launch { lifecycle.withLock { if (generation == target) server.stop() } }
    }

    private fun waiting(): LoginSyncHostState {
        val address = selected ?: return LoginSyncHostState.Starting
        val sess = session ?: return LoginSyncHostState.Starting
        val invite = sess.invite(address.ip)
        return LoginSyncHostState.Waiting(invite.toUri(), addresses, address)
    }

    // LoginSyncServer.Handler — called from the server's request coroutines.

    override fun onHello() {
        if (!favoritesCompleted && !loginCompleted) {
            _state.value = LoginSyncHostState.Connected
        }
    }

    override suspend fun onCancel() {
        favoritesMutex.withLock {
            if (!exchangeInProgress && (favoritesCompleted || loginCompleted)) {
                // A CANCEL received after a successfully completed favorites exchange or login transfer
                // must not erase the completed state.
                return
            }
            hostBatches = null
            exchangeInProgress = false
            if (!favoritesCompleted && !loginCompleted) {
                cumulativeHostMergeResult = FavoritesSyncResult()
                _state.value = waiting()
            }
        }
    }

    override suspend fun onPayload(payload: ByteArray): ByteArray {
        val bundle = loginSyncJson.decodeFromString(LoginBundle.serializer(), payload.decodeToString())
        restored = store.restore(bundle)
        return loginSyncJson.encodeToString(LoginSyncAck.serializer(), LoginSyncAck(restored.map { it.name })).encodeToByteArray()
    }

    override suspend fun onFavorites(payload: ByteArray): ByteArray =
        favoritesMutex.withLock {
            val incomingBundle = favoritesSyncJson.decodeFromString(FavoritesSyncBundle.serializer(), payload.decodeToString())

            // Snapshot desktop's local favorites on the first request of an exchange
            if (incomingBundle.requestBatchIndex == 0 || hostBatches == null) {
                hostBatches = favoriteSyncStore.exportBundles()
                cumulativeHostMergeResult = FavoritesSyncResult()
                exchangeInProgress = true
            }

            val mergeResult = favoriteSyncStore.mergeBundle(incomingBundle)
            cumulativeHostMergeResult =
                cumulativeHostMergeResult.copy(
                    songsAddedOrUpdated = cumulativeHostMergeResult.songsAddedOrUpdated + mergeResult.songsAddedOrUpdated,
                    songsRemoved = cumulativeHostMergeResult.songsRemoved + mergeResult.songsRemoved,
                    albumsAddedOrUpdated = cumulativeHostMergeResult.albumsAddedOrUpdated + mergeResult.albumsAddedOrUpdated,
                    albumsRemoved = cumulativeHostMergeResult.albumsRemoved + mergeResult.albumsRemoved,
                    playlistsAddedOrUpdated = cumulativeHostMergeResult.playlistsAddedOrUpdated + mergeResult.playlistsAddedOrUpdated,
                    playlistsRemoved = cumulativeHostMergeResult.playlistsRemoved + mergeResult.playlistsRemoved,
                    podcastsAddedOrUpdated = cumulativeHostMergeResult.podcastsAddedOrUpdated + mergeResult.podcastsAddedOrUpdated,
                    podcastsRemoved = cumulativeHostMergeResult.podcastsRemoved + mergeResult.podcastsRemoved,
                    artistsAddedOrUpdated = cumulativeHostMergeResult.artistsAddedOrUpdated + mergeResult.artistsAddedOrUpdated,
                    artistsRemoved = cumulativeHostMergeResult.artistsRemoved + mergeResult.artistsRemoved,
                )

            val batches = hostBatches ?: emptyList()
            val replyBundle =
                if (incomingBundle.requestBatchIndex in batches.indices) {
                    batches[incomingBundle.requestBatchIndex]
                } else {
                    FavoritesSyncBundle(
                        clientTimestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                        batchIndex = incomingBundle.requestBatchIndex,
                        batchTotal = maxOf(batches.size, 1),
                        hasMore = false,
                    )
                }

            // A favorites exchange is complete only after both devices have finished sending their batches.
            val clientFinished = !incomingBundle.hasMore
            val hostFinished = !replyBundle.hasMore
            if (clientFinished && hostFinished) {
                exchangeInProgress = false
                favoritesCompleted = true
                val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
                _state.value =
                    if (loginCompleted || restored.isNotEmpty()) {
                        LoginSyncHostState.Done(
                            services = restored,
                            favoritesResult = cumulativeHostMergeResult,
                            timestamp = nowMs,
                        )
                    } else {
                        LoginSyncHostState.FavoritesSynced(
                            result = cumulativeHostMergeResult,
                            timestamp = nowMs,
                        )
                    }
            }

            favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), replyBundle).encodeToByteArray()
        }

    override fun onDelivered() {
        loginCompleted = true
        _state.value =
            LoginSyncHostState.Done(
                services = restored,
                favoritesResult = if (favoritesCompleted) cumulativeHostMergeResult else null,
                timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            )
    }

    override fun onRejected(reason: String) {
        exchangeInProgress = false
        if (!favoritesCompleted && !loginCompleted) {
            _state.value = LoginSyncHostState.Rejected(reason)
        }
    }
}
