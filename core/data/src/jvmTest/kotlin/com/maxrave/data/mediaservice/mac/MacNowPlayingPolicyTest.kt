package com.maxrave.data.mediaservice.mac

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM unit tests for [MacNowPlayingPolicy] — the pure decision logic behind the macOS
 * remote commands and Now Playing metadata. These run on any OS (no native code involved);
 * the native side itself still needs a real Mac.
 */
class MacNowPlayingPolicyTest {
    // ===== Play / Pause intent guarding =====

    @Test
    fun `play command acts only when paused`() {
        assertTrue(MacNowPlayingPolicy.shouldActOnPlay(isPlaying = false))
        assertFalse(MacNowPlayingPolicy.shouldActOnPlay(isPlaying = true))
    }

    @Test
    fun `pause command acts only when playing`() {
        assertTrue(MacNowPlayingPolicy.shouldActOnPause(isPlaying = true))
        assertFalse(MacNowPlayingPolicy.shouldActOnPause(isPlaying = false))
    }

    @Test
    fun `a duplicate play delivery while playing does nothing`() {
        // First Play: paused -> act. Second (duplicate) delivery: now playing -> no-op.
        val firstDeliveryActs = MacNowPlayingPolicy.shouldActOnPlay(isPlaying = false)
        assertTrue(firstDeliveryActs)
        val secondDeliveryActs = MacNowPlayingPolicy.shouldActOnPlay(isPlaying = true)
        assertFalse(secondDeliveryActs)
    }

    // ===== Elapsed push throttling =====

    @Test
    fun `elapsed push is due after the interval`() {
        val last = 1_000_000_000L
        val now = last + MacNowPlayingPolicy.ELAPSED_PUSH_INTERVAL_NANOS
        assertTrue(MacNowPlayingPolicy.isElapsedPushDue(last, now, force = false))
    }

    @Test
    fun `elapsed push is not due inside the interval`() {
        val last = 1_000_000_000L
        val now = last + MacNowPlayingPolicy.ELAPSED_PUSH_INTERVAL_NANOS - 1
        assertFalse(MacNowPlayingPolicy.isElapsedPushDue(last, now, force = false))
    }

    @Test
    fun `forced push bypasses the interval`() {
        val last = 1_000_000_000L
        val now = last + 1
        assertTrue(MacNowPlayingPolicy.isElapsedPushDue(last, now, force = true))
    }

    @Test
    fun `never-pushed sentinel is due immediately`() {
        // The sentinel is Long.MIN_VALUE, not 0: nanoTime values can legitimately be small
        // or negative on some JVMs, so 0 cannot mean "never". The first push must not wait
        // a full interval after startup.
        assertTrue(
            MacNowPlayingPolicy.isElapsedPushDue(
                lastPushNanos = MacNowPlayingPolicy.NEVER_PUSHED_NANOS,
                nowNanos = 1,
                force = false,
            ),
        )
    }

    @Test
    fun `never-pushed sentinel is due even with no elapsed time`() {
        assertTrue(
            MacNowPlayingPolicy.isElapsedPushDue(
                lastPushNanos = MacNowPlayingPolicy.NEVER_PUSHED_NANOS,
                nowNanos = 0,
                force = false,
            ),
        )
    }

    @Test
    fun `small real nanoTime values are not mistaken for never-pushed`() {
        // A genuine nanoTime reading (e.g. early in the JVM's life) must NOT reset the
        // throttle the way the old `<= 0` wall-clock check would have.
        assertFalse(
            MacNowPlayingPolicy.isElapsedPushDue(
                lastPushNanos = -500,
                nowNanos = -400,
                force = false,
            ),
        )
    }

    @Test
    fun `a backward clock step cannot freeze the throttle the wall clock would have`() {
        // Regression for the 2.1 finding: currentTimeMillis stepping BACKWARD (NTP
        // correction, manual clock change) made the delta strongly negative and suppressed
        // pushes for the whole size of the step, freezing macOS's elapsed display.
        // nanoTime is monotonic per process, so the policy must treat a still-growing
        // monotonic source as due at the interval — this pins the delta arithmetic to the
        // real semantics a monotonic source delivers.
        val last = 100_000_000_000L
        val now = last + MacNowPlayingPolicy.ELAPSED_PUSH_INTERVAL_NANOS
        assertTrue(MacNowPlayingPolicy.isElapsedPushDue(last, now, force = false))
        // And a delta of zero (or negative, which a monotonic source cannot produce but
        // must not crash on) stays throttled rather than spamming.
        assertFalse(MacNowPlayingPolicy.isElapsedPushDue(last, last, force = false))
        assertFalse(MacNowPlayingPolicy.isElapsedPushDue(last, last - 1, force = false))
    }

    // ===== Remote seek clamping =====

    @Test
    fun `seek inside the track is kept`() {
        assertEquals(42.5, MacNowPlayingPolicy.clampSeekSeconds(42.5, durationMs = 200_000))
    }

    @Test
    fun `seek past the end clamps to the end`() {
        assertEquals(200.0, MacNowPlayingPolicy.clampSeekSeconds(500.0, durationMs = 200_000))
    }

    @Test
    fun `negative and NaN seeks clamp to zero`() {
        assertEquals(0.0, MacNowPlayingPolicy.clampSeekSeconds(-3.0, durationMs = 200_000))
        assertEquals(0.0, MacNowPlayingPolicy.clampSeekSeconds(Double.NaN, durationMs = 200_000))
    }

    @Test
    fun `seek with unknown duration is left alone`() {
        assertEquals(42.5, MacNowPlayingPolicy.clampSeekSeconds(42.5, durationMs = 0))
        assertEquals(42.5, MacNowPlayingPolicy.clampSeekSeconds(42.5, durationMs = -1))
    }

    // ===== Now Playing duration =====

    @Test
    fun `live player duration wins`() {
        assertEquals(123.4, MacNowPlayingPolicy.durationSecondsForNowPlaying(123_400, songDurationSeconds = 999))
    }

    @Test
    fun `song row duration fills a zero player duration`() {
        assertEquals(215.0, MacNowPlayingPolicy.durationSecondsForNowPlaying(0, songDurationSeconds = 215))
    }

    @Test
    fun `negative player duration falls back to the song row`() {
        assertEquals(215.0, MacNowPlayingPolicy.durationSecondsForNowPlaying(-1, songDurationSeconds = 215))
    }

    @Test
    fun `live stream with no known duration is zero, not garbage`() {
        assertEquals(0.0, MacNowPlayingPolicy.durationSecondsForNowPlaying(0, songDurationSeconds = 0))
    }
}
