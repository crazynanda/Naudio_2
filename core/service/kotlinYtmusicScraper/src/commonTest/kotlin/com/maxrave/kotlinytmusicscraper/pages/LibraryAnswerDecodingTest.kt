package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library page of an account, in the shape the API answers with.
 *
 * The page carries a recommendation shelf beside the songs, and the shelf of the songs answers
 * with a continuation. The recommendation shelf has no `itemSize`, which used to be a required
 * field of the model of the answer - so the decode of the library page of every account failed,
 * the page was thrown away, and the import of the liked songs found nothing in it.
 */
class LibraryAnswerDecodingTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun songRow(id: String): String =
        """{"musicResponsiveListItemRenderer":{
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],
            "playlistItemData":{"videoId":"$id"}
        }}"""

    private val libraryPage =
        """{"responseContext":{"visitorData":"abc"},
        "contents":{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},
                "contents":[${songRow("saved")}],
                "continuations":[{"nextContinuationData":{"continuation":"library-next"}}]}},
            {"musicCarouselShelfRenderer":{
                "title":{"runs":[{"text":"Songs"}]},
                "contents":[${songRow("never-saved")}],
                "continuations":[{"nextContinuationData":{"continuation":"recommendations-next"}}]}}
        ]}}}"""

    @Test
    fun theLibraryPageOfAnAccountIsDecoded() {
        val decoded = json.decodeFromString<BrowseResponse>(libraryPage)

        // The shelf of songs and the shelf of recommendations are both there.
        val shelves = decoded.contents?.sectionListRenderer?.contents.orEmpty()
        assertTrue(shelves.isNotEmpty(), "the shelves of the library page must be read")
        assertEquals(2, shelves.size)
    }

    @Test
    fun aShelfWithoutItsItemSizeDoesNotBreakTheAnswer() {
        // The recommendation shelf answers without an itemSize.
        val answer =
            """{"responseContext":{"visitorData":"abc"},
            "contents":{"sectionListRenderer":{"contents":[
                {"musicCarouselShelfRenderer":{"contents":[${songRow("never-saved")}]}}
            ]}}}"""

        val decoded = json.decodeFromString<BrowseResponse>(answer)

        assertEquals(1, decoded.contents?.sectionListRenderer?.contents?.size)
    }

    @Test
    fun aRowWithoutItsColumnsDoesNotBreakTheAnswer() {
        val answer =
            """{"responseContext":{"visitorData":"abc"},
            "contents":{"sectionListRenderer":{"contents":[
                {"musicShelfRenderer":{"contents":[
                    {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"no-columns"}}}
                ]}}
            ]}}}"""

        val decoded = json.decodeFromString<BrowseResponse>(answer)

        assertEquals(1, decoded.contents?.sectionListRenderer?.contents?.size)
    }
}
