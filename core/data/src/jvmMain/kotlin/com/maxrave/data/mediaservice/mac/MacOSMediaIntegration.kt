package com.maxrave.data.mediaservice.mac

import com.maxrave.logger.Logger
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "MacOSMediaIntegration"

/**
 * MPNowPlayingPlaybackState constants
 */
object MPNowPlayingPlaybackState {
    const val Unknown = 0
    const val Playing = 1
    const val Paused = 2
    const val Stopped = 3
    const val Interrupted = 4
}

/**
 * MPRemoteCommandHandlerStatus constants
 */
object MPRemoteCommandHandlerStatus {
    const val Success = 0
    const val NoSuchContent = 1
    const val NoActionableNowPlayingItem = 2
    const val DeviceNotFound = 3
    const val CommandFailed = 4
}

/**
 * Helper object to get MediaPlayer framework constants
 * The constants are NSString* symbols exported from the framework
 */
object MPConstants {
    private var loaded = false
    private val constants = mutableMapOf<String, Pointer?>()

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        try {
            // Load MediaPlayer framework to access its exported symbols
            val lib =
                com.sun.jna.NativeLibrary
                    .getInstance("MediaPlayer")

            // Get the actual NSString* constant values from the framework
            val symbolNames =
                listOf(
                    "MPMediaItemPropertyTitle",
                    "MPMediaItemPropertyArtist",
                    "MPMediaItemPropertyAlbumTitle",
                    "MPMediaItemPropertyPlaybackDuration",
                    "MPMediaItemPropertyArtwork",
                    "MPNowPlayingInfoPropertyElapsedPlaybackTime",
                    "MPNowPlayingInfoPropertyPlaybackRate",
                    "MPNowPlayingInfoPropertyDefaultPlaybackRate",
                    "MPNowPlayingInfoPropertyPlaybackQueueIndex",
                    "MPNowPlayingInfoPropertyPlaybackQueueCount",
                    "MPNowPlayingInfoPropertyMediaType",
                )

            for (name in symbolNames) {
                try {
                    val symbolPtr = lib.getGlobalVariableAddress(name)
                    // The symbol is a pointer to NSString*, so we need to dereference it
                    val nsStringPtr = symbolPtr.getPointer(0)
                    constants[name] = nsStringPtr
                    Logger.d(TAG, "Loaded constant $name: $nsStringPtr")
                } catch (e: Exception) {
                    Logger.w(TAG, "Could not load constant $name: ${e.message}")
                }
            }
            loaded = true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load MediaPlayer constants: ${e.message}", e)
        }
    }

    fun get(name: String): Pointer? {
        ensureLoaded()
        return constants[name]
    }

    // Convenience methods
    val title: Pointer? get() = get("MPMediaItemPropertyTitle")
    val artist: Pointer? get() = get("MPMediaItemPropertyArtist")
    val album: Pointer? get() = get("MPMediaItemPropertyAlbumTitle")
    val playbackDuration: Pointer? get() = get("MPMediaItemPropertyPlaybackDuration")
    val artwork: Pointer? get() = get("MPMediaItemPropertyArtwork")
    val elapsedPlaybackTime: Pointer? get() = get("MPNowPlayingInfoPropertyElapsedPlaybackTime")
    val playbackRate: Pointer? get() = get("MPNowPlayingInfoPropertyPlaybackRate")
    val defaultPlaybackRate: Pointer? get() = get("MPNowPlayingInfoPropertyDefaultPlaybackRate")
    val playbackQueueIndex: Pointer? get() = get("MPNowPlayingInfoPropertyPlaybackQueueIndex")
    val playbackQueueCount: Pointer? get() = get("MPNowPlayingInfoPropertyPlaybackQueueCount")
    val mediaType: Pointer? get() = get("MPNowPlayingInfoPropertyMediaType")
}

/**
 * Pure decision logic shared by the macOS integration and the JVM media handler — no native
 * dependencies, so it is unit-testable on any OS. Everything the ObjC side decides at runtime
 * that can be decided in advance lives here.
 */
object MacNowPlayingPolicy {
    /**
     * macOS delivers Play and Pause as DISTINCT commands that each name the state they want.
     * Routing both through the toggle must be guarded, or a Play command arriving while the
     * player is already playing would pause it — and a duplicate delivery would toggle twice.
     */
    fun shouldActOnPlay(isPlaying: Boolean): Boolean = !isPlaying

    fun shouldActOnPause(isPlaying: Boolean): Boolean = isPlaying

    /** The minimum gap between two elapsed-time pushes to the Now Playing dictionary. */
    const val ELAPSED_PUSH_INTERVAL_NANOS = 1_000_000_000L

    /**
     * Sentinel for "no push has ever happened": [System.nanoTime] values can legitimately be
     * small or negative on some JVMs, so `0` cannot be the sentinel — [Long.MIN_VALUE] is
     * safely outside the 292-year range of any real nanoTime.
     */
    const val NEVER_PUSHED_NANOS = Long.MIN_VALUE

    /**
     * macOS advances the elapsed display itself from the pushed rate, so 1 Hz keeps the clock
     * accurate while costing 1/20th of the 50 ms progress tick's native work (a dictionary
     * copy plus a dictionary swap each push).
     *
     * The timestamps are [System.nanoTime], not the wall clock: `currentTimeMillis` can step
     * BACKWARD (NTP correction, manual clock change), which would suppress pushes for the
     * whole size of the step and freeze macOS's elapsed display mid-playback. nanoTime is
     * monotonic per process, so the delta can only grow.
     */
    fun isElapsedPushDue(
        lastPushNanos: Long,
        nowNanos: Long,
        force: Boolean,
        minIntervalNanos: Long = ELAPSED_PUSH_INTERVAL_NANOS,
    ): Boolean =
        force ||
            lastPushNanos == NEVER_PUSHED_NANOS ||
            nowNanos - lastPushNanos >= minIntervalNanos

    /**
     * Clamp a remote seek (scrub) into the track's real bounds. NaN and negative positions
     * collapse to 0; past-the-end collapses to the end.
     */
    fun clampSeekSeconds(
        positionSeconds: Double,
        durationMs: Long,
    ): Double =
        when {
            positionSeconds.isNaN() || positionSeconds < 0.0 -> 0.0
            durationMs > 0 -> positionSeconds.coerceAtMost(durationMs / 1000.0)
            else -> positionSeconds
        }

    /**
     * Duration for the Now Playing dictionary. At a track transition the player's duration is
     * still the PREVIOUS track's (or 0 before the demuxer answers), so the song row's stored
     * length fills the gap; a live stream (duration 0 everywhere) gets 0 rather than garbage.
     */
    fun durationSecondsForNowPlaying(
        playerDurationMs: Long,
        songDurationSeconds: Int,
    ): Double =
        when {
            playerDurationMs > 0 -> playerDurationMs / 1000.0
            songDurationSeconds > 0 -> songDurationSeconds.toDouble()
            else -> 0.0
        }
}

/**
 * Listener interface for remote commands from macOS
 */
interface MacOSRemoteCommandListener {
    fun onPlay()

    fun onPause()

    fun onTogglePlayPause()

    fun onStop()

    fun onNextTrack()

    fun onPreviousTrack()

    fun onSeekForward()

    fun onSeekBackward()

    fun onChangePlaybackPosition(positionSeconds: Double)
}

/**
 * Data class for Now Playing info
 */
data class NowPlayingInfo(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Double,
    val elapsedTimeSeconds: Double = 0.0,
    val playbackRate: Double = 1.0,
    val artworkUrl: String? = null,
    val queueIndex: Int = 0,
    val queueCount: Int = 1,
)

/**
 * Block literal structure for Objective-C blocks
 * This is a simplified version - in production you might need more fields
 */
@Structure.FieldOrder("isa", "flags", "reserved", "invoke", "descriptor")
class BlockLiteral : Structure() {
    @JvmField
    var isa: Pointer? = null

    @JvmField
    var flags: Int = 0

    @JvmField
    var reserved: Int = 0

    @JvmField
    var invoke: Pointer? = null

    @JvmField
    var descriptor: Pointer? = null

    companion object {
        // Block flags
        const val BLOCK_HAS_COPY_DISPOSE = (1 shl 25)
        const val BLOCK_HAS_CTOR = (1 shl 26)
        const val BLOCK_IS_GLOBAL = (1 shl 28)
        const val BLOCK_HAS_STRET = (1 shl 29)
        const val BLOCK_HAS_SIGNATURE = (1 shl 30)
    }
}

/**
 * `dispatch_function_t` — kept top-level so JNA can build its trampoline without fighting the
 * accessibility of an enclosing private type.
 */
internal interface DispatchWork : Callback {
    fun invoke(context: Pointer?)
}

/**
 * Runs work on the AppKit main queue.
 *
 * ## Why this is needed
 * Conveyor's launcher keeps thread 0 for the Cocoa event loop (`conveyor::run_cocoa_event_loop`)
 * and starts the JVM on a separate pthread, so in a packaged `.app` neither `main()` nor anything
 * Koin builds with `createdAtStart` runs on the main thread. MediaPlayer.framework does not
 * register its command handlers from there, which is why Now Playing works under `gradle run` —
 * where there is no launcher and `main()` owns thread 0 — but not in the shipped app.
 *
 * The queue is reachable because AWT keeps a CFRunLoop on thread 0, and that run loop drains the
 * main dispatch queue.
 */
private object MainQueue {
    private interface LibC : Library {
        /** Darwin: returns 1 on the initial (main) thread. */
        fun pthread_main_np(): Int
    }

    private interface Dispatch : Library {
        fun dispatch_sync_f(
            queue: Pointer,
            context: Pointer?,
            work: DispatchWork,
        )
    }

    private val libc: LibC? = runCatching { Native.load("c", LibC::class.java) }.getOrNull()
    private val dispatch: Dispatch? = runCatching { Native.load("System", Dispatch::class.java) }.getOrNull()

    /** `dispatch_get_main_queue()` is `&_dispatch_main_q`, so the symbol address is the queue. */
    private val mainQueue: Pointer? =
        runCatching {
            com.sun.jna.NativeLibrary
                .getInstance("System")
                .getGlobalVariableAddress("_dispatch_main_q")
        }.getOrNull()

    fun isMainThread(): Boolean = libc?.pthread_main_np() == 1

    /**
     * Runs [block] on the main queue and waits for it.
     *
     * Returns false when the hop is impossible (symbols unavailable) so the caller can decide;
     * a thrown [block] is rethrown here rather than crossing back into libdispatch, where an
     * escaping exception would abort the process.
     */
    fun runSync(block: () -> Unit): Boolean {
        // dispatch_sync onto the queue we are already on would deadlock.
        if (isMainThread()) {
            block()
            return true
        }
        val d = dispatch ?: return false
        val q = mainQueue ?: return false
        var failure: Throwable? = null
        val work =
            object : DispatchWork {
                override fun invoke(context: Pointer?) {
                    runCatching { block() }.onFailure { failure = it }
                }
            }
        d.dispatch_sync_f(q, null, work)
        failure?.let { throw it }
        return true
    }
}

/**
 * Main class for macOS Media Integration
 * Handles Now Playing Center and Remote Command Center
 */
class MacOSMediaIntegration private constructor() {
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var remoteCommandListener: MacOSRemoteCommandListener? = null

    /**
     * Guards every list and cache below: `initialize()` (and so `createCommandHandler`) runs
     * on the main queue, while `release()` and artwork eviction run on JVM worker threads.
     */
    private val nativeStateLock = Any()

    // Keep references to prevent garbage collection of the JNA trampolines and the native
    // block literals the command center holds. Cleared only after the targets are removed.
    private val blockCallbacks = mutableListOf<Callback>()
    private val blockMemories = mutableListOf<Memory>()

    /**
     * One Kotlin-owned native object triple per artwork URL ever applied. Bounded: evicting
     * the least-recently-used entry releases its objects. Unbounded, a listening session
     * would retain an NSImage + NSData + MPMediaItemArtwork per track for the whole process.
     */
    private val artworkCache = LinkedHashMap<String, ArtworkEntry>(16, 0.75f, true)

    /** The artwork URL whose metadata is currently applied to Now Playing. */
    private var currentArtworkUrl: String? = null

    /**
     * The artwork URL currently being fetched. A newer request supersedes an older one, so
     * a slow download for the PREVIOUS track must not paint its artwork onto the new track's
     * metadata once the download lands.
     */
    @Volatile
    private var requestedArtworkUrl: String? = null

    /** Monotonic timestamp of the last elapsed-time push (see [MacNowPlayingPolicy]). */
    private val lastElapsedPushNanos = AtomicLong(MacNowPlayingPolicy.NEVER_PUSHED_NANOS)

    /** Kotlin-owned native artwork objects, kept alive exactly as long as the cache entry. */
    private class ArtworkEntry(
        val artwork: Pointer,
        val nsImage: Pointer,
        val nsData: Pointer,
    )

    companion object {
        /** Artwork entries retained for reuse; 8 covers any realistic back-and-forth listening. */
        private const val ARTWORK_CACHE_LIMIT = 8

        @Volatile
        private var instance: MacOSMediaIntegration? = null

        @Volatile
        private var frameworkLoaded = false

        fun getInstance(): MacOSMediaIntegration =
            instance ?: synchronized(this) {
                instance ?: MacOSMediaIntegration().also { instance = it }
            }

        /**
         * Check if running on macOS
         */
        fun isSupported(): Boolean = Platform.isMac()

        /**
         * Load the MediaPlayer framework
         * This must be called before using any MediaPlayer APIs
         */
        private fun loadMediaPlayerFramework(): Boolean {
            if (frameworkLoaded) return true

            return try {
                // Load the MediaPlayer framework
                // On macOS, frameworks are located at /System/Library/Frameworks/
                val frameworkPath = "/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer"
                System.load(frameworkPath)
                frameworkLoaded = true
                Logger.d(TAG, "MediaPlayer framework loaded successfully")
                true
            } catch (e: UnsatisfiedLinkError) {
                // Try alternative approach - just reference the framework classes
                // They may be loaded on-demand by the Objective-C runtime
                Logger.w(TAG, "Could not load MediaPlayer framework directly: ${e.message}")
                // Check if classes are available
                val cls = ObjC.cls("MPNowPlayingInfoCenter")
                if (cls != null) {
                    frameworkLoaded = true
                    Logger.d(TAG, "MediaPlayer classes available via runtime")
                    true
                } else {
                    Logger.e(TAG, "MediaPlayer framework not available")
                    false
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to load MediaPlayer framework: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Initialize the media integration
     * Must be called from the main thread
     */
    fun initialize(): Boolean {
        if (!isSupported()) {
            Logger.w(TAG, "MacOS media integration not supported on this platform")
            return false
        }

        // compareAndSet, not get: two concurrent calls (a handler rebuild racing a lazy
        // accessor) must not both pass the guard and register the native command targets twice.
        if (!initialized.compareAndSet(false, true)) {
            Logger.d(TAG, "Already initialized or initializing")
            return true
        }

        // MediaPlayer.framework registers its command handlers against the main thread. In a
        // packaged .app this method runs on the JVM's own pthread, not thread 0 — see [MainQueue].
        var result = false
        val reachedMainQueue =
            runCatching { MainQueue.runSync { result = initializeOnMainThread() } }
                .onFailure { Logger.e(TAG, "Main-queue init threw: ${it.message}", it) }
                .getOrDefault(false)

        if (!reachedMainQueue) {
            initialized.set(false)
            Logger.e(TAG, "Could not reach the main queue; media integration stays disabled")
            return false
        }
        if (!result) {
            // Allow a retry after a failed main-thread setup.
            initialized.set(false)
        }
        return result
    }

    /** Body of [initialize]; must run on the main thread. */
    private fun initializeOnMainThread(): Boolean {
        try {
            // Load MediaPlayer framework first
            if (!loadMediaPlayerFramework()) {
                Logger.e(TAG, "Failed to load MediaPlayer framework")
                return false
            }

            // Get the shared MPRemoteCommandCenter
            val commandCenter = getRemoteCommandCenter()
            if (commandCenter == null) {
                Logger.e(TAG, "Failed to get MPRemoteCommandCenter")
                return false
            }

            // Setup remote commands
            setupRemoteCommands(commandCenter)

            initialized.set(true)
            Logger.d(TAG, "MacOS media integration initialized successfully (main thread)")
            return true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize MacOS media integration: ${e.message}", e)
            return false
        }
    }

    /**
     * Set the listener for remote commands
     */
    fun setRemoteCommandListener(listener: MacOSRemoteCommandListener?) {
        this.remoteCommandListener = listener
    }

    /**
     * Update Now Playing info
     *
     * Runs inside an autorelease pool, and every owned temporary (strings, numbers, the
     * dictionary) is released right after the framework retains it — without this, each
     * track change leaked a dozen native objects on a JVM thread that has no pool.
     */
    fun updateNowPlayingInfo(info: NowPlayingInfo) {
        if (!isSupported() || !initialized.get()) {
            Logger.w(TAG, "updateNowPlayingInfo: Not supported or not initialized")
            return
        }

        try {
            ObjC.autoreleasePool {
                val infoCenter = getNowPlayingInfoCenter()
                if (infoCenter == null) {
                    Logger.e(TAG, "Failed to get NowPlayingInfoCenter")
                    return@autoreleasePool
                }

                val dict = ObjC.nsMutableDictionary()
                if (dict == null) {
                    Logger.e(TAG, "Failed to create NSMutableDictionary")
                    return@autoreleasePool
                }

                // set object retains what it stores, so the only durable reference the
                // framework keeps is its own: drop our +1 on every temporary EVEN IF the
                // insert call itself fails — a mid-method JNA failure must not leak the
                // values created before it.
                fun put(
                    key: Pointer?,
                    value: Pointer?,
                ) {
                    try {
                        if (key != null && value != null) {
                            ObjC.dictionarySetObject(dict, value, key)
                        }
                    } finally {
                        ObjC.releaseOwned(value)
                    }
                }

                put(MPConstants.title, ObjC.nsString(info.title))
                put(MPConstants.artist, ObjC.nsString(info.artist))
                if (info.album.isNotEmpty()) {
                    put(MPConstants.album, ObjC.nsString(info.album))
                }
                put(MPConstants.playbackDuration, ObjC.nsNumber(info.durationSeconds))
                put(MPConstants.elapsedPlaybackTime, ObjC.nsNumber(info.elapsedTimeSeconds))
                put(MPConstants.playbackRate, ObjC.nsNumber(info.playbackRate))
                put(MPConstants.defaultPlaybackRate, ObjC.nsNumber(1.0))
                put(MPConstants.playbackQueueIndex, ObjC.nsNumber(info.queueIndex))
                put(MPConstants.playbackQueueCount, ObjC.nsNumber(info.queueCount))
                // MPNowPlayingMediaTypeAudio
                put(MPConstants.mediaType, ObjC.nsNumber(1))

                try {
                    ObjC.msg(infoCenter, "setNowPlayingInfo:", dict)
                } finally {
                    // The center retains (or copies) the dictionary; drop ours even if a
                    // call threw mid-way — otherwise the owned +1 leaks on the error path.
                    ObjC.releaseOwned(dict)
                }

                Logger.d(TAG, "Updated now playing info: ${info.title} - ${info.artist}")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to update now playing info: ${e.message}", e)
        }
    }

    /**
     * Update playback state
     */
    fun updatePlaybackState(isPlaying: Boolean) {
        if (!isSupported() || !initialized.get()) return

        try {
            ObjC.autoreleasePool {
                val infoCenter = getNowPlayingInfoCenter() ?: return@autoreleasePool
                val state = if (isPlaying) MPNowPlayingPlaybackState.Playing else MPNowPlayingPlaybackState.Paused
                ObjC.msg(infoCenter, "setPlaybackState:", state)
                Logger.d(TAG, "Updated playback state: ${if (isPlaying) "Playing" else "Paused"}")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to update playback state: ${e.message}", e)
        }
    }

    /**
     * Update the enabled state of remote command buttons
     * @param hasNext Whether there is a next track available
     * @param hasPrevious Whether there is a previous track available
     * @param canSeek Whether seeking is supported
     */
    fun updateCommandsEnabled(
        hasNext: Boolean,
        hasPrevious: Boolean,
        canSeek: Boolean = true,
    ) {
        if (!isSupported() || !initialized.get()) return

        try {
            ObjC.autoreleasePool {
                val commandCenter = getRemoteCommandCenter() ?: return@autoreleasePool

                // Note: Objective-C BOOL is actually a signed char (1 byte)
                // We use Byte (1 or 0) instead of Boolean for proper JNA mapping
                val nextEnabled: Byte = if (hasNext) 1 else 0
                val prevEnabled: Byte = if (hasPrevious) 1 else 0
                val seekEnabled: Byte = if (canSeek) 1 else 0

                // Update next track command
                val nextCommand = ObjC.msg(commandCenter, "nextTrackCommand")
                if (nextCommand != null) {
                    setCommandEnabled(nextCommand, nextEnabled)
                    Logger.d(TAG, "Next command enabled: $hasNext")
                }

                // Update previous track command
                val prevCommand = ObjC.msg(commandCenter, "previousTrackCommand")
                if (prevCommand != null) {
                    setCommandEnabled(prevCommand, prevEnabled)
                    Logger.d(TAG, "Previous command enabled: $hasPrevious")
                }

                // Update seek commands
                val seekForwardCommand = ObjC.msg(commandCenter, "seekForwardCommand")
                if (seekForwardCommand != null) {
                    setCommandEnabled(seekForwardCommand, seekEnabled)
                }

                val seekBackwardCommand = ObjC.msg(commandCenter, "seekBackwardCommand")
                if (seekBackwardCommand != null) {
                    setCommandEnabled(seekBackwardCommand, seekEnabled)
                }

                val changePositionCommand = ObjC.msg(commandCenter, "changePlaybackPositionCommand")
                if (changePositionCommand != null) {
                    setCommandEnabled(changePositionCommand, seekEnabled)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to update commands enabled state: ${e.message}", e)
        }
    }

    /**
     * Set enabled state on a command using proper Objective-C BOOL type
     * Note: On ARM64 and x86_64, BOOL is passed as int in objc_msgSend
     */
    private fun setCommandEnabled(
        command: Pointer,
        enabled: Byte,
    ) {
        // Use int for BOOL - on modern macOS/ARM64, BOOL is promoted to int in variadic functions
        val enabledInt = enabled.toInt()
        ObjCRuntime.INSTANCE.objc_msgSend(command, ObjC.sel("setEnabled:"), enabledInt)
    }

    /**
     * Update elapsed time (for seek bar progress)
     *
     * Each push is a native dictionary copy plus a dictionary swap, so the 50 ms progress
     * tick must not land here every round: [MacNowPlayingPolicy.ELAPSED_PUSH_INTERVAL_NANOS]
     * throttles to 1 Hz, and `force` bypasses the throttle for state changes (play, pause,
     * seek, track change) where the displayed clock must resync at once. When the player
     * pauses, macOS keeps advancing its own elapsed display until a push with rate 0 lands,
     * so the pause path always forces one.
     */
    fun updateElapsedTime(
        elapsedSeconds: Double,
        playbackRate: Double = 1.0,
        force: Boolean = false,
    ) {
        if (!isSupported() || !initialized.get()) return

        val now = System.nanoTime()
        synchronized(lastElapsedPushNanos) {
            if (!MacNowPlayingPolicy.isElapsedPushDue(lastElapsedPushNanos.get(), now, force)) return
            lastElapsedPushNanos.set(now)
        }

        try {
            ObjC.autoreleasePool {
                val infoCenter = getNowPlayingInfoCenter() ?: return@autoreleasePool

                // Get current now playing info
                val currentInfo = ObjC.msg(infoCenter, "nowPlayingInfo")
                if (currentInfo == null) {
                    Logger.w(TAG, "No current now playing info to update")
                    return@autoreleasePool
                }

                // Create mutable copy
                val mutableInfo = ObjC.msg(currentInfo, "mutableCopy") ?: return@autoreleasePool

                // Update elapsed time using framework constant
                val elapsedKey = MPConstants.elapsedPlaybackTime
                val elapsedValue = ObjC.nsNumber(elapsedSeconds)
                try {
                    if (elapsedKey != null && elapsedValue != null) {
                        ObjC.dictionarySetObject(mutableInfo, elapsedValue, elapsedKey)
                    }
                } finally {
                    ObjC.releaseOwned(elapsedValue)
                }

                // Update playback rate using framework constant
                val rateKey = MPConstants.playbackRate
                val rateValue = ObjC.nsNumber(playbackRate)
                try {
                    if (rateKey != null && rateValue != null) {
                        ObjC.dictionarySetObject(mutableInfo, rateValue, rateKey)
                    }
                } finally {
                    ObjC.releaseOwned(rateValue)
                }

                try {
                    // Set updated info
                    ObjC.msg(infoCenter, "setNowPlayingInfo:", mutableInfo)
                } finally {
                    // The center retains (or copies) the dictionary; drop our copy's +1
                    // even if the publish call itself failed.
                    ObjC.releaseOwned(mutableInfo)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to update elapsed time: ${e.message}", e)
        }
    }

    /**
     * Clear Now Playing info
     */
    fun clearNowPlayingInfo() {
        if (!isSupported() || !initialized.get()) return

        try {
            ObjC.autoreleasePool {
                val infoCenter = getNowPlayingInfoCenter() ?: return@autoreleasePool
                ObjC.msg(infoCenter, "setNowPlayingInfo:", null as Pointer?)
                ObjC.msg(infoCenter, "setPlaybackState:", MPNowPlayingPlaybackState.Stopped)
                Logger.d(TAG, "Cleared now playing info")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to clear now playing info: ${e.message}", e)
        }
    }

    /**
     * Callbacks and block literals retired by [release]. Deliberately NEVER freed: main-
     * thread teardown stops new invocations, but one that started just before it can still
     * be executing, and freeing the block literal (or letting GC collect the JNA
     * trampoline) under that invocation is a use-after-free. This is quantified, not an
     * unexplained leak: the integration is a process-lifetime singleton, so the bound is
     * 9 blocks — 9 callbacks and 18 JNA `Memory`s — per initialize/release cycle (tens of
     * KB; in practice one cycle per app run, because [release] is a quit-time path).
     */
    private val retiredCallbacks = ArrayList<Callback>()
    private val retiredMemories = ArrayList<Memory>()

    /**
     * Release resources. Idempotent, and safe to call from any thread.
     *
     * Teardown ordering carries the safety: (1) the NATIVE teardown (clear + target
     * removal) runs on the MAIN thread through [MainQueue.runSync] — MPRemoteCommandCenter
     * invokes handlers and mutates its target list on the main thread and its thread
     * safety is undocumented, so a worker-thread `removeTarget:` racing a main-thread
     * handler iteration is undefined; running the teardown on that same thread also makes
     * it atomic against in-flight invocations, because a handler is either already
     * finished or not yet started — never concurrent with the removal. (2) Only after the
     * native side has let go are the Kotlin references cleared, and then they are RETIRED,
     * never freed — see [retiredCallbacks].
     */
    fun release() {
        if (!isSupported()) return

        try {
            val tornDownOnMain =
                runCatching {
                    MainQueue.runSync {
                        clearNowPlayingInfo()
                        disableRemoteCommands()
                    }
                }.getOrDefault(false)
            if (!tornDownOnMain) {
                // Main-queue symbols unavailable: degrade to direct teardown rather than
                // skipping it — leaving targets registered into the next session is worse
                // than a possibly off-main removal.
                clearNowPlayingInfo()
                disableRemoteCommands()
            }
            remoteCommandListener = null

            synchronized(nativeStateLock) {
                // Retire, never free — see the doc comment above.
                retiredCallbacks.addAll(blockCallbacks)
                retiredMemories.addAll(blockMemories)
                blockCallbacks.clear()
                blockMemories.clear()
            }
            synchronized(artworkCache) {
                for (entry in artworkCache.values) {
                    releaseArtworkEntry(entry)
                }
                artworkCache.clear()
            }
            requestedArtworkUrl = null
            currentArtworkUrl = null
            lastElapsedPushNanos.set(MacNowPlayingPolicy.NEVER_PUSHED_NANOS)

            initialized.set(false)
            Logger.d(TAG, "Released MacOS media integration")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to release: ${e.message}", e)
        }
    }

    /** Release the Kotlin-owned +1 on each of an entry's native objects (innermost first). */
    private fun releaseArtworkEntry(entry: ArtworkEntry) {
        ObjC.autoreleasePool {
            ObjC.releaseOwned(entry.nsData)
            ObjC.releaseOwned(entry.nsImage)
            ObjC.releaseOwned(entry.artwork)
        }
    }

    // ============ Artwork Methods ============

    /**
     * Load artwork from URL and update Now Playing info.
     * This should be called from a coroutine.
     *
     * A newer request supersedes an older one: the download runs on IO, and if the track
     * changed while it was in flight, the landing artwork belongs to the previous track and
     * must not be applied (it would paint stale artwork onto the new track's metadata).
     */
    suspend fun loadAndSetArtwork(artworkUrl: String?) {
        if (artworkUrl.isNullOrEmpty()) {
            Logger.d(TAG, "No artwork URL provided")
            return
        }
        requestedArtworkUrl = artworkUrl

        synchronized(artworkCache) {
            val cached = artworkCache[artworkUrl]
            if (cached != null) {
                currentArtworkUrl = artworkUrl
                updateNowPlayingArtwork(cached.artwork)
                Logger.d(TAG, "Artwork applied from cache: $artworkUrl")
                return
            }
        }

        try {
            // Download image data on IO dispatcher
            val imageData =
                withContext(Dispatchers.IO) {
                    downloadImageData(artworkUrl)
                }

            if (imageData == null) {
                Logger.e(TAG, "Failed to download artwork from: $artworkUrl")
                return
            }

            // The integration may have been released while the download ran (track change on
            // a released handler, app quit): without this check the artwork would republish
            // a Now Playing dictionary after the release cleared it, or attach an old
            // session's in-flight download to a newly initialized session.
            if (!initialized.get()) {
                Logger.d(TAG, "Integration released while artwork was loading; dropped")
                return
            }

            // A newer request superseded this one while the download was in flight.
            if (requestedArtworkUrl != artworkUrl) {
                Logger.d(TAG, "Artwork for $artworkUrl superseded by a newer request; dropped")
                return
            }

            val entry = createArtworkEntry(imageData)
            if (entry != null) {
                // Publication holds the cache lock for its whole body. Without it, a
                // concurrent release() (which evicts and releases every entry) or another
                // load's evictOldArtwork() could free the artwork between the lock above and
                // the pointer use inside publication — a use-after-free on a native object.
                // With it, eviction cannot run until publication is done; the published
                // dictionary's own copy also holds a retain of the artwork, so the cache
                // entry can be evicted safely afterwards. updateNowPlayingArtwork takes no
                // locks, so this cannot deadlock.
                synchronized(artworkCache) {
                    // Two downloads of the same URL can race (a repeat or re-queue of one
                    // track): both pass the not-cached pre-check. Overwriting without
                    // releasing the displaced entry would orphan its owned native triple
                    // (NSData + NSImage + MPMediaItemArtwork), so release it exactly once
                    // here, while the map still holds it.
                    artworkCache.remove(artworkUrl)?.let { displaced ->
                        Logger.d(TAG, "Displacing duplicate artwork entry for $artworkUrl")
                        releaseArtworkEntry(displaced)
                    }
                    artworkCache[artworkUrl] = entry
                    evictOldArtwork()
                    currentArtworkUrl = artworkUrl
                    updateNowPlayingArtwork(entry.artwork)
                }
                Logger.d(TAG, "Successfully set artwork from: $artworkUrl")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error loading artwork: ${e.message}", e)
        }
    }

    /**
     * Drop least-recently-used entries beyond [ARTWORK_CACHE_LIMIT], releasing their native
     * objects. The call site holds the [artworkCache] lock.
     */
    private fun evictOldArtwork() {
        val iterator = artworkCache.entries.iterator()
        while (artworkCache.size > ARTWORK_CACHE_LIMIT && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            releaseArtworkEntry(eldest.value)
            Logger.d(TAG, "Evicted artwork from cache: ${eldest.key}")
        }
    }

    /**
     * Download image data from URL
     */
    private fun downloadImageData(urlString: String): ByteArray? =
        try {
            val url = URL(urlString)
            url.openStream().use { it.readBytes() }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to download image: ${e.message}")
            null
        }

    /**
     * Create the Kotlin-owned native artwork triple from raw image bytes.
     *
     * Every object here is owned (+1): NSData (`alloc`/`initWithBytes:` — which COPIES the
     * bytes, so the JVM-side `Memory` needs no retention), NSImage, and MPMediaItemArtwork.
     * The returned entry keeps them alive for the cache; on any failure path the objects
     * created so far are released before returning null, so nothing leaks on the way out.
     */
    private fun createArtworkEntry(imageData: ByteArray): ArtworkEntry? {
        val nsData = createNSData(imageData)
        if (nsData == null) {
            Logger.w(TAG, "Failed to create NSData for artwork")
            return null
        }
        val nsImage = createNSImage(nsData)
        if (nsImage == null) {
            Logger.w(TAG, "Failed to create NSImage for artwork")
            ObjC.releaseOwned(nsData)
            return null
        }

        val artworkClass = ObjC.cls("MPMediaItemArtwork")
        val allocated = artworkClass?.let { ObjC.msg(it, "alloc") }
        val artwork = allocated?.let { ObjC.msg(it, "initWithImage:", nsImage) }
        if (artwork == null) {
            Logger.w(TAG, "MPMediaItemArtwork creation failed; no artwork will be shown")
            // A failed initializer releases self per Cocoa convention; our temporaries are
            // ours to drop.
            ObjC.releaseOwned(nsImage)
            ObjC.releaseOwned(nsData)
            return null
        }
        return ArtworkEntry(artwork, nsImage, nsData)
    }

    /**
     * Create NSData from byte array
     *
     * `initWithBytes:length:` copies, so the JVM-side Memory needs no retention afterwards —
     * unlike a `NoCopy` variant. (Adding it to `blockMemories` here used to pin every
     * artwork's byte buffer for the life of the process.)
     */
    private fun createNSData(bytes: ByteArray): Pointer? {
        val nsDataClass = ObjC.cls("NSData") ?: return null

        // Copy bytes to native memory for the call
        val nativeMemory = Memory(bytes.size.toLong())
        nativeMemory.write(0, bytes, 0, bytes.size)

        // Create NSData with bytes:length:
        val allocated = ObjC.msg(nsDataClass, "alloc") ?: return null
        return ObjCRuntime.INSTANCE.objc_msgSend(
            allocated,
            ObjC.sel("initWithBytes:length:"),
            nativeMemory,
            bytes.size.toLong(),
        )
    }

    /**
     * Create NSImage from NSData
     */
    private fun createNSImage(nsData: Pointer): Pointer? {
        val nsImageClass = ObjC.cls("NSImage") ?: return null
        val allocated = ObjC.msg(nsImageClass, "alloc") ?: return null
        return ObjC.msg(allocated, "initWithData:", nsData)
    }

    /**
     * Update the current now playing info with artwork
     */
    private fun updateNowPlayingArtwork(artwork: Pointer) {
        // Lifecycle guard, like its siblings updateNowPlayingInfo/updateElapsedTime: an
        // artwork download that landed while release() was clearing the session must not
        // republish a Now Playing dictionary afterwards (an artwork-only dict would
        // resurrect cleared metadata).
        if (!isSupported() || !initialized.get()) return
        try {
            ObjC.autoreleasePool {
                val infoCenter = getNowPlayingInfoCenter() ?: return@autoreleasePool

                // Get current now playing info
                val currentInfo = ObjC.msg(infoCenter, "nowPlayingInfo") ?: return@autoreleasePool

                // Create mutable copy
                val mutableInfo = ObjC.msg(currentInfo, "mutableCopy") ?: return@autoreleasePool

                // Set artwork
                val artworkKey = MPConstants.artwork
                if (artworkKey != null) {
                    ObjC.dictionarySetObject(mutableInfo, artwork, artworkKey)
                }

                try {
                    // Set updated info
                    ObjC.msg(infoCenter, "setNowPlayingInfo:", mutableInfo)
                } finally {
                    // Owned +1 released even if the publish call itself fails.
                    ObjC.releaseOwned(mutableInfo)
                }

                Logger.d(TAG, "Updated now playing artwork")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to update artwork: ${e.message}", e)
        }
    }

    // ============ Private helper methods ============

    private fun getNowPlayingInfoCenter(): Pointer? {
        val cls = ObjC.cls("MPNowPlayingInfoCenter") ?: return null
        return ObjC.msg(cls, "defaultCenter")
    }

    private fun getRemoteCommandCenter(): Pointer? {
        val cls = ObjC.cls("MPRemoteCommandCenter") ?: return null
        return ObjC.msg(cls, "sharedCommandCenter")
    }

    /**
     * Create a simple block that calls back to Kotlin
     * This is a simplified implementation - for production, consider using a library like JObjC
     */
    private fun createCommandHandler(handler: (Pointer?) -> Int): Pointer? {
        // Create a callback
        val callback =
            object : Callback {
                @Suppress("unused")
                fun invoke(
                    block: Pointer?,
                    event: Pointer?,
                ): Int {
                    Logger.d(TAG, "Block callback invoked! block=$block, event=$event")
                    // Catch Throwable, not Exception: an Error escaping `invoke` would
                    // propagate back into the ObjC block invocation on the main thread —
                    // undefined behavior, because it crosses the JNA trampoline into
                    // native code. Every failure is logged at Error level; nothing is
                    // silently swallowed.
                    return try {
                        handler(event)
                    } catch (e: Throwable) {
                        Logger.e(TAG, "Command handler error: ${e.message}", e)
                        MPRemoteCommandHandlerStatus.CommandFailed
                    }
                }
            }

        // Get the callback function pointer using CallbackReference
        val callbackPointer = CallbackReference.getFunctionPointer(callback)

        // Create block descriptor
        val descriptorSize = 40L // reserved + size + copy + dispose + signature pointers
        val descriptorMemory = Memory(descriptorSize)
        descriptorMemory.clear()
        descriptorMemory.setLong(0, 0) // reserved
        descriptorMemory.setLong(8, 32) // size of block literal

        // Create block literal
        val blockSize = 32L
        val blockMemory = Memory(blockSize)
        blockMemory.clear()

        // A global block needs no copy/dispose and outlives any stack frame, so the invoke
        // pointer is safe as long as the JNA callback object stays strongly referenced.
        // If `__NSGlobalBlock__` is somehow not registered, FAIL rather than fabricate a
        // block with an uncertain isa identity: the caller leaves the command disabled and
        // logs it — a recoverable degradation, while a wrong-isa block is not.
        val nsConcreteGlobalBlock = ObjC.cls("__NSGlobalBlock__") ?: return null

        blockMemory.setPointer(0, nsConcreteGlobalBlock) // isa
        blockMemory.setInt(8, BlockLiteral.BLOCK_IS_GLOBAL) // flags
        blockMemory.setInt(12, 0) // reserved
        blockMemory.setPointer(16, callbackPointer) // invoke
        blockMemory.setPointer(24, descriptorMemory) // descriptor

        // Keep references to prevent GC — under the same lock `release()` clears them with.
        synchronized(nativeStateLock) {
            blockCallbacks.add(callback)
            blockMemories.add(descriptorMemory)
            blockMemories.add(blockMemory)
        }
        Logger.d(TAG, "Created callback: $callback")

        return blockMemory
    }

    private fun setupRemoteCommands(commandCenter: Pointer) {
        // Play command
        setupCommand(commandCenter, "playCommand") { _ ->
            remoteCommandListener?.onPlay()
            MPRemoteCommandHandlerStatus.Success
        }

        // Pause command
        setupCommand(commandCenter, "pauseCommand") { _ ->
            remoteCommandListener?.onPause()
            MPRemoteCommandHandlerStatus.Success
        }

        // Toggle play/pause command
        setupCommand(commandCenter, "togglePlayPauseCommand") { _ ->
            remoteCommandListener?.onTogglePlayPause()
            MPRemoteCommandHandlerStatus.Success
        }

        // Stop command
        setupCommand(commandCenter, "stopCommand") { _ ->
            remoteCommandListener?.onStop()
            MPRemoteCommandHandlerStatus.Success
        }

        // Next track command
        setupCommand(commandCenter, "nextTrackCommand") { _ ->
            remoteCommandListener?.onNextTrack()
            MPRemoteCommandHandlerStatus.Success
        }

        // Previous track command
        setupCommand(commandCenter, "previousTrackCommand") { _ ->
            remoteCommandListener?.onPreviousTrack()
            MPRemoteCommandHandlerStatus.Success
        }

        // Seek forward command
        setupCommand(commandCenter, "seekForwardCommand") { _ ->
            remoteCommandListener?.onSeekForward()
            MPRemoteCommandHandlerStatus.Success
        }

        // Seek backward command
        setupCommand(commandCenter, "seekBackwardCommand") { _ ->
            remoteCommandListener?.onSeekBackward()
            MPRemoteCommandHandlerStatus.Success
        }

        // Change playback position command (for seek bar scrubbing)
        setupChangePositionCommand(commandCenter)

        Logger.d(TAG, "Remote commands setup completed")
    }

    private fun setupCommand(
        commandCenter: Pointer,
        commandName: String,
        handler: (Pointer?) -> Int,
    ) {
        try {
            val command = ObjC.msg(commandCenter, commandName)
            if (command == null) {
                Logger.e(TAG, "Failed to get remote command: $commandName")
                return
            }

            // Register the handler FIRST; enable the command only after it exists. An
            // enabled command without a target falls through to the system's default
            // behavior, so a failed block creation must leave the command disabled.
            val handlerBlock = createCommandHandler(handler)
            if (handlerBlock == null) {
                Logger.e(TAG, "Failed to create handler block for $commandName — command left disabled")
                return
            }
            ObjC.msg(command, "addTargetWithHandler:", handlerBlock)
            setCommandEnabled(command, 1)
            Logger.d(TAG, "Setup command: $commandName")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to setup command $commandName: ${e.message}", e)
        }
    }

    private fun setupChangePositionCommand(commandCenter: Pointer) {
        try {
            val command = ObjC.msg(commandCenter, "changePlaybackPositionCommand")
            if (command == null) {
                Logger.e(TAG, "Failed to get remote command: changePlaybackPositionCommand")
                return
            }

            // Create handler that extracts position from event
            val handlerBlock =
                createCommandHandler { event ->
                    Logger.d(TAG, "changePlaybackPositionCommand handler invoked! event=$event")
                    if (event != null) {
                        try {
                            // Get positionTime from MPChangePlaybackPositionCommandEvent
                            // Use ObjCRuntimeDouble for double return value (works on both ARM64 and x86_64)
                            val positionTime =
                                ObjCRuntimeDouble.INSTANCE.objc_msgSend(
                                    event,
                                    ObjC.sel("positionTime"),
                                )
                            Logger.d(TAG, "Seek position from system: $positionTime seconds")
                            remoteCommandListener?.onChangePlaybackPosition(positionTime)
                        } catch (e: Exception) {
                            Logger.e(TAG, "Failed to get position time: ${e.message}", e)
                        }
                    } else {
                        Logger.w(TAG, "changePlaybackPositionCommand: event is null!")
                    }
                    MPRemoteCommandHandlerStatus.Success
                }

            // Register first, enable only after the handler exists (same reasoning as
            // [setupCommand]).
            if (handlerBlock != null) {
                ObjC.msg(command, "addTargetWithHandler:", handlerBlock)
                setCommandEnabled(command, 1)
                Logger.d(TAG, "Setup command: changePlaybackPositionCommand with handler block: $handlerBlock")
            } else {
                Logger.e(TAG, "Failed to create handler block for changePlaybackPositionCommand — command left disabled")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to setup changePlaybackPositionCommand: ${e.message}", e)
        }
    }

    private fun disableRemoteCommands() {
        try {
            val commandCenter = getRemoteCommandCenter() ?: return

            val commands =
                listOf(
                    "playCommand",
                    "pauseCommand",
                    "togglePlayPauseCommand",
                    "stopCommand",
                    "nextTrackCommand",
                    "previousTrackCommand",
                    "seekForwardCommand",
                    "seekBackwardCommand",
                    "changePlaybackPositionCommand",
                )

            for (commandName in commands) {
                val command = ObjC.msg(commandCenter, commandName)
                if (command != null) {
                    setCommandEnabled(command, 0) // Disable using proper BOOL type
                    ObjC.msg(command, "removeTarget:", null as Pointer?)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to disable remote commands: ${e.message}", e)
        }
    }
}