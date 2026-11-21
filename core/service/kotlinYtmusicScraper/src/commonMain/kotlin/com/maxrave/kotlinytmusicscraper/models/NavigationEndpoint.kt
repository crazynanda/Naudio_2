package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.Serializable

@Serializable
data class NavigationEndpoint(
    val watchEndpoint: WatchEndpoint? = null,
    val watchPlaylistEndpoint: WatchEndpoint? = null,
    val browseEndpoint: BrowseEndpoint? = null,
    val searchEndpoint: SearchEndpoint? = null,
    val urlEndpoint: UrlEndpoint? = null,
    val queueAddEndpoint: QueueAddEndpoint? = null,
    val shareEntityEndpoint: ShareEntityEndpoint? = null,
    val playlistEditEndpoint: PlaylistEditEndpoint? = null,
    /**
     * The endpoint of a "sign in" button. Its presence says the page was served to a session
     * that is not signed in.
     */
    val signInEndpoint: SignInEndpoint? = null,
) {
    val endpoint: Endpoint?
        get() =
            watchEndpoint
                ?: watchPlaylistEndpoint
                ?: browseEndpoint
                ?: searchEndpoint
                ?: queueAddEndpoint
                ?: shareEntityEndpoint

    /** The sign-in button of a page that asks for a sign-in. */
    @Serializable
    data class SignInEndpoint(
        val clickTrackingParams: String? = null,
    )
}