package com.maxrave.simpmusic.viewModel.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.isRadioQueueId
import com.maxrave.logger.LogLevel
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import multiplatform.network.cmptoast.ToastDuration
import multiplatform.network.cmptoast.ToastGravity
import multiplatform.network.cmptoast.showToast
import org.jetbrains.compose.resources.StringResource
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.add_all_to_favorites_already
import simpmusic.composeapp.generated.resources.add_all_to_favorites_done
import simpmusic.composeapp.generated.resources.add_all_to_favorites_done_skipped
import simpmusic.composeapp.generated.resources.add_all_to_favorites_result
import simpmusic.composeapp.generated.resources.error
import simpmusic.composeapp.generated.resources.import_progress_songs
import simpmusic.composeapp.generated.resources.loading

abstract class BaseViewModel :
    ViewModel(),
    KoinComponent {
    protected val mediaPlayerHandler: MediaPlayerHandler by inject<MediaPlayerHandler>()
    private val _nowPlayingVideoId: MutableStateFlow<String> = MutableStateFlow("")

    /**
     * Get now playing video id
     * If empty, no video is playing
     */
    val nowPlayingVideoId: StateFlow<String> get() = _nowPlayingVideoId

    /**
     * Tag for logging
     */
    protected val tag: String = this::class.simpleName ?: "BaseViewModel"

    /**
     * Log with viewModel tag
     */
    protected fun log(
        message: String,
        logType: LogLevel = LogLevel.DEBUG,
    ) {
        when (logType) {
            LogLevel.DEBUG -> Logger.d(tag, message)
            LogLevel.INFO -> Logger.i(tag, message)
            LogLevel.WARN -> Logger.w(tag, message)
            LogLevel.ERROR -> Logger.e(tag, message)
        }
    }

    /**
     * Cancel all jobs
     */
    override fun onCleared() {
        super.onCleared()
        viewModelScope.cancel()
        log("ViewModel cleared", LogLevel.WARN)
    }

    init {
        getNowPlayingVideoId()
    }

    fun makeToast(message: String?) {
        showToast(
            message = message ?: "NO MESSAGE",
            duration = ToastDuration.Short,
            gravity = ToastGravity.Bottom,
        )
    }

    protected fun getString(resId: StringResource): String =
        runBlocking {
            org.jetbrains.compose.resources
                .getString(resId)
        }

    protected fun getString(
        resId: StringResource,
        vararg formatArgs: Any,
    ): String =
        runBlocking {
            org.jetbrains.compose.resources
                .getString(resId, *formatArgs)
        }

    // Loading dialog
    private val _showLoadingDialog: MutableStateFlow<Pair<Boolean, String>> = MutableStateFlow(false to getString(Res.string.loading))
    val showLoadingDialog: StateFlow<Pair<Boolean, String>> get() = _showLoadingDialog

    fun showLoadingDialog(message: String? = null) {
        _showLoadingDialog.value = true to (message ?: getString(Res.string.loading))
    }

    fun hideLoadingDialog() {
        _showLoadingDialog.value = false to getString(Res.string.loading)
    }

    // Bulk favorite
    private var addAllToFavoritesJob: Job? = null

    /**
     * Adds every song behind [loadVideoIds] to Naudio's Favorites (`song.liked`), the same local
     * collection the heart button writes to. Songs already favorited are skipped, and
     * [loadVideoIds] returning null means the collection could not be loaded.
     */
    protected fun addAllSongsToFavorites(
        songRepository: SongRepository,
        emptyMessage: String,
        loadVideoIds: suspend () -> List<String>?,
    ) {
        if (addAllToFavoritesJob?.isActive == true) return
        addAllToFavoritesJob =
            viewModelScope.launch {
                showLoadingDialog()
                try {
                    val videoIds = loadVideoIds()
                    when {
                        videoIds == null -> {
                            makeToast(getString(Res.string.error))
                            return@launch
                        }

                        videoIds.isEmpty() -> {
                            makeToast(emptyMessage)
                            return@launch
                        }
                    }
                    val distinctIds = videoIds.distinct()
                    val songs =
                        distinctIds.chunked(FAVORITES_QUERY_CHUNK).flatMap { chunk ->
                            songRepository.getSongsByListVideoId(chunk).firstOrNull().orEmpty()
                        }
                    val plan =
                        planAddAllToFavorites(
                            requestedVideoIds = distinctIds,
                            likedById = songs.associate { it.videoId to it.liked },
                        )
                    val result =
                        applyAddAllToFavorites(
                            plan = plan,
                            favorite = { songRepository.updateLikeStatus(it, 1) },
                            onFailure = { videoId, e ->
                                log("Could not favorite $videoId: ${e.message}", LogLevel.ERROR)
                            },
                            onProgress = { done, total ->
                                if (done % FAVORITES_PROGRESS_STEP == 0 || done == total) {
                                    showLoadingDialog(
                                        getString(Res.string.import_progress_songs, done, total),
                                    )
                                }
                            },
                        )
                    log(
                        "Add all to Favorites: ${result.added} added, " +
                            "${result.skipped} skipped, ${result.failed} failed",
                    )
                    makeToast(addAllToFavoritesMessage(result))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Add all to Favorites failed: ${e.message}", LogLevel.ERROR)
                    makeToast(getString(Res.string.error))
                } finally {
                    hideLoadingDialog()
                    addAllToFavoritesJob = null
                }
            }
    }

    private fun addAllToFavoritesMessage(result: AddAllToFavoritesResult): String =
        when {
            result.added == 0 && result.failed == 0 -> {
                getString(Res.string.add_all_to_favorites_already)
            }

            result.failed == 0 && result.skipped == 0 -> {
                getString(Res.string.add_all_to_favorites_done, result.added)
            }

            result.failed == 0 -> {
                getString(Res.string.add_all_to_favorites_done_skipped, result.added, result.skipped)
            }

            else -> {
                getString(
                    Res.string.add_all_to_favorites_result,
                    result.added,
                    result.skipped,
                    result.failed,
                )
            }
        }

    private fun getNowPlayingVideoId() {
        viewModelScope.launch {
            combine(mediaPlayerHandler.nowPlayingState, mediaPlayerHandler.controlState) { nowPlayingState, controlState ->
                Pair(nowPlayingState, controlState)
            }.collect { (nowPlayingState, controlState) ->
                if (controlState.isPlaying) {
                    _nowPlayingVideoId.value = nowPlayingState.songEntity?.videoId ?: ""
                } else {
                    _nowPlayingVideoId.value = ""
                }
            }
        }
    }

    /**
     * Communicate with SimpleMediaServiceHandler to load media item
     */
    fun setQueueData(queueData: QueueData.Data) {
        mediaPlayerHandler.reset()
        mediaPlayerHandler.setQueueData(queueData)
    }

    fun <T> loadMediaItem(
        anyTrack: T,
        type: String,
        index: Int? = null,
    ) {
        viewModelScope.launch {
            mediaPlayerHandler.loadMediaItem(
                anyTrack = anyTrack,
                type = type,
                index = index,
            )
        }
    }

    fun shufflePlaylist(firstPlayIndex: Int = 0) {
        mediaPlayerHandler.shufflePlaylist(firstPlayIndex)
    }

    private val playbackSongRepository: SongRepository by inject<SongRepository>()

    /**
     * Plays what a YouTube play button points at, as the queue `/next` returns for it: a song's
     * radio (an `RD…` id), or a playlist, an album, a shelf's "Play all" as a plain list. Used by
     * Home's card play buttons and shelf headers, so they never need to open a page first.
     */
    fun playFromEndpoint(
        endpoint: YouTubeWatchEndpoint,
        name: String,
    ) {
        viewModelScope.launch {
            playbackSongRepository.getRadioFromEndpoint(endpoint).collectLatest { res ->
                val tracks = res.data?.first
                if (res is Resource.Success && !tracks.isNullOrEmpty()) {
                    val isRadio = endpoint.playlistId?.isRadioQueueId() == true
                    setQueueData(
                        QueueData.Data(
                            listTracks = tracks.toCollection(arrayListOf<Track>()),
                            firstPlayedTrack = tracks.first(),
                            playlistId = endpoint.playlistId,
                            playlistName = name,
                            playlistType = if (isRadio) PlaylistType.RADIO else PlaylistType.PLAYLIST,
                            continuation = res.data?.second,
                        ),
                    )
                    loadMediaItem(tracks.first(), if (isRadio) Config.RADIO_CLICK else Config.PLAYLIST_CLICK, 0)
                } else {
                    makeToast(res.message ?: getString(Res.string.error))
                }
            }
        }
    }

    private companion object {
        const val FAVORITES_QUERY_CHUNK = 500
        const val FAVORITES_PROGRESS_STEP = 10
    }
}

internal data class AddAllToFavoritesPlan(
    val pending: List<String>,
    val skipped: Int,
    val failed: Int,
)

/**
 * Deduplicates the requested songs, drops the ones already in Favorites, and counts the ones with
 * no stored row to update. Kept pure so the counting can be tested without a ViewModel.
 */
internal fun planAddAllToFavorites(
    requestedVideoIds: List<String>,
    likedById: Map<String, Boolean>,
): AddAllToFavoritesPlan {
    val pending = ArrayList<String>(requestedVideoIds.size)
    var skipped = 0
    var failed = 0
    requestedVideoIds.distinct().forEach { videoId ->
        when (likedById[videoId]) {
            null -> failed++
            true -> skipped++
            false -> pending += videoId
        }
    }
    return AddAllToFavoritesPlan(pending, skipped, failed)
}

internal data class AddAllToFavoritesResult(
    val added: Int,
    val skipped: Int,
    val failed: Int,
)

/**
 * Writes [AddAllToFavoritesPlan.pending] one song at a time, so a failure only fails that song.
 * [favorite] is the local Favorites write, never a YouTube like.
 */
internal suspend fun applyAddAllToFavorites(
    plan: AddAllToFavoritesPlan,
    favorite: suspend (String) -> Unit,
    onFailure: (String, Exception) -> Unit = { _, _ -> },
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): AddAllToFavoritesResult {
    var added = 0
    var failed = plan.failed
    plan.pending.forEachIndexed { index, videoId ->
        try {
            favorite(videoId)
            added++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed++
            onFailure(videoId, e)
        }
        onProgress(index + 1, plan.pending.size)
    }
    return AddAllToFavoritesResult(added, plan.skipped, failed)
}