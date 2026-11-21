package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.Album
import com.maxrave.kotlinytmusicscraper.models.Artist
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.YTItem
import com.maxrave.kotlinytmusicscraper.models.oddElements
import com.maxrave.kotlinytmusicscraper.models.splitBySeparator
import com.maxrave.kotlinytmusicscraper.utils.parseTime

data class ArtistItemsContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
) {
    companion object {
        fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): SongItem? {
            val videoId = renderer.videoId
                ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                ?: return null
            val title = renderer.flexColumns
                .firstOrNull()
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.firstOrNull()
                ?.text ?: ""
            val artists = renderer.flexColumns
                .getOrNull(1)
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                // The column reads "Artist • Album • 13M plays"; only the first group is
                // artists, so everything after the first " • " is dropped.
                ?.splitBySeparator()
                ?.firstOrNull()
                ?.oddElements()
                ?.map {
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId,
                    )
                } ?: emptyList()
            val albumRun = renderer.flexColumns.getOrNull(2)
                ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
            val album = albumRun?.let {
                Album(
                    name = it.text,
                    id = it.navigationEndpoint?.browseEndpoint?.browseId ?: "",
                )
            }
            val duration = renderer.fixedColumns
                ?.firstOrNull()
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.firstOrNull()
                ?.text
                ?.parseTime()
            val thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: ""
            val explicit = renderer.badges?.find {
                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
            } != null
            val endpoint = renderer.overlay
                ?.musicItemThumbnailOverlayRenderer
                ?.content
                ?.musicPlayButtonRenderer
                ?.playNavigationEndpoint
                ?.watchEndpoint
            return SongItem(
                id = videoId,
                title = title,
                artists = artists,
                album = album,
                duration = duration,
                thumbnail = thumbnail,
                explicit = explicit,
                endpoint = endpoint,
                thumbnails = renderer.thumbnail?.musicThumbnailRenderer?.thumbnail,
                musicVideoType = renderer.musicVideoType,
                isLive = renderer.isLive,
            )
        }
    }
}