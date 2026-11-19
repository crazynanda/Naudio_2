package com.maxrave.kotlinytmusicscraper.models.response

/** The browse ids that lead to the liked songs of a session. */
object LikedSongsBrowseIds {
    /**
     * The library section that lists the liked songs.
     *
     * The library answers every section on its own id, and the liked songs are the section
     * `FEmusic_liked_videos`.
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

    /** The ids to try, in order. The first one that answers with rows wins. */
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
