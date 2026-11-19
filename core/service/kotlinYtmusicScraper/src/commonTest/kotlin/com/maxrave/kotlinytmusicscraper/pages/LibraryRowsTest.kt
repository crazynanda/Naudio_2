package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import com.maxrave.kotlinytmusicscraper.models.response.isSignInRequired
import com.maxrave.kotlinytmusicscraper.models.response.libraryRows
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The liked songs of a session, as YouTube serves them.
 *
 * The shapes below are the ones the API answers with: a library page puts the songs in a
 * `musicShelfRenderer`, a playlist page in a `musicPlaylistShelfRenderer`, and either of them may
 * open with another section. Reading one fixed path found the songs on some pages and on none of
 * the others, and an answer without songs is the same as an account without likes.
 */
class LibraryRowsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    private fun parse(body: String): BrowseResponse = json.decodeFromString(body)

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

    @Test
    fun readsTheSongsOfALibraryShelf() {
        val body =
            """
            {
              "responseContext": {},
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [{"musicShelfRenderer": {
                  "title": {"runs": [{"text": "Songs"}]},
                  "contents": [${row("a")}]
                }}]}}}}]}}
            }
            """.trimIndent()

        val rows = parse(body).libraryRows().rows

        assertEquals(1, rows.size)
        assertEquals("a", rows.first().playlistItemData?.videoId)
    }

    @Test
    fun readsTheSongsWhenTheShelfIsNotTheFirstSection() {
        // The library opens with another section before the songs.
        val body =
            """
            {
              "responseContext": {},
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [
                  {"musicDescriptionShelfRenderer": {"description": {"runs": [{"text": "Your library"}]}}},
                  {"musicShelfRenderer": {"contents": [${row("b")}]}}
                ]}}}}]}}
            }
            """.trimIndent()

        val rows = parse(body).libraryRows().rows

        assertEquals(1, rows.size)
        assertEquals("b", rows.first().playlistItemData?.videoId)
    }

    @Test
    fun readsTheSongsOfAPlaylistShelf() {
        val body =
            """
            {
              "responseContext": {},
              "contents": {"twoColumnBrowseResultsRenderer": {"secondaryContents": {"sectionListRenderer": {
                "contents": [{"musicPlaylistShelfRenderer": {
                  "playlistId": "VLLM",
                  "contents": [${row("c")}, ${row("d")}],
                  "collapsedItemCount": 0
                }}]}}}}
            }
            """.trimIndent()

        val rows = parse(body).libraryRows().rows

        assertEquals(2, rows.size)
        assertEquals(listOf("c", "d"), rows.map { it.playlistItemData?.videoId })
    }

    @Test
    fun readsTheSongsOfAContinuationPage() {
        val body =
            """
            {
              "responseContext": {},
              "continuationContents": {"musicShelfContinuation": {
                "contents": [${row("e")}],
                "continuations": [{"nextContinuationData": {"continuation": "page-2"}}]
              }}
            }
            """.trimIndent()

        val page = parse(body).libraryRows()

        assertEquals(1, page.rows.size)
        assertEquals(listOf("page-2"), page.continuations)
    }

    @Test
    fun readsTheContinuationOfAShelf() {
        val body =
            """
            {
              "responseContext": {},
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [{"musicShelfRenderer": {
                  "contents": [${row("f")}],
                  "continuations": [{"nextContinuationData": {"continuation": "page-2"}}]
                }}]}}}}]}}
            }
            """.trimIndent()

        val page = parse(body).libraryRows()

        assertEquals(listOf("page-2"), page.continuations)
    }

    @Test
    fun readsTheSongsOfAnItemSection() {
        // A section may hold the rows itself, without a shelf around them.
        val body =
            """
            {
              "responseContext": {},
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [{"itemSectionRenderer": {"contents": [
                  ${row("h")},
                  ${row("i")}
                ]}}]}}}}]}}
            }
            """.trimIndent()

        val rows = parse(body).libraryRows().rows

        assertEquals(2, rows.size)
        assertEquals(listOf("h", "i"), rows.map { it.playlistItemData?.videoId })
    }

    @Test
    fun theFixedPathTheImportUsedToReadFindsNothingOnALibraryPage() {
        // The path the import read until now: the first section of the first tab, and only a
        // playlist shelf. A library page answers with a shelf of another kind, so the path found
        // nothing - and an answer without songs is an account without a single like.
        val body =
            """
            {
              "responseContext": {},
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [{"musicShelfRenderer": {
                  "contents": [${row("g")}]
                }}]}}}}]}}
            }
            """.trimIndent()

        val response = parse(body)
        val oldPath =
            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.firstOrNull()
                ?.musicPlaylistShelfRenderer
                ?.contents

        assertTrue(oldPath.isNullOrEmpty(), "the fixed path found nothing: that is the reported failure")
        assertEquals(1, response.libraryRows().rows.size, "the shelves are collected wherever they sit")
    }

    @Test
    fun tellsASignInPageApartFromAnEmptyLibrary() {
        // What a session that may not read the library is answered with.
        val signIn =
            """
            {
              "responseContext": {},
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [{"itemSectionRenderer": {"contents": [
                  {"messageRenderer": {
                    "text": {"runs": [{"text": "Sign in to see your music"}]},
                    "button": {"buttonRenderer": {
                      "text": {"runs": [{"text": "Sign in"}]},
                      "navigationEndpoint": {"clickTrackingParams": "x", "signInEndpoint": {"clickTrackingParams": "x"}}
                    }}
                  }}
                ]}}]}}}}]}}
            }
            """.trimIndent()

        assertTrue(parse(signIn).isSignInRequired())
        assertTrue(parse(signIn).libraryRows().rows.isEmpty())

        // An empty library is a shelf without a single song, not a sign-in page.
        val emptyLibrary =
            """
            {
              "responseContext": {},
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [{"musicShelfRenderer": {"contents": []}}]}}}}]}}
            }
            """.trimIndent()

        assertFalse(parse(emptyLibrary).isSignInRequired())
    }
}
