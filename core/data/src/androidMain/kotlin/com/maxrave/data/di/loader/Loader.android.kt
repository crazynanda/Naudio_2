package com.maxrave.data.di.loader

import com.maxrave.data.favoritesync.FavoriteSyncStore
import com.maxrave.media3.di.loadMediaService
import com.maxrave.data.loginsync.LoginSyncSenderRepositoryImpl
import com.maxrave.data.loginsync.LoginSyncStore
import com.maxrave.domain.repository.LoginSyncSenderRepository
import org.koin.core.context.loadKoinModules
import org.simpmusic.loginsync.LoginSyncClient
import org.koin.dsl.module

actual fun loadMediaService() {
    loadMediaService()
}

actual fun loadLoginSyncModule() {
    loadKoinModules(
        module {
            single { FavoriteSyncStore(get()) }
            single { LoginSyncStore(get(), get(), get(), getOrNull()) }
            single<LoginSyncSenderRepository> { LoginSyncSenderRepositoryImpl(get(), get(), LoginSyncClient()) }
        },
    )
}
