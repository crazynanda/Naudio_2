package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.SectionListRenderer
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.kotlinytmusicscraper.models.response.PlaylistBrowsePage
import com.maxrave.kotlinytmusicscraper.models.response.libraryRows
import com.maxrave.kotlinytmusicscraper.models.response.librarySignInRequired
import com.maxrave.kotlinytmusicscraper.models.response.playlistHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The page of a playlist, as YouTube serves it, read as its JSON.
 *
 * The typed read of the answer failed the page of the liked music on one missing field of one
 * header, so the page is read the way the library pages are read: the rows and the tokens with
 * the walk, and the header decoded on its own - so a header that does not fit the model is
 * skipped instead of throwing the page away.
 */
class PlaylistBrowsePageTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun parse(body: String): JsonElement = json.parseToJsonElement(body)

    private fun row(videoId: String): String =
        """
        {"musicResponsiveListItemRenderer": {
          "trackingParams": "x",
          "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
            {"url": "https://i.test/$videoId.jpg", "width": 120, "height": 120}]}}},
          "overlay": {"musicItemThumbnailOverlayRenderer": {"content": {"musicPlayButtonRenderer": {
            "playNavigationEndpoint": {"watchEndpoint": {"videoId": "$videoId"}}}}}},
          "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
            {"text": "Song $videoId"}]}}}],
          "fixedColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
            {"text": "3:21"}]}}}],
          "menu": null,
          "playlistItemData": {"playlistSetVideoId": "ps$videoId", "videoId": "$videoId"}
        }}
        """.trimIndent()

    /**
     * A `musicDetailHeaderRenderer` the model fits. Everything the model names as required is
     * there, plus one field the model has never heard of - which the decode of the header must
     * survive, the way the decode of the whole answer never did.
     */
    private fun detailHeader(title: String): String =
        """
        {"musicDetailHeaderRenderer": {
          "title": {"runs": [{"text": "$title"}]},
          "subtitle": {"runs": [{"text": "Playlist"}, {"text": " • "}, {"text": "Me"}]},
          "secondSubtitle": {"runs": [{"text": "2 songs"}, {"text": " • "}, {"text": "7:00"}]},
          "description": null,
          "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
            {"url": "https://i.test/cover.jpg", "width": 544, "height": 544}]}}},
          "menu": {"menuRenderer": {"items": [], "topLevelButtons": null}},
          "aFieldTheModelHasNeverHeardOf": true
        }}
        """.trimIndent()

    @Test
    fun readsTheHeaderOfAPlaylistDetailPage() {
        val body =
            """
            {"header": ${detailHeader("Liked songs")},
             "contents": {"twoColumnBrowseResultsRenderer": {"secondaryContents": {"sectionListRenderer": {
               "contents": [{"musicPlaylistShelfRenderer": {"playlistId": "VLLM", "contents": [${row("a")}]}}]
             }}}}}
            """.trimIndent()

        val header = parse(body).playlistHeader()

        assertTrue(header is BrowseResponse.Header.MusicDetailHeaderRenderer)
        assertEquals("Liked songs", header.title.runs.orEmpty().first().text)
    }

    @Test
    fun readsTheHeaderOfAEditablePlaylistPage() {
        val body =
            """
            {"header": {"musicEditablePlaylistDetailHeaderRenderer": {"header": ${detailHeader("My playlist")}}},
             "contents": {"twoColumnBrowseResultsRenderer": {"secondaryContents": {"sectionListRenderer": {
               "contents": [{"musicPlaylistShelfRenderer": {"playlistId": "VLxyz", "contents": [${row("b")}]}}]
             }}}}}
            """.trimIndent()

        val header = parse(body).playlistHeader()

        assertTrue(header is BrowseResponse.Header.MusicEditablePlaylistDetailHeaderRenderer)
        assertEquals("My playlist", header.header.musicDetailHeaderRenderer?.title?.runs?.first()?.text)
    }

    @Test
    fun readsTheHeaderOfAResponsivePage() {
        val body =
            """
            {"contents": {"twoColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
              "sectionListRenderer": {"contents": [{"musicResponsiveHeaderRenderer": {
                "title": {"runs": [{"text": "Liked songs"}]},
                "subtitle": {"runs": [{"text": "Playlist"}]},
                "secondSubtitle": {"runs": [{"text": "2 songs"}]},
                "buttons": []
              }}]}}}}]},
              "secondaryContents": {"sectionListRenderer": {"contents": [
                {"musicPlaylistShelfRenderer": {"playlistId": "VLLM", "contents": [${row("c")}]}}
              ]}}}}
            """.trimIndent()

        val header = parse(body).playlistHeader()

        assertTrue(header is SectionListRenderer.Content.MusicResponsiveHeaderRenderer)
    }

    @Test
    fun aHeaderTheModelDoesNotFitIsSkippedButTheRowsAreRead() {
        // The shape that killed the page of the liked music: the header misses a field the model
        // names as required. The header is skipped - and the page, its rows and its tokens with
        // it, is still read.
        val brokenHeader =
            """
            {"musicDetailHeaderRenderer": {
              "title": {"runs": [{"text": "Liked songs"}]},
              "subtitle": {"runs": [{"text": "Playlist"}]}
            }}
            """.trimIndent()
        val body =
            """
            {"header": $brokenHeader,
             "contents": {"twoColumnBrowseResultsRenderer": {"secondaryContents": {"sectionListRenderer": {
               "contents": [{"musicPlaylistShelfRenderer": {"playlistId": "VLLM",
                 "contents": [${row("a")}, ${row("b")}],
                 "continuations": [{"nextContinuationData": {"continuation": "playlist-next"}}]}
               }]}}}}}
            """.trimIndent()

        val root = parse(body)
        val page =
            PlaylistBrowsePage(
                httpStatus = 200,
                header = root.playlistHeader(),
                rows = root.libraryRows().rows,
                continuations = root.libraryRows().continuations,
                signInRequired = root.librarySignInRequired(),
                shape = "",
            )

        assertNull(page.header)
        assertTrue(page.usable)
        assertEquals(listOf("a", "b"), page.rows.map { it.playlistItemData?.videoId })
        assertEquals(listOf("playlist-next"), page.continuations)
    }
}
