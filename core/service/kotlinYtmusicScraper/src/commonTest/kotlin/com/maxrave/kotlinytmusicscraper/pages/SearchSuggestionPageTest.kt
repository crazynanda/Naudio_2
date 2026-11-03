package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.NavigationEndpoint
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.Runs
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.Thumbnail
import com.maxrave.kotlinytmusicscraper.models.ThumbnailRenderer
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import com.maxrave.kotlinytmusicscraper.models.VideoItem
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchSuggestionPageTest {
    @Test
    fun audioSuggestionIsParsedAsSong() {
        val item = SearchSuggestionPage.fromMusicResponsiveListItemRenderer(renderer("MUSIC_VIDEO_TYPE_ATV"))

        assertTrue(item is SongItem, "ATV suggestion must be a SongItem, was ${item}")
        assertEquals("MUSIC_VIDEO_TYPE_ATV", item.musicVideoType)
        assertEquals("Aditya Rikhari", item.artists.single().name)
    }

    @Test
    fun videoSuggestionIsStillParsedAsVideo() {
        val item = SearchSuggestionPage.fromMusicResponsiveListItemRenderer(renderer("MUSIC_VIDEO_TYPE_UGC"))

        assertTrue(item is VideoItem, "UGC suggestion must stay a VideoItem, was ${item}")
    }

    private fun renderer(musicVideoType: String): MusicResponsiveListItemRenderer =
        MusicResponsiveListItemRenderer(
            badges = null,
            fixedColumns = null,
            flexColumns =
                listOf(
                    column("Sahiba"),
                    MusicResponsiveListItemRenderer.FlexColumn(
                        MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                            Runs(
                                listOf(
                                    Run("Song", null),
                                    Run(" • ", null),
                                    Run("Aditya Rikhari", null),
                                    Run(" • ", null),
                                    Run("Sahiba", null),
                                ),
                            ),
                        ),
                    ),
                ),
            thumbnail =
                ThumbnailRenderer(
                    musicThumbnailRenderer =
                        ThumbnailRenderer.MusicThumbnailRenderer(
                            thumbnail = Thumbnails(listOf(Thumbnail("https://img.example/cover", 120, 120))),
                            thumbnailCrop = null,
                            thumbnailScale = null,
                        ),
                    musicAnimatedThumbnailRenderer = null,
                    croppedSquareThumbnailRenderer = null,
                ),
            menu = null,
            playlistItemData = MusicResponsiveListItemRenderer.PlaylistItemData(playlistSetVideoId = null, videoId = "tNc2coVC2aw"),
            overlay = null,
            navigationEndpoint =
                NavigationEndpoint(
                    watchEndpoint =
                        WatchEndpoint(
                            videoId = "tNc2coVC2aw",
                            watchEndpointMusicSupportedConfigs =
                                WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                                    WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig(musicVideoType),
                                ),
                        ),
                ),
        )

    private fun column(text: String) =
        MusicResponsiveListItemRenderer.FlexColumn(
            MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(
                Runs(listOf(Run(text, null))),
            ),
        )
}
