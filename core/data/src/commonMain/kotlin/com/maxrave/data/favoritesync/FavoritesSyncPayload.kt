package com.maxrave.data.favoritesync

import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.favoritesync.FavoritesSyncResult
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val favoritesSyncJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

@Serializable
data class FavoritesSyncBundle(
    val songs: List<FavoriteSongDto> = emptyList(),
    val albums: List<FavoriteAlbumDto> = emptyList(),
    val playlists: List<FavoritePlaylistDto> = emptyList(),
    val podcasts: List<FavoritePodcastDto> = emptyList(),
    val artists: List<FavoriteArtistDto> = emptyList(),
    val tombstones: List<FavoriteTombstoneDto> = emptyList(),
    val clientTimestamp: Long = 0L,
)

@Serializable
data class FavoriteSongDto(
    val videoId: String,
    val title: String,
    val duration: String = "",
    val durationSeconds: Int = 0,
    val thumbnails: String? = null,
    val artistId: List<String>? = null,
    val artistName: List<String>? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val isAvailable: Boolean = true,
    val isExplicit: Boolean = false,
    val likeStatus: String = "LIKE",
    val videoType: String = "",
    val category: String? = null,
    val resultType: String? = null,
    val liked: Boolean = true,
    val totalPlayTime: Long = 0,
    val favoriteAtEpochMs: Long,
    val inLibraryEpochMs: Long = favoriteAtEpochMs,
)

@Serializable
data class FavoriteAlbumDto(
    val browseId: String,
    val title: String,
    val thumbnails: String? = null,
    val artistId: List<String?>? = null,
    val artistName: List<String>? = null,
    val audioPlaylistId: String = "",
    val description: String = "",
    val duration: String? = null,
    val durationSeconds: Int = 0,
    val trackCount: Int = 0,
    val tracks: List<String>? = null,
    val type: String = "ALBUM",
    val year: String? = null,
    val liked: Boolean = true,
    val favoriteAtEpochMs: Long,
    val inLibraryEpochMs: Long = favoriteAtEpochMs,
)

@Serializable
data class FavoritePlaylistDto(
    val id: String,
    val title: String,
    val thumbnails: String = "",
    val author: String? = null,
    val description: String = "",
    val duration: String = "",
    val durationSeconds: Int = 0,
    val privacy: String = "PRIVATE",
    val trackCount: Int = 0,
    val tracks: List<String>? = null,
    val year: String? = null,
    val liked: Boolean = true,
    val favoriteAtEpochMs: Long,
    val inLibraryEpochMs: Long = favoriteAtEpochMs,
)

@Serializable
data class FavoritePodcastDto(
    val podcastId: String,
    val title: String,
    val authorId: String = "",
    val authorName: String = "",
    val authorThumbnail: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val isFavorite: Boolean = true,
    val listEpisodes: List<String> = emptyList(),
    val favoriteTimeEpochMs: Long,
    val inLibraryEpochMs: Long = favoriteTimeEpochMs,
)

@Serializable
data class FavoriteArtistDto(
    val channelId: String,
    val name: String,
    val thumbnails: String? = null,
    val followed: Boolean = true,
    val nameLogoUrl: String? = null,
    val nameLogoColor: String? = null,
    val followedAtEpochMs: Long,
    val inLibraryEpochMs: Long = followedAtEpochMs,
)

@Serializable
data class FavoriteTombstoneDto(
    val type: String,
    val id: String,
    val isFavorite: Boolean,
    val updatedAtEpochMs: Long,
)

fun LocalDateTime?.toEpochMillis(): Long =
    this?.toInstant(TimeZone.currentSystemDefault())?.toEpochMilliseconds() ?: 0L

fun Long.toLocalDateTimeSystem(): LocalDateTime =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())

fun Long.toLocalDateTimeUtc(): LocalDateTime = toLocalDateTimeSystem()

fun SongEntity.toFavoriteDto(): FavoriteSongDto =
    FavoriteSongDto(
        videoId = videoId,
        title = title,
        duration = duration,
        durationSeconds = durationSeconds,
        thumbnails = thumbnails,
        artistId = artistId,
        artistName = artistName,
        albumId = albumId,
        albumName = albumName,
        isAvailable = isAvailable,
        isExplicit = isExplicit,
        likeStatus = likeStatus,
        videoType = videoType,
        category = category,
        resultType = resultType,
        liked = liked,
        totalPlayTime = totalPlayTime,
        favoriteAtEpochMs = favoriteAt.toEpochMillis(),
        inLibraryEpochMs = inLibrary.toEpochMillis(),
    )

fun FavoriteSongDto.toEntity(): SongEntity =
    SongEntity(
        videoId = videoId,
        albumId = albumId,
        albumName = albumName,
        artistId = artistId,
        artistName = artistName,
        duration = duration,
        durationSeconds = durationSeconds,
        isAvailable = isAvailable,
        isExplicit = isExplicit,
        likeStatus = likeStatus,
        thumbnails = thumbnails,
        title = title,
        videoType = videoType,
        category = category,
        resultType = resultType,
        liked = liked,
        totalPlayTime = totalPlayTime,
        downloadState = DownloadState.STATE_NOT_DOWNLOADED,
        favoriteAt = favoriteAtEpochMs.toLocalDateTimeUtc(),
        downloadedAt = null,
        inLibrary = inLibraryEpochMs.toLocalDateTimeUtc(),
        canvasUrl = null,
        canvasThumbUrl = null,
    )

fun AlbumEntity.toFavoriteDto(): FavoriteAlbumDto =
    FavoriteAlbumDto(
        browseId = browseId,
        title = title,
        thumbnails = thumbnails,
        artistId = artistId,
        artistName = artistName,
        audioPlaylistId = audioPlaylistId,
        description = description,
        duration = duration,
        durationSeconds = durationSeconds,
        trackCount = trackCount,
        tracks = tracks,
        type = type,
        year = year,
        liked = liked,
        favoriteAtEpochMs = favoriteAt.toEpochMillis(),
        inLibraryEpochMs = inLibrary.toEpochMillis(),
    )

fun FavoriteAlbumDto.toEntity(): AlbumEntity =
    AlbumEntity(
        browseId = browseId,
        artistId = artistId,
        artistName = artistName,
        audioPlaylistId = audioPlaylistId,
        description = description,
        duration = duration,
        durationSeconds = durationSeconds,
        thumbnails = thumbnails,
        title = title,
        trackCount = trackCount,
        tracks = tracks,
        type = type,
        year = year,
        liked = liked,
        inLibrary = inLibraryEpochMs.toLocalDateTimeUtc(),
        favoriteAt = favoriteAtEpochMs.toLocalDateTimeUtc(),
        downloadedAt = null,
        downloadState = DownloadState.STATE_NOT_DOWNLOADED,
    )

fun PlaylistEntity.toFavoriteDto(): FavoritePlaylistDto =
    FavoritePlaylistDto(
        id = id,
        title = title,
        thumbnails = thumbnails,
        author = author,
        description = description,
        duration = duration,
        durationSeconds = durationSeconds,
        privacy = privacy,
        trackCount = trackCount,
        tracks = tracks,
        year = year,
        liked = liked,
        favoriteAtEpochMs = favoriteAt.toEpochMillis(),
        inLibraryEpochMs = inLibrary.toEpochMillis(),
    )

fun FavoritePlaylistDto.toEntity(): PlaylistEntity =
    PlaylistEntity(
        id = id,
        author = author,
        description = description,
        duration = duration,
        durationSeconds = durationSeconds,
        privacy = privacy,
        thumbnails = thumbnails,
        title = title,
        trackCount = trackCount,
        tracks = tracks,
        year = year,
        liked = liked,
        inLibrary = inLibraryEpochMs.toLocalDateTimeUtc(),
        favoriteAt = favoriteAtEpochMs.toLocalDateTimeUtc(),
        downloadedAt = null,
        downloadState = DownloadState.STATE_NOT_DOWNLOADED,
    )

fun PodcastsEntity.toFavoriteDto(): FavoritePodcastDto =
    FavoritePodcastDto(
        podcastId = podcastId,
        title = title,
        authorId = authorId,
        authorName = authorName,
        authorThumbnail = authorThumbnail,
        description = description,
        thumbnail = thumbnail,
        isFavorite = isFavorite,
        listEpisodes = listEpisodes,
        favoriteTimeEpochMs = favoriteTime.toEpochMillis(),
        inLibraryEpochMs = inLibrary.toEpochMillis(),
    )

fun FavoritePodcastDto.toEntity(): PodcastsEntity =
    PodcastsEntity(
        podcastId = podcastId,
        title = title,
        authorId = authorId,
        authorName = authorName,
        authorThumbnail = authorThumbnail,
        description = description,
        thumbnail = thumbnail,
        isFavorite = isFavorite,
        inLibrary = inLibraryEpochMs.toLocalDateTimeUtc(),
        favoriteTime = favoriteTimeEpochMs.toLocalDateTimeUtc(),
        listEpisodes = listEpisodes,
    )

fun ArtistEntity.toFavoriteDto(): FavoriteArtistDto =
    FavoriteArtistDto(
        channelId = channelId,
        name = name,
        thumbnails = thumbnails,
        followed = followed,
        nameLogoUrl = nameLogoUrl,
        nameLogoColor = nameLogoColor,
        followedAtEpochMs = followedAt.toEpochMillis(),
        inLibraryEpochMs = inLibrary.toEpochMillis(),
    )

fun FavoriteArtistDto.toEntity(): ArtistEntity =
    ArtistEntity(
        channelId = channelId,
        name = name,
        thumbnails = thumbnails,
        followed = followed,
        followedAt = followedAtEpochMs.toLocalDateTimeUtc(),
        inLibrary = inLibraryEpochMs.toLocalDateTimeUtc(),
        nameLogoUrl = nameLogoUrl,
        nameLogoColor = nameLogoColor,
    )
