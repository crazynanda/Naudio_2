package com.maxrave.domain.mediaservice.handler

import com.maxrave.domain.data.player.PlayerConstants

/**
 * Pure decision function governing service-level WakeLock and WifiLock acquisition.
 *
 * Ensures WakeLocks are held during:
 * 1. Active playback (STATE_READY / STATE_BUFFERING with playWhenReady = true).
 * 2. Inter-track transitions and stream resolution (STATE_BUFFERING or ongoing foreground service while play is intended).
 *
 * Guarantees WakeLocks are released immediately when:
 * 1. Playback is paused (playWhenReady = false).
 * 2. Playback reaches the genuine end of the queue (STATE_ENDED).
 * 3. Playback is stopped or in idle/error without active playback intent.
 */
fun shouldHoldPlaybackLocks(
    sessionPlayWhenReady: Boolean,
    handlerPlayWhenReady: Boolean,
    sessionPlaybackState: Int,
    handlerPlaybackState: Int,
    isPlaybackOngoing: Boolean,
): Boolean {
    val isPlayIntended = sessionPlayWhenReady || handlerPlayWhenReady
    if (!isPlayIntended) return false

    // Genuine queue completion: always release locks
    if (sessionPlaybackState == PlayerConstants.STATE_ENDED && handlerPlaybackState == PlayerConstants.STATE_ENDED) {
        return false
    }

    val isPlaybackActiveOrBuffering = sessionPlaybackState != PlayerConstants.STATE_ENDED &&
        (sessionPlaybackState != PlayerConstants.STATE_IDLE || isPlaybackOngoing || handlerPlaybackState == PlayerConstants.STATE_BUFFERING)

    return isPlaybackActiveOrBuffering
}
