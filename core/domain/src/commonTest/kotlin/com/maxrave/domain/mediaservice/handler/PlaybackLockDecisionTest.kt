package com.maxrave.domain.mediaservice.handler

import com.maxrave.domain.data.player.PlayerConstants
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLockDecisionTest {

    @Test
    fun activePlaybackHoldsLocks() {
        // Active playback: playing, STATE_READY
        val result = shouldHoldPlaybackLocks(
            sessionPlayWhenReady = true,
            handlerPlayWhenReady = true,
            sessionPlaybackState = PlayerConstants.STATE_READY,
            handlerPlaybackState = PlayerConstants.STATE_READY,
            isPlaybackOngoing = true,
        )
        assertTrue(result, "Active playback should hold locks")
    }

    @Test
    fun activeBufferingHoldsLocks() {
        // Buffering next chunk
        val result = shouldHoldPlaybackLocks(
            sessionPlayWhenReady = true,
            handlerPlayWhenReady = true,
            sessionPlaybackState = PlayerConstants.STATE_BUFFERING,
            handlerPlaybackState = PlayerConstants.STATE_BUFFERING,
            isPlaybackOngoing = true,
        )
        assertTrue(result, "Active buffering should hold locks")
    }

    @Test
    fun trackTransitionWithIntentToPlayHoldsLocks() {
        // Transition: incoming player in STATE_BUFFERING (suppressed from IDLE), handler buffering
        val result = shouldHoldPlaybackLocks(
            sessionPlayWhenReady = true,
            handlerPlayWhenReady = true,
            sessionPlaybackState = PlayerConstants.STATE_BUFFERING,
            handlerPlaybackState = PlayerConstants.STATE_BUFFERING,
            isPlaybackOngoing = true,
        )
        assertTrue(result, "Track transition with play intent must hold locks")
    }

    @Test
    fun pauseDuringActivePlaybackReleasesLocksImmediately() {
        // User pressed pause during playback
        val result = shouldHoldPlaybackLocks(
            sessionPlayWhenReady = false,
            handlerPlayWhenReady = false,
            sessionPlaybackState = PlayerConstants.STATE_READY,
            handlerPlaybackState = PlayerConstants.STATE_READY,
            isPlaybackOngoing = true,
        )
        assertFalse(result, "Pause must release locks immediately")
    }

    @Test
    fun pauseDuringTrackTransitionReleasesLocksImmediately() {
        // User pressed pause during track transition (playWhenReady becomes false)
        val result = shouldHoldPlaybackLocks(
            sessionPlayWhenReady = false,
            handlerPlayWhenReady = false,
            sessionPlaybackState = PlayerConstants.STATE_BUFFERING,
            handlerPlaybackState = PlayerConstants.STATE_BUFFERING,
            isPlaybackOngoing = true,
        )
        assertFalse(result, "Pause during track transition must release locks immediately")
    }

    @Test
    fun genuineQueueEndReleasesLocks() {
        // Queue reached its natural end
        val result = shouldHoldPlaybackLocks(
            sessionPlayWhenReady = true,
            handlerPlayWhenReady = false,
            sessionPlaybackState = PlayerConstants.STATE_ENDED,
            handlerPlaybackState = PlayerConstants.STATE_ENDED,
            isPlaybackOngoing = false,
        )
        assertFalse(result, "Genuine queue completion must release locks")
    }

    @Test
    fun stoppedOrErrorStateWithoutIntentReleasesLocks() {
        // Error or stopped
        val result = shouldHoldPlaybackLocks(
            sessionPlayWhenReady = false,
            handlerPlayWhenReady = false,
            sessionPlaybackState = PlayerConstants.STATE_IDLE,
            handlerPlaybackState = PlayerConstants.STATE_IDLE,
            isPlaybackOngoing = false,
        )
        assertFalse(result, "Idle/error state without play intent must release locks")
    }

    @Test
    fun streamResolutionFailureReleasesLocks() {
        // Stream resolution error settles to IDLE/ERROR with playWhenReady = false
        val result = shouldHoldPlaybackLocks(
            sessionPlayWhenReady = false,
            handlerPlayWhenReady = false,
            sessionPlaybackState = PlayerConstants.STATE_IDLE,
            handlerPlaybackState = PlayerConstants.STATE_IDLE,
            isPlaybackOngoing = false,
        )
        assertFalse(result, "Stream resolution failure transitioning to IDLE without play intent must release locks")
    }
}
