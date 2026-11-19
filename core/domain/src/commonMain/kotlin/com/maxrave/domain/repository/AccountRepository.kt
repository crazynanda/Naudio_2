package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.model.account.AccountInfo
import com.maxrave.domain.data.model.account.YouTubeAccountProbe
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun getAccountInfo(cookie: String): Flow<List<AccountInfo>>

    /**
     * Asks YouTube which account [cookie] belongs to.
     *
     * Unlike [getAccountInfo] this keeps the three possible answers apart, so a caller can tell
     * "not a session" from "the request failed" and say so.
     */
    fun probeAccount(cookie: String): Flow<YouTubeAccountProbe>

    fun getYouTubeCookie(): String?

    fun insertGoogleAccount(googleAccountEntity: GoogleAccountEntity): Flow<Long>

    fun getGoogleAccounts(): Flow<List<GoogleAccountEntity>?>

    fun getUsedGoogleAccount(): Flow<GoogleAccountEntity?>

    suspend fun deleteGoogleAccount(email: String)

    fun updateGoogleAccountUsed(
        email: String,
        isUsed: Boolean,
    ): Flow<Int>
}