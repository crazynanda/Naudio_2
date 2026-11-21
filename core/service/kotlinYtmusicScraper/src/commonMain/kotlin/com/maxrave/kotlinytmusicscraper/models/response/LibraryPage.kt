package com.maxrave.kotlinytmusicscraper.models.response

/** The browse ids that lead to the liked songs of a session. */
object LikedSongsBrowseIds {
    /**
     * The library section that opens a "Recently saved" shelf.
     *
     * Measured on a signed-in account on 2026-11-21: this id answers with a shelf titled
     * "Recently saved", which lists what was recently saved/liked - including songs whose like
     * was removed afterwards, and items saved to the library without being liked. It is NOT the
     * liked-songs list, and importing it wholesale resurrects locally-removed favorites. It is
     * kept as a fallback for sessions whose canonical playlist page cannot be read.
     */
    const val LIKED_SONGS = "FEmusic_liked_videos"

    /**
     * The "Liked Music" entry of the library, and the page it opens.
     *
     * This is the browse id the library itself hands out for the entry, and the one the app used
     * until the library section was read as well.
     */
    const val LIKED_MUSIC = "VLLM"

    /**
     * The liked music as the playlist it is.
     *
     * YouTube serves the liked songs as the playlist `LM` under the `VL` prefix of the library
     * pages, and answers this id with the playlist page.
     */
    const val LIKED_MUSIC_PLAYLIST = "VLM"

    /**
     * The ids to try, in order.
     *
     * The canonical liked list is [LIKED_MUSIC] (`VLLM`): it was the only source whose rows
     * matched the account's actual like states when the sources disagreed (2 liked songs:
     * the section `FEmusic_liked_videos` answered with 6 rows, four of them recently unliked).
     * Every id is asked, and the reader decides which answer to use.
     */
    val ALL = listOf(LIKED_SONGS, LIKED_MUSIC, LIKED_MUSIC_PLAYLIST)
}

/**
 * One page of a library answer.
 *
 * What the answer held, and how it was answered, kept apart: a page that asks for a sign-in is
 * not a library without songs.
 */
data class LibraryPage(
    val httpStatus: Int,
    val rows: List<com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer>,
    val continuations: List<String>,
    val shelfCount: Int,
    val signInRequired: Boolean,
    /** The shape of the answer, for the log. Never carries a value. */
    val shape: String,
) {
    /** Whether this page can be used at all. */
    val usable: Boolean
        get() = httpStatus in 200..299 && !signInRequired
}
