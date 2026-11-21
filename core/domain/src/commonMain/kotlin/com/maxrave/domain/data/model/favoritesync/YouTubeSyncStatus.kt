package com.maxrave.domain.data.model.favoritesync

sealed interface YouTubeSyncStatus {
    data object Idle : YouTubeSyncStatus
    data object Synced : YouTubeSyncStatus
    data object WaitingForConnection : YouTubeSyncStatus
    data object AuthenticationRequired : YouTubeSyncStatus
    data class SyncFailed(val pendingCount: Int, val message: String? = null) : YouTubeSyncStatus
    data class Syncing(val pendingCount: Int) : YouTubeSyncStatus
}
