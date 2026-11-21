package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicCarouselShelfRenderer(
    val header: Header?,
    // With a default, for the same reason as `itemSize` below: a shelf without rows is still a
    // shelf, and a required field that is missing failed the whole answer.
    val contents: List<Content> = emptyList(),
    // With a default: a shelf that answers without it is still a shelf, and a required field that
    // is missing failed the whole answer it was part of - the library page of every account
    // carries a shelf of this kind.
    val itemSize: String = "",
    val numItemsPerColumn: String?,
) {
    @Serializable
    data class Header(
        val musicCarouselShelfBasicHeaderRenderer: MusicCarouselShelfBasicHeaderRenderer,
    ) {
        @Serializable
        data class MusicCarouselShelfBasicHeaderRenderer(
            val strapline: Runs?,
            val title: Runs,
            val thumbnail: ThumbnailRenderer?,
            val moreContentButton: Button?,
        )
    }

    @Serializable
    data class Content(
        val musicTwoRowItemRenderer: MusicTwoRowItemRenderer?,
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer?,
        val musicNavigationButtonRenderer: MusicNavigationButtonRenderer?, // navigation button in explore tab
        val musicMultiRowListItemRenderer: MusicShelfRenderer.Content.MusicMultiRowListItemRenderer?,
    )
}