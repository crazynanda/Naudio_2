package com.maxrave.kotlinytmusicscraper.models.response

import com.maxrave.kotlinytmusicscraper.models.Continuation
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.SectionListRenderer

/**
 * The song rows of a browse answer, and the tokens that lead to the next page of them.
 *
 * The answer of a library page is not a fixed shape. The same page comes as a single column with
 * a tab, as a two column with a sidebar, or as a bare section list; the songs sit in a
 * `musicShelfRenderer` on a library page and in a `musicPlaylistShelfRenderer` on a playlist page;
 * and the shelf is not always the first section - a page may open with a header, a carousel or a
 * hint before the songs.
 *
 * Reading one fixed path therefore finds nothing whenever YouTube serves the page another way,
 * and an empty answer then looks exactly like an account without a single liked song.
 *
 * So the shelves are collected from every place a shelf can be, and both kinds of shelf are read.
 */
data class LibraryRows(
    /** The rows, in the order the answer lists them. */
    val rows: List<MusicResponsiveListItemRenderer>,
    /** The tokens that lead to the next pages. */
    val continuations: List<String>,
    /** How many shelves were found, for the log. */
    val shelfCount: Int,
)

/**
 * Every song row of this answer, and the tokens of the pages that follow.
 */
fun BrowseResponse.libraryRows(): LibraryRows {
    val rows = mutableListOf<MusicResponsiveListItemRenderer>()
    val continuations = mutableListOf<String>()
    var shelfCount = 0

    fun readShelf(
        contents: List<MusicShelfRenderer.Content>?,
        shelfContinuations: List<Continuation>?,
    ) {
        if (contents.isNullOrEmpty() && shelfContinuations.isNullOrEmpty()) return
        shelfCount++
        contents?.forEach { content ->
            content.musicResponsiveListItemRenderer?.let { rows.add(it) }
        }
        continuations.addAll(shelfContinuations.orEmpty().mapNotNull { it.token() })
    }

    fun readSectionList(sectionList: SectionListRenderer?) {
        sectionList?.contents?.forEach { content ->
            content.musicShelfRenderer?.let { readShelf(it.contents, it.continuations) }
            content.musicPlaylistShelfRenderer?.let { readShelf(it.contents, it.continuations) }
            // A section may hold the rows itself, without a shelf around them.
            content.itemSectionRenderer?.contents?.forEach { item ->
                item.musicResponsiveListItemRenderer?.let { rows.add(it) }
                if (item.musicResponsiveListItemRenderer != null) shelfCount++
            }
        }
    }

    // The tabs of a single column and of a two column answer.
    contents?.singleColumnBrowseResultsRenderer?.tabs?.forEach { tab ->
        readSectionList(tab.tabRenderer?.content?.sectionListRenderer)
    }
    contents?.twoColumnBrowseResultsRenderer?.tabs?.forEach { tab ->
        readSectionList(tab.tabRenderer?.content?.sectionListRenderer)
    }
    readSectionList(contents?.sectionListRenderer)
    readSectionList(contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer)

    // A continuation page: the rows are in the continuation contents.
    continuationContents?.musicShelfContinuation?.let { continuation ->
        shelfCount++
        continuation.contents?.forEach { content ->
            content.musicResponsiveListItemRenderer?.let { rows.add(it) }
        }
        continuations.addAll(continuation.continuations.orEmpty().mapNotNull { it.token() })
    }
    continuationContents?.musicPlaylistShelfContinuation?.let { continuation ->
        shelfCount++
        continuation.contents?.forEach { content ->
            content.musicResponsiveListItemRenderer?.let { rows.add(it) }
        }
        continuations.addAll(continuation.continuations.orEmpty().mapNotNull { it.token() })
    }
    readSectionList(continuationContents?.sectionListContinuation?.let { continuation ->
        SectionListRenderer(
            header = null,
            contents = continuation.contents,
            continuations = continuation.continuations,
        )
    })

    onResponseReceivedActions?.forEach { action ->
        action.appendContinuationItemsAction?.continuationItems?.forEach { item ->
            item.musicResponsiveListItemRenderer?.let { rows.add(it) }
        }
    }

    return LibraryRows(
        rows = rows,
        continuations = continuations.distinct(),
        shelfCount = shelfCount,
    )
}

/**
 * The token of a continuation, whichever of the two shapes carries it.
 */
fun Continuation.token(): String? =
    nextContinuationData?.continuation
        ?: reloadContinuationData?.continuation

/**
 * Whether this answer is the page that asks for a sign-in.
 *
 * A page the session may not read is not an empty library: it is a page that was refused, and
 * reading it as "the account has no liked songs" is what makes a refused sign-in look like a
 * library that is simply empty.
 */
fun BrowseResponse.isSignInRequired(): Boolean {
    val sectionLists =
        buildList {
            contents?.singleColumnBrowseResultsRenderer?.tabs?.forEach { tab ->
                tab.tabRenderer?.content?.sectionListRenderer?.let { add(it) }
            }
            contents?.twoColumnBrowseResultsRenderer?.tabs?.forEach { tab ->
                tab.tabRenderer?.content?.sectionListRenderer?.let { add(it) }
            }
            contents?.sectionListRenderer?.let { add(it) }
            contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.let { add(it) }
        }

    return sectionLists.any { sectionList ->
        sectionList.contents.orEmpty().any { content ->
            content.itemSectionRenderer?.contents.orEmpty().any { item ->
                item.messageRenderer?.button?.buttonRenderer?.navigationEndpoint?.signInEndpoint != null
            }
        }
    }
}
