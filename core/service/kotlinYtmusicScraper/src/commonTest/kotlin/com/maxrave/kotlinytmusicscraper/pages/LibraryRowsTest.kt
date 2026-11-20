package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.response.libraryRows
import com.maxrave.kotlinytmusicscraper.models.response.librarySignInRequired
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The liked songs of a session, as YouTube serves them.
 *
 * The shapes below are the ones the API answers with: a library page puts the songs in a
 * `musicShelfRenderer`, a playlist page in a `musicPlaylistShelfRenderer`, and either of them may
 * open with another section.
 *
 * The answer is read as the JSON it is, so the tests here are about the answer and not about a
 * model of it: the model of a browse answer declares fields as required, and a single missing
 * field failed the whole answer - the `itemSize` of the recommendation shelf every library page
 * carries, for one.
 */
class LibraryRowsTest {
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
     * The recommendation shelf every library page carries. Its `itemSize` is not in the model of
     * the answer, and a required field that is missing failed the whole answer.
     */
    private fun recommendationShelf(): String =
        """
        {"musicCarouselShelfRenderer": {
          "title": {"runs": [{"text": "Songs"}]},
          "contents": [${row("recommended")}],
          "continuations": [{"nextContinuationData": {"continuation": "recommendations-next"}}]
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
    fun readsTheSongsOfTheLibraryPageOfAnAccount() {
        // The shape the library answers with: the songs in a shelf under the section list, and a
        // recommendation shelf beside them.
        val body =
            """
            {
              "responseContext": {},
              "contents": {"sectionListRenderer": {"contents": [
                {"musicShelfRenderer": {"title": {"runs": [{"text": "Songs"}]},
                  "contents": [${row("saved")}],
                  "continuations": [{"nextContinuationData": {"continuation": "library-next"}}]}},
                ${recommendationShelf()}
              ]}}
            }
            """.trimIndent()

        val page = parse(body).libraryRows()

        assertEquals(listOf("saved"), page.rows.map { it.playlistItemData?.videoId })
        assertEquals(listOf("library-next"), page.continuations)
    }

    @Test
    fun theSongsOfTheRecommendationShelfAreNotLikedSongs() {
        val body =
            """
            {
              "responseContext": {},
              "contents": {"sectionListRenderer": {"contents": [
                {"musicShelfRenderer": {"contents": [${row("saved")}],
                  "continuations": [{"nextContinuationData": {"continuation": "library-next"}}]}},
                ${recommendationShelf()}
              ]}}
            }
            """.trimIndent()

        val page = parse(body).libraryRows()

        assertEquals(
            listOf("saved"),
            page.rows.map { it.playlistItemData?.videoId },
            "a recommended song is not a song the account liked",
        )
        assertEquals(
            listOf("library-next"),
            page.continuations,
            "the token of the recommendation shelf is not a token of the library",
        )
    }

    @Test
    fun readsTheSongsWhenTheShelfIsNotTheFirstSection() {
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
    fun readsTheSongsOfAnItemSection() {
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
    fun readsTheSongsOfASectionListContinuation() {
        // The shape the rest of the library arrives in.
        val body =
            """
            {
              "responseContext": {},
              "continuationContents": {"sectionListContinuation": {"contents": [
                {"musicShelfRenderer": {"contents": [${row("saved-next")}]}}
              ]}}
            }
            """.trimIndent()

        val page = parse(body).libraryRows()

        assertEquals(listOf("saved-next"), page.rows.map { it.playlistItemData?.videoId })
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
    fun readsTheContinuationOfARow() {
        val body =
            """
            {
              "responseContext": {},
              "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {
                "sectionListRenderer": {"contents": [{"musicShelfRenderer": {
                  "contents": [${row("g")}, {"continuationItemRenderer": {"continuationEndpoint": {
                    "continuationCommand": {"token": "row-token"}}}}]
                }}]}}}}]}}
            }
            """.trimIndent()

        val page = parse(body).libraryRows()

        assertEquals(listOf("row-token"), page.continuations)
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

        assertTrue(parse(signIn).librarySignInRequired())
        assertTrue(parse(signIn).libraryRows().rows.isEmpty())

        // An empty library is a shelf without a single song, not a sign-in page.
        val emptyLibrary =
            """
            {
              "responseContext": {},
              "contents": {"sectionListRenderer": {"contents": []}}
            }
            """.trimIndent()

        assertFalse(parse(emptyLibrary).librarySignInRequired())
        assertTrue(parse(emptyLibrary).libraryRows().rows.isEmpty())
    }

    @Test
    fun readsTheLibraryEvenWhenTheModelOfTheAnswerIsIncomplete() {
        // The answer carries a field the models do not know. A model of the answer would fail
        // here, and the songs that are in the answer would be gone with it.
        val body =
            """
            {
              "responseContext": {"visitorData": "abc"},
              "aFieldTheModelDoesNotKnow": {"nested": [1, 2, {"deeper": true}]},
              "contents": {"sectionListRenderer": {"contents": [
                {"musicShelfRenderer": {"itemSize": "MUSIC_SHELF", "contents": [${row("kept")}]}}
              ]}}
            }
            """.trimIndent()

        val rows = parse(body).libraryRows().rows

        assertEquals(listOf("kept"), rows.map { it.playlistItemData?.videoId })
    }

    @Test
    fun aShelfThatIsThereButHoldsNothingIsNotAShelfOfRecommendations() {
        // The answer of a page that was built by a model writes every field the model knows, and
        // a field that is null is not a shelf of recommendations - it is no shelf at all. Reading
        // the presence of the key as the shelf hid the songs of the library behind it.
        val body =
            """
            {
              "contents": {"sectionListRenderer": {"contents": [
                {"musicPlaylistShelfRenderer": {
                  "playlistId": "VLLM",
                  "musicCarouselShelfRenderer": null,
                  "musicCardShelfRenderer": null,
                  "contents": [${row("kept")}]
                }}
              ]}}
            }
            """.trimIndent()

        val rows = parse(body).libraryRows().rows

        assertEquals(listOf("kept"), rows.map { it.playlistItemData?.videoId })
    }
}
