package com.maxrave.media3.service

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.maxrave.common.MEDIA_NOTIFICATION
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.shouldHoldPlaybackLocks
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.extension.toCommandButton
import com.maxrave.media3.utils.CoilBitmapLoader
import com.maxrave.media3.utils.sizeLimitedForSession
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlin.system.exitProcess

@UnstableApi
internal class SimpleMediaService :
    MediaLibraryService(),
    KoinComponent {
    // Session-level player from DI: the ForwardingPlayer wrapped with Cast support in the
    // full build (plain ForwardingPlayer in the FOSS build).
    private val player: Player by inject<Player>(qualifier = named(com.maxrave.common.Config.MAIN_PLAYER))
    private val coilBitmapLoader: CoilBitmapLoader by inject<CoilBitmapLoader>()

    private var mediaSession: MediaLibrarySession? = null

    private val simpleMediaSessionCallback: MediaLibrarySession.Callback by inject<MediaLibrarySession.Callback>()

    private val simpleMediaServiceHandler: MediaPlayerHandler by inject<MediaPlayerHandler>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private fun isConnectedToWifi(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    private fun acquirePlaybackLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Naudio:PlaybackWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                // Indefinite acquisition tied strictly to playback lifecycle.
                // An arbitrary timeout (e.g. 30 min) causes system PowerManagerService to drop the lock
                // during continuous playback, leading to deep sleep and audio stoppage on track transitions.
                wakeLock?.acquire()
                Logger.i("NaudioBackground", "WakeLock: Acquired partial WakeLock for playback (held=${wakeLock?.isHeld})")
            } else {
                Logger.d("NaudioBackground", "WakeLock: Already held (held=${wakeLock?.isHeld})")
            }

            // Only hold WifiLock when actively connected to Wi-Fi to avoid unnecessary battery drain on mobile data
            val onWifi = isConnectedToWifi()
            if (onWifi) {
                if (wifiLock == null) {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Naudio:PlaybackWifiLock")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (wifiLock?.isHeld == false) {
                    wifiLock?.acquire()
                    Logger.i("NaudioBackground", "WifiLock: Acquired for Wi-Fi playback (held=${wifiLock?.isHeld})")
                } else {
                    Logger.d("NaudioBackground", "WifiLock: Already held on Wi-Fi (held=${wifiLock?.isHeld})")
                }
            } else if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Logger.i("NaudioBackground", "WifiLock: Released (device is on mobile data, held=${wifiLock?.isHeld})")
            }
        } catch (e: Exception) {
            Logger.w("NaudioBackground", "Failed to acquire playback locks: ${e.message}")
        }
    }

    private fun releasePlaybackLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Logger.i("NaudioBackground", "WakeLock: Released (held=${wakeLock?.isHeld})")
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Logger.i("NaudioBackground", "WifiLock: Released (held=${wifiLock?.isHeld})")
            }
        } catch (e: Exception) {
            Logger.w("NaudioBackground", "Failed to release playback locks: ${e.message}")
        }
    }

    private val binder = MusicBinder()

    inner class MusicBinder : Binder() {
        val service: SimpleMediaService
            get() = this@SimpleMediaService

        fun setActivitySession(
            context: Context,
            activity: Class<out Activity>,
        ) {
            mediaSession?.setSessionActivity(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, activity),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Logger.w("Service", "Simple Media Service Bound")
        return super.onBind(intent) ?: binder
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        Logger.w("Service", "Simple Media Service Created")

        // The only signal that Android refused to put the service in the foreground (SDK 31+).
        // Media3 swallows the exception otherwise, and the service then dies as "app idle" about a
        // minute after the screen goes off.
        setListener(
            object : MediaSessionService.Listener {
                override fun onForegroundServiceStartNotAllowedException() {
                    Logger.w(
                        "Service",
                        "Foreground-service start refused by the system (app in background)",
                    )
                }
            },
        )

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { MEDIA_NOTIFICATION.NOTIFICATION_ID },
                MEDIA_NOTIFICATION.NOTIFICATION_CHANNEL_ID,
                R.string.notification_channel_name,
            ).apply {
                setSmallIcon(R.drawable.mono)
            },
        )

        if (mediaSession == null) {
            mediaSession =
                provideMediaLibrarySession(
                    this,
                    player,
                    simpleMediaSessionCallback,
                )
        }

        // Nothing in the app ever connects a MediaController, and media3 only learns about a
        // session when one does (or via addSession). Without this the notification manager never
        // creates its controller, onUpdateNotification never runs, and the service is never put in
        // the foreground - it then dies as "app idle" about a minute after the screen goes off.
        mediaSession?.let { addSession(it) }

        simpleMediaServiceHandler.onUpdateNotification = { list ->
            val commandButtonList = list.map { it.toCommandButton(this) }
            mediaSession?.setMediaButtonPreferences(
                commandButtonList,
            )
        }
    }

    @UnstableApi
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Logger.w("Service", "Simple Media Service Received Action: ${intent?.action}")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        Logger.i(
            "Service",
            "Session requested by ${controllerInfo.packageName} " +
                "notification=${mediaSession?.isMediaNotificationController(controllerInfo)}",
        )
        return mediaSession
    }

    @UnstableApi
    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        val ongoing = isPlaybackOngoing()
        val handlerPlayer = simpleMediaServiceHandler.player
        val shouldHoldLocks = shouldHoldPlaybackLocks(
            sessionPlayWhenReady = session.player.playWhenReady,
            handlerPlayWhenReady = handlerPlayer.playWhenReady,
            sessionPlaybackState = session.player.playbackState,
            handlerPlaybackState = handlerPlayer.playbackState,
            isPlaybackOngoing = ongoing,
        )
        Logger.i(
            "NaudioBackground",
            "Notification update: required=$startInForegroundRequired " +
                "foreground=$ongoing " +
                "playWhenReady=${session.player.playWhenReady} (handler=${handlerPlayer.playWhenReady}) " +
                "state=${session.player.playbackState} (handler=${handlerPlayer.playbackState}) " +
                "items=${session.player.mediaItemCount} " +
                "playing=${session.player.isPlaying} " +
                "shouldHoldLocks=$shouldHoldLocks " +
                "wakeLockHeld=${wakeLock?.isHeld} " +
                "wifiLockHeld=${wifiLock?.isHeld} " +
                "onWifi=${isConnectedToWifi()}",
        )
        if (shouldHoldLocks) {
            acquirePlaybackLocks()
        } else {
            releasePlaybackLocks()
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    // Releases the session and stops playback. The handler is a process-wide Koin singleton, so
    // this must NOT release it: a service recreated in the same process reuses that handler.
    private fun releaseSession() {
        val session = mediaSession ?: return
        mediaSession = null
        try {
            session.player.pause()
            session.player.playWhenReady = false
        } catch (e: Exception) {
            Logger.e("Service", "Error stopping player during session release")
        } finally {
            try {
                // Don't call session.player.release() - CrossfadeExoPlayerAdapter manages player lifecycle
                session.release()
                Logger.w("Service", "Media session released")
            } catch (e: Exception) {
                Logger.e("Service", "Error during session release")
            }
        }
    }

    // Only for real process shutdown (task removed with kill-on-exit enabled).
    @UnstableApi
    fun release() {
        Logger.w("Service", "Starting release process")
        releasePlaybackLocks()
        releaseSession()
        try {
            // Release handler (contains coroutines and jobs, which also releases the adapter)
            simpleMediaServiceHandler.release()
            Logger.w("Service", "Simple Media Service Released")
        } catch (e: Exception) {
            Logger.e("Service", "Error during release")
        }
    }

    @UnstableApi
    override fun onDestroy() {
        Logger.w(
            "NaudioBackground",
            "Simple Media Service Destroyed (foreground=${isPlaybackOngoing()}, " +
                "playWhenReady=${simpleMediaServiceHandler.player.playWhenReady})",
        )
        releasePlaybackLocks()
        super.onDestroy()
        simpleMediaServiceHandler.onUpdateNotification = {}
        releaseSession()
    }

    override fun onTrimMemory(level: Int) {
        Logger.w("Service", "Simple Media Service Trim Memory Level: $level")
        simpleMediaServiceHandler.mayBeSaveRecentSong()
    }

    @UnstableApi
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i(
            "Service",
            "Simple Media Service Task Removed (playWhenReady=${simpleMediaServiceHandler.player.playWhenReady})",
        )
        if (simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()) {
            release()
            super.onTaskRemoved(rootIntent)
            exitProcess(0)
        } else if (!simpleMediaServiceHandler.player.playWhenReady) {
            // Media3's default stops the service when it is not playing, which is what makes
            // onDestroy (and therefore releaseSession) run on a normal swipe-away. It asks the
            // player's instantaneous isPlaying, which is false during every buffer/track gap, so a
            // swipe landing in one of those stopped the service and the music with it even though
            // playback was intended. Only stop when the listener actually paused.
            super.onTaskRemoved(rootIntent)
        }
    }

    // Can't inject by Koin because it depend on service
    @UnstableApi
    private fun provideMediaLibrarySession(
        service: MediaLibraryService,
        player: Player,
        callback: MediaLibrarySession.Callback,
    ): MediaLibrarySession =
        MediaLibrarySession
            .Builder(
                service,
                player,
                callback,
            ).setId(this.javaClass.name)
            // Capped at the platform's own artwork limit so the framework never rescales the shared
            // bitmap in setMetadata — see sizeLimitedForSession (#2500). Falls back to the plain
            // loader if the limit cannot be read.
            .setBitmapLoader(sizeLimitedForSession(service, coilBitmapLoader))
            // Tapping the media notification opens whatever the session activity points at, and the
            // only other place that sets it is MainActivity's bind. A service started without that
            // bind — a headset/Bluetooth play, Android Auto, a widget, a restart after the process
            // was killed — would otherwise post a notification that opens nothing. The launcher
            // activity is resolved at runtime, so this module never has to name MainActivity.
            .apply {
                service.packageManager.getLaunchIntentForPackage(service.packageName)?.let { launch ->
                    setSessionActivity(
                        PendingIntent.getActivity(service, 0, launch, PendingIntent.FLAG_IMMUTABLE),
                    )
                }
            }
            .build()

    private fun isAppInForeground(): Boolean {
        val appProcessInfo = RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        return appProcessInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}