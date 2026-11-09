package com.maxrave.data.loginsync

import com.maxrave.data.favoritesync.FavoriteSyncStore
import com.maxrave.data.favoritesync.FavoritesSyncBundle
import com.maxrave.data.favoritesync.favoritesSyncJson
import com.maxrave.domain.data.model.favoritesync.FavoritesSyncResult
import com.maxrave.domain.data.model.loginsync.DesktopInfo
import com.maxrave.domain.data.model.loginsync.LoginSyncException
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.repository.LoginSyncSenderRepository
import org.simpmusic.loginsync.LoginSyncClient
import org.simpmusic.loginsync.LoginSyncInvite
import org.simpmusic.loginsync.LoginSyncTransportException

/** Android: turns this device's sign-ins into a payload and hands it to the loginSync service. */
internal class LoginSyncSenderRepositoryImpl(
    private val store: LoginSyncStore,
    private val favoriteSyncStore: FavoriteSyncStore,
    private val client: LoginSyncClient,
) : LoginSyncSenderRepository {
    override suspend fun signedInServices(): List<LoginSyncService> = store.signedIn()

    override fun isInvite(qrText: String): Boolean = LoginSyncInvite.parse(qrText) != null

    override suspend fun connect(qrText: String): DesktopInfo {
        val invite = LoginSyncInvite.parse(qrText) ?: throw LoginSyncException(LoginSyncException.Reason.FAILED, "not a login code")
        val device = transport { client.connect(invite) }
        return DesktopInfo(device.name, device.os)
    }

    override suspend fun send(services: Set<LoginSyncService>): List<LoginSyncService> {
        val payload = loginSyncJson.encodeToString(LoginBundle.serializer(), store.bundle(services)).encodeToByteArray()
        val reply = transport { client.send(payload) }
        val ack = loginSyncJson.decodeFromString(LoginSyncAck.serializer(), reply.decodeToString())
        return ack.services.mapNotNull { name -> LoginSyncService.entries.firstOrNull { it.name == name } }
    }

    override suspend fun syncFavorites(): FavoritesSyncResult {
        val bundles = favoriteSyncStore.exportBundles()
        var combinedResult = FavoritesSyncResult()
        for (bundle in bundles) {
            val payload = favoritesSyncJson.encodeToString(FavoritesSyncBundle.serializer(), bundle).encodeToByteArray()
            val reply = transport { client.sendFavorites(payload) }
            val hostBundle = favoritesSyncJson.decodeFromString(FavoritesSyncBundle.serializer(), reply.decodeToString())
            val mergeResult = favoriteSyncStore.mergeBundle(hostBundle)
            combinedResult = combinedResult.copy(
                songsAddedOrUpdated = combinedResult.songsAddedOrUpdated + mergeResult.songsAddedOrUpdated,
                songsRemoved = combinedResult.songsRemoved + mergeResult.songsRemoved,
                albumsAddedOrUpdated = combinedResult.albumsAddedOrUpdated + mergeResult.albumsAddedOrUpdated,
                albumsRemoved = combinedResult.albumsRemoved + mergeResult.albumsRemoved,
                playlistsAddedOrUpdated = combinedResult.playlistsAddedOrUpdated + mergeResult.playlistsAddedOrUpdated,
                playlistsRemoved = combinedResult.playlistsRemoved + mergeResult.playlistsRemoved,
                podcastsAddedOrUpdated = combinedResult.podcastsAddedOrUpdated + mergeResult.podcastsAddedOrUpdated,
                podcastsRemoved = combinedResult.podcastsRemoved + mergeResult.podcastsRemoved,
                artistsAddedOrUpdated = combinedResult.artistsAddedOrUpdated + mergeResult.artistsAddedOrUpdated,
                artistsRemoved = combinedResult.artistsRemoved + mergeResult.artistsRemoved,
            )
        }
        return combinedResult
    }

    override fun disconnect() = client.disconnect()

    private inline fun <T> transport(block: () -> T): T =
        try {
            block()
        } catch (e: LoginSyncTransportException) {
            val reason =
                when (e.kind) {
                    LoginSyncTransportException.Kind.UNREACHABLE -> LoginSyncException.Reason.UNREACHABLE
                    LoginSyncTransportException.Kind.REFUSED -> LoginSyncException.Reason.CODE_EXPIRED
                    LoginSyncTransportException.Kind.FAILED -> LoginSyncException.Reason.FAILED
                }
            throw LoginSyncException(reason, e.detail)
        }
}
