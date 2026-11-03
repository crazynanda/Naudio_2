package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.type.ArtistType
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.isLive
import com.maxrave.domain.utils.toListName
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.ui.icon.Add
import com.maxrave.simpmusic.ui.icon.Check
import com.maxrave.simpmusic.ui.icon.DownloadForOffline
import com.maxrave.simpmusic.ui.icon.DragHandle
import com.maxrave.simpmusic.ui.icon.MoreVert
import com.maxrave.simpmusic.ui.icon.PushPin
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.SkipNext
import com.maxrave.simpmusic.ui.theme.LocalForceDarkText
import com.maxrave.simpmusic.ui.theme.seed
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.album
import simpmusic.composeapp.generated.resources.artists
import simpmusic.composeapp.generated.resources.play_next
import simpmusic.composeapp.generated.resources.playlist
import simpmusic.composeapp.generated.resources.podcasts
import simpmusic.composeapp.generated.resources.radio
import simpmusic.composeapp.generated.resources.you
import kotlin.math.roundToInt

/**
 * This is the song item in the playlist or other places.
 *
 * Multi-selection is opt-in per screen: pass [onLongClick] to let a long press start it, then
 * drive [selectionMode] and [isSelected] from the screen's
 * [com.maxrave.simpmusic.ui.component.selection.SongSelectionState].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongFullWidthItems(
    track: Track? = null,
    index: Int? = null,
    songEntity: SongEntity? = null,
    isPlaying: Boolean,
    shouldShowDragHandle: Boolean = false,
    onMoreClickListener: ((videoId: String) -> Unit)? = null,
    onClickListener: ((videoId: String) -> Unit)? = null,
    onAddToQueue: ((videoId: String) -> Unit)? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: ((videoId: String) -> Unit)? = null,
    onSelectToggle: ((videoId: String) -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
    rightView: @Composable (() -> Unit)? = null,
    forceDark: Boolean = LocalForceDarkText.current,
) {
    val contentColor = if (forceDark) Color.White else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (forceDark) Color(0xC4FFFFFF) else MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val maxOffset = with(density) { 96.dp.toPx() }
    val swipeThreshold = with(density) { 48.dp.toPx() }
    val coroutineScope = rememberCoroutineScope()
    val songRepository: SongRepository = koinInject<SongRepository>()
    val storedSong by songRepository
        .getSongAsFlow(songEntity?.videoId ?: track?.videoId ?: "")
        .collectAsState(initial = null)
    val downloadState = storedSong?.downloadState ?: DownloadState.STATE_NOT_DOWNLOADED
    // The list's own model says what its source said; the stored row adds what playback learned
    // since, which is the only way a queue row is marked — the queue response shows no LIVE chip.
    val isLive = track?.isLive == true || songEntity?.isLive == true || storedSong?.isLive == true
    val itemVideoId = track?.videoId ?: songEntity?.videoId ?: ""
    val offsetX = remember { Animatable(initialValue = 0f) }
    var heightDp by remember { mutableStateOf(0.dp) }
    // Read in the gesture coroutine, never in composition: keying pointerInput on these (or on
    // offsetX, which changes every frame) tore the detector down mid-drag and swallowed the swipe.
    val currentItemVideoId by rememberUpdatedState(itemVideoId)
    val currentIsPlaying by rememberUpdatedState(isPlaying)
    val currentOnAddToQueue by rememberUpdatedState(onAddToQueue)
    val showAddToQueue by remember { derivedStateOf { offsetX.value >= swipeThreshold } }

    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Crossfade(
            showAddToQueue,
            modifier = Modifier.align(Alignment.CenterStart),
        ) { shouldShowAddToQueue ->
            if (shouldShowAddToQueue) {
                Box(
                    modifier =
                        Modifier
                            .height(heightDp)
                            .aspectRatio(1f)
                            .padding(start = 15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        tint = contentColor,
                        imageVector = SimpIcons.SkipNext,
                        contentDescription = stringResource(Res.string.play_next),
                    )
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .background(
                        if (isSelected) seed.copy(alpha = 0.18f) else Color.Transparent,
                    ).combinedClickable(
                        onClick = {
                            if (selectionMode) {
                                onSelectToggle?.invoke(itemVideoId)
                            } else {
                                onClickListener?.invoke(itemVideoId)
                            }
                        },
                        onLongClick =
                            if (onLongClick != null) {
                                { onLongClick(itemVideoId) }
                            } else {
                                null
                            },
                    ).animateContentSize()
                    // Only selection restarts the detector. The row's playing state and callback are
                    // read through rememberUpdatedState at gesture time; offsetX is read through a
                    // derived state, so a drag no longer recomposes the row on every frame.
                    .pointerInput(selectionMode) {
                        // The decision is taken from this accumulation, not from offsetX:
                        // snapTo is launched per pointer event and may not have run yet when
                        // the last event arrives, so reading the Animatable can miss a drag
                        // that clearly passed the threshold.
                        var active = false
                        var draggedPx = 0f
                        var loggedThreshold = false
                        detectHorizontalDragGestures(
                            onDragStart = {
                                active = !selectionMode && !currentIsPlaying && currentOnAddToQueue != null
                                draggedPx = 0f
                                loggedThreshold = false
                                if (active) {
                                    Logger.i(
                                        "SwipeGesture",
                                        "start ${currentItemVideoId} playing=$currentIsPlaying",
                                    )
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (active) {
                                    val previous = draggedPx
                                    draggedPx = (draggedPx + dragAmount).coerceIn(0f, maxOffset)
                                    if (!loggedThreshold && previous < swipeThreshold && draggedPx >= swipeThreshold) {
                                        loggedThreshold = true
                                        Logger.i(
                                            "SwipeGesture",
                                            "threshold ${currentItemVideoId} ${draggedPx.roundToInt()}px",
                                        )
                                    }
                                    change.consume()
                                    coroutineScope.launch {
                                        offsetX.snapTo(draggedPx)
                                    }
                                }
                            },
                            onDragEnd = {
                                Logger.i(
                                    "SwipeGesture",
                                    "end ${currentItemVideoId} ${draggedPx.roundToInt()}px active=$active",
                                )
                                if (active) {
                                    val callback = currentOnAddToQueue
                                    if (draggedPx >= swipeThreshold && callback != null) {
                                        Logger.i(
                                            "SongSwipe",
                                            "play next: ${currentItemVideoId} (${draggedPx.roundToInt()}px)",
                                        )
                                        callback(currentItemVideoId)
                                    }
                                    coroutineScope.launch {
                                        offsetX.animateTo(0f)
                                    }
                                }
                                active = false
                            },
                            onDragCancel = {
                                Logger.i(
                                    "SwipeGesture",
                                    "cancel ${currentItemVideoId} ${draggedPx.roundToInt()}px active=$active",
                                )
                                if (active && draggedPx >= swipeThreshold) {
                                    val callback = currentOnAddToQueue
                                    if (callback != null) {
                                        Logger.i(
                                            "SongSwipe",
                                            "play next on cancel: ${currentItemVideoId} (${draggedPx.roundToInt()}px)",
                                        )
                                        callback(currentItemVideoId)
                                    }
                                }
                                draggedPx = 0f
                                active = false
                                coroutineScope.launch {
                                    offsetX.animateTo(0f)
                                }
                            },
                        )
                    }.onGloballyPositioned { coordinates ->
                        with(density) {
                            heightDp = coordinates.size.height.toDp()
                        }
                    },
        ) {
            Row(
                Modifier
                    .padding(vertical = 6.dp, horizontal = 15.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(8.dp))
                AnimatedVisibility(
                    visible = selectionMode,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) seed else Color.Transparent)
                                    .border(
                                        width = 1.5.dp,
                                        color = if (isSelected) seed else contentColor.copy(alpha = 0.6f),
                                        shape = CircleShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = SimpIcons.Check,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                }
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(isPlaying) {
                        if (it) {
                            AudioPlayingIndicator(
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (index == null) {
                            val thumb = track?.thumbnails?.lastOrNull()?.url ?: songEntity?.thumbnails
                            AsyncImage(
                                model =
                                    ImageRequest
                                        .Builder(LocalPlatformContext.current)
                                        .data(thumb)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .diskCacheKey(thumb)
                                        .crossfade(true)
                                        .build(),
                                placeholder = rememberHolderPainter(),
                                error = rememberHolderPainter(),
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(4.dp)),
                            )
                        } else {
                            Text(
                                text = (index + 1).toString(),
                                color = contentColor,
                                style = typo().titleMedium,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 10.dp)
                        .align(Alignment.CenterVertically),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Text(
                        text = track?.title ?: songEntity?.title ?: "",
                        style = typo().titleSmall,
                        maxLines = 1,
                        color = contentColor,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(align = Alignment.CenterVertically)
                                .basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    animationMode = MarqueeAnimationMode.Immediately,
                                ).focusable(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedVisibility(
                            visible =
                                if (songEntity != null || track != null) {
                                    downloadState == DownloadState.STATE_DOWNLOADED
                                } else {
                                    false
                                },
                        ) {
                            Row {
                                Icon(
                                    imageVector = SimpIcons.DownloadForOffline,
                                    tint = contentColor,
                                    contentDescription = "",
                                    modifier = Modifier.size(16.dp).padding(2.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                        }
                        AnimatedVisibility(
                            visible =
                                songEntity?.isExplicit
                                    ?: (track?.isExplicit ?: false),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ExplicitBadge(
                                    modifier = Modifier.size(20.dp).padding(1.dp),
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                            }
                        }
                        // Ahead of the artist, as on Home's cards.
                        if (isLive) LiveBadge(modifier = Modifier.padding(end = 6.dp))
                        Text(
                            text =
                                (
                                    track?.artists?.toListName()?.connectArtists()
                                        ?: songEntity?.artistName?.connectArtists()
                                ) ?: "",
                            style = typo().bodySmall,
                            maxLines = 1,
                            color = subtitleColor,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight(align = Alignment.CenterVertically)
                                    .basicMarquee(
                                        iterations = Int.MAX_VALUE,
                                        animationMode = MarqueeAnimationMode.Immediately,
                                    ).focusable(),
                        )
                    }
                }
                if (rightView != null) {
                    rightView()
                }
                // Hidden while selecting: the per-item menu moves up to the selection app bar,
                // so one tap cannot mean both "act on this song" and "pick this song".
                if (onMoreClickListener != null && !selectionMode) {
                    RippleIconButton(imageVector = SimpIcons.MoreVert, fillMaxSize = false, tint = contentColor) {
                        if (itemVideoId.isNotBlank()) onMoreClickListener.invoke(itemVideoId)
                    }
                }
                AnimatedVisibility(
                    shouldShowDragHandle,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    Icon(
                        SimpIcons.DragHandle,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Shared reusable container for horizontal swipe-to-Play-Next interaction.
 *
 * Wraps any track or suggestion row to provide the same consistent gesture detection,
 * threshold, SkipNext icon indicator, and play-next callback across Naudio.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeToPlayNextContainer(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onPlayNext: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (!enabled || onPlayNext == null) {
        Box(modifier = modifier.fillMaxWidth()) {
            content()
        }
        return
    }

    val density = LocalDensity.current
    val maxOffset = with(density) { 96.dp.toPx() }
    val swipeThreshold = with(density) { 48.dp.toPx() }
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(initialValue = 0f) }
    var heightDp by remember { mutableStateOf(0.dp) }
    val currentOnPlayNext by rememberUpdatedState(onPlayNext)
    val showAddToQueue by remember { derivedStateOf { offsetX.value >= swipeThreshold } }

    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Crossfade(
            showAddToQueue,
            modifier = Modifier.align(Alignment.CenterStart),
        ) { shouldShowAddToQueue ->
            if (shouldShowAddToQueue) {
                Box(
                    modifier =
                        Modifier
                            .height(heightDp)
                            .aspectRatio(1f)
                            .padding(start = 15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        tint = contentColor,
                        imageVector = SimpIcons.SkipNext,
                        contentDescription = stringResource(Res.string.play_next),
                    )
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .pointerInput(enabled) {
                        var active = false
                        var draggedPx = 0f
                        var loggedThreshold = false
                        detectHorizontalDragGestures(
                            onDragStart = {
                                active = enabled && currentOnPlayNext != null
                                draggedPx = 0f
                                loggedThreshold = false
                                if (active) {
                                    Logger.i("SwipeGesture", "SwipeToPlayNextContainer start")
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (active) {
                                    val previous = draggedPx
                                    draggedPx = (draggedPx + dragAmount).coerceIn(0f, maxOffset)
                                    if (!loggedThreshold && previous < swipeThreshold && draggedPx >= swipeThreshold) {
                                        loggedThreshold = true
                                        Logger.i(
                                            "SwipeGesture",
                                            "SwipeToPlayNextContainer threshold ${draggedPx.roundToInt()}px",
                                        )
                                    }
                                    change.consume()
                                    coroutineScope.launch {
                                        offsetX.snapTo(draggedPx)
                                    }
                                }
                            },
                            onDragEnd = {
                                Logger.i(
                                    "SwipeGesture",
                                    "SwipeToPlayNextContainer end ${draggedPx.roundToInt()}px active=$active",
                                )
                                if (active) {
                                    val callback = currentOnPlayNext
                                    if (draggedPx >= swipeThreshold && callback != null) {
                                        Logger.i(
                                            "SongSwipe",
                                            "SwipeToPlayNextContainer play next (${draggedPx.roundToInt()}px)",
                                        )
                                        callback()
                                    }
                                    coroutineScope.launch {
                                        offsetX.animateTo(0f)
                                    }
                                }
                                active = false
                            },
                            onDragCancel = {
                                Logger.i(
                                    "SwipeGesture",
                                    "SwipeToPlayNextContainer cancel ${draggedPx.roundToInt()}px active=$active",
                                )
                                if (active && draggedPx >= swipeThreshold) {
                                    val callback = currentOnPlayNext
                                    if (callback != null) {
                                        Logger.i(
                                            "SongSwipe",
                                            "SwipeToPlayNextContainer play next on cancel (${draggedPx.roundToInt()}px)",
                                        )
                                        callback()
                                    }
                                }
                                draggedPx = 0f
                                active = false
                                coroutineScope.launch {
                                    offsetX.animateTo(0f)
                                }
                            },
                        )
                    }.onGloballyPositioned { coordinates ->
                        with(density) {
                            heightDp = coordinates.size.height.toDp()
                        }
                    },
        ) {
            content()
        }
    }
}

@Composable
fun SuggestItems(
    track: Track,
    isPlaying: Boolean,
    onClickListener: (() -> Unit)? = null,
    onAddClickListener: (() -> Unit)? = null,
    forceDark: Boolean = LocalForceDarkText.current,
) {
    val contentColor = if (forceDark) Color.White else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (forceDark) Color(0xC4FFFFFF) else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            Modifier
                .clickable {
                    if (onClickListener != null) {
                        onClickListener()
                    }
                }.animateContentSize(),
    ) {
        Row(
            Modifier
                .padding(vertical = 6.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(40.dp)) {
                Crossfade(isPlaying) {
                    if (it) {
                        AudioPlayingIndicator(
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        val thumb = track.thumbnails?.lastOrNull()?.url
                        AsyncImage(
                            model =
                                ImageRequest
                                    .Builder(LocalPlatformContext.current)
                                    .data(thumb)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .diskCacheKey(thumb)
                                    .crossfade(true)
                                    .build(),
                            placeholder = rememberHolderPainter(),
                            error = rememberHolderPainter(),
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier =
                                Modifier
                                    .wrapContentHeight()
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 10.dp)
                    .align(Alignment.CenterVertically),
            ) {
                Text(
                    text = track.title,
                    style = typo().titleSmall,
                    maxLines = 1,
                    color = contentColor,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(align = Alignment.CenterVertically)
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                animationMode = MarqueeAnimationMode.Immediately,
                            ).focusable(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (track.isLive) LiveBadge(modifier = Modifier.padding(end = 6.dp))
                    Text(
                        text =
                            (
                                track.artists?.toListName()?.connectArtists()
                            ) ?: "",
                        style = typo().bodySmall,
                        maxLines = 1,
                        color = subtitleColor,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(align = Alignment.CenterVertically)
                                .basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    animationMode = MarqueeAnimationMode.Immediately,
                                ).focusable(),
                    )
                }
            }
            RippleIconButton(
                imageVector = SimpIcons.Add,
                fillMaxSize = false,
                onClick =
                    onAddClickListener ?: {
                    },
            )
        }
    }
}

@Composable
fun PlaylistFullWidthItems(
    data: PlaylistType,
    onClickListener: (() -> Unit)? = null,
    rightView: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    forceDark: Boolean = LocalForceDarkText.current,
) {
    val contentColor = if (forceDark) Color.White else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (forceDark) Color(0xC4FFFFFF) else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            modifier
                .clickable {
                    onClickListener?.invoke()
                }.animateContentSize(),
    ) {
        var title = ""
        var thumb = ""
        var firstSubtitle = ""
        var secondSubtitle = ""
        var thirdRowSubtitle: String? = null

        var shouldPin = false

        firstSubtitle =
            when (data.playlistType()) {
                PlaylistType.Type.YOUTUBE_PLAYLIST -> stringResource(Res.string.playlist)
                PlaylistType.Type.RADIO -> stringResource(Res.string.radio)
                PlaylistType.Type.LOCAL -> stringResource(Res.string.playlist)
                PlaylistType.Type.ALBUM -> stringResource(Res.string.album)
                PlaylistType.Type.PODCAST -> stringResource(Res.string.podcasts)
            }
        when (data) {
            is AlbumEntity -> {
                title = data.title
                thumb = data.thumbnails ?: ""
                secondSubtitle = data.artistName?.connectArtists() ?: ""
                thirdRowSubtitle = data.year
            }

            is PlaylistEntity -> {
                title = data.title
                thumb = data.thumbnails
                secondSubtitle = data.author ?: ""
                if (data.description == "PIN") { // LIKED MUSIC
                    shouldPin = true
                }
            }

            is LocalPlaylistEntity -> {
                title = data.title
                thumb = data.thumbnail ?: ""
                secondSubtitle = stringResource(Res.string.you)
            }

            is PlaylistsResult -> {
                title = data.title
                thumb = data.thumbnails.lastOrNull()?.url ?: ""
                secondSubtitle = data.author
            }

            is AlbumsResult -> {
                title = data.title
                thumb = data.thumbnails.lastOrNull()?.url ?: ""
                secondSubtitle = data.artists.toListName().connectArtists()
                thirdRowSubtitle = data.year
            }

            is PodcastsEntity -> {
                title = data.title
                thumb = data.thumbnail ?: ""
                secondSubtitle = data.authorName
                thirdRowSubtitle = data.description
            }
        }
        Row(
            Modifier
                .padding(vertical = 10.dp, horizontal = 15.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.size(48.dp)) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalPlatformContext.current)
                            .data(thumb)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .diskCacheKey(thumb)
                            .crossfade(true)
                            .build(),
                    placeholder = rememberHolderPainter(),
                    error = rememberHolderPainter(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(4.dp)),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 10.dp)
                    .align(Alignment.CenterVertically),
            ) {
                Text(
                    text = title,
                    style = typo().titleSmall,
                    maxLines = 1,
                    color = contentColor,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(align = Alignment.CenterVertically)
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                animationMode = MarqueeAnimationMode.Immediately,
                            ).focusable(),
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (shouldPin) {
                        Image(
                            imageVector = SimpIcons.PushPin,
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(if (forceDark) Color.Cyan else MaterialTheme.colorScheme.primary),
                            modifier =
                                Modifier
                                    .rotate(30f)
                                    .size(16.dp),
                        )
                    }
                    Text(
                        text = "$firstSubtitle ${if (secondSubtitle.isNotEmpty()) " • $secondSubtitle" else ""}",
                        style = typo().bodySmall,
                        maxLines = 1,
                        color = subtitleColor,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(align = Alignment.CenterVertically)
                                .basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    animationMode = MarqueeAnimationMode.Immediately,
                                ).focusable(),
                    )
                }

                if (thirdRowSubtitle != null) {
                    Text(
                        text = thirdRowSubtitle,
                        style = typo().bodySmall,
                        maxLines = 1,
                        color = subtitleColor,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(align = Alignment.CenterVertically)
                                .basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    animationMode = MarqueeAnimationMode.Immediately,
                                ).focusable(),
                    )
                }
            }
            if (rightView != null) {
                rightView()
            }
        }
    }
}

@Composable
fun ArtistFullWidthItems(
    data: ArtistType,
    onClickListener: (() -> Unit)? = null,
    rightView: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    forceDark: Boolean = LocalForceDarkText.current,
) {
    val contentColor = if (forceDark) Color.White else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (forceDark) Color(0xC4FFFFFF) else MaterialTheme.colorScheme.onSurfaceVariant
    val (name: String, thumbnails: String?) =
        when (data) {
            is ArtistEntity -> Pair(data.name, data.thumbnails)
            is ArtistsResult -> Pair(data.artist, data.thumbnails.lastOrNull()?.url)
            else -> Pair("", null)
        }
    Box(
        modifier
            .clickable {
                onClickListener?.invoke()
            },
    ) {
        Row(
            Modifier
                .padding(vertical = 10.dp, horizontal = 15.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.size(48.dp)) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalPlatformContext.current)
                            .data(thumbnails)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .diskCacheKey(thumbnails)
                            .crossfade(true)
                            .build(),
                    placeholder = rememberHolderPainter(),
                    error = rememberHolderPainter(),
                    contentDescription = null,
                    contentScale = ContentScale.FillHeight,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 10.dp)
                    .align(Alignment.CenterVertically),
            ) {
                Text(
                    text = name,
                    style = typo().titleSmall,
                    maxLines = 1,
                    color = contentColor,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(align = Alignment.CenterVertically)
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                animationMode = MarqueeAnimationMode.Immediately,
                            ).focusable(),
                )

                Text(
                    text = stringResource(Res.string.artists),
                    style = typo().bodySmall,
                    maxLines = 1,
                    color = subtitleColor,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(align = Alignment.CenterVertically)
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                animationMode = MarqueeAnimationMode.Immediately,
                            ).focusable(),
                )
            }
            if (rightView != null) {
                rightView()
            }
        }
    }
}