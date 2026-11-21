package com.maxrave.kotlinytmusicscraper.models.response

import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.SectionListRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** The object under this key, when there is one. */
private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

/**
 * One page of a playlist browse answer, read as the JSON it is.
 *
 * The typed read of the answer (the `BrowseResponse` model) declares fields as required, and one
 * missing field fails the whole answer: the header of a playlist page carries five required
 * fields, and the page of the liked music was lost to one of them. The page is therefore read as
 * the JSON it is - the rows and the tokens with the walk the library pages use, and the header
 * decoded on its own, so a header that does not fit the model is skipped instead of throwing the
 * page away.
 */
data class PlaylistBrowsePage(
    val httpStatus: Int,
    /**
     * The header of the page, decoded into the model `parsePlaylistData` reads - whichever of the
     * three shapes carries it, or null when none of them decoded.
     */
    val header: Any?,
    val rows: List<MusicResponsiveListItemRenderer>,
    val continuations: List<String>,
    val signInRequired: Boolean,
    /** The shape of the answer, for the log. Never carries a value. */
    val shape: String,
) {
    /** Whether this page can be used at all. */
    val usable: Boolean
        get() = httpStatus in 200..299 && !signInRequired
}

/**
 * The JSON the header is decoded with: unknown keys are ignored, so a header that carries a field
 * the model does not know is still read.
 */
private val headerJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * The header of this answer, decoded on its own.
 *
 * The header sits in the answer under one of three names, wherever the page put it: a
 * `musicDetailHeaderRenderer`, a `musicEditablePlaylistDetailHeaderRenderer` around one, or a
 * `musicResponsiveHeaderRenderer`. Each is decoded separately, and a header that does not fit its
 * model is skipped rather than throwing the answer away - the page without a header still carries
 * its rows.
 */
fun JsonElement.playlistHeader(): Any? {
    var detail: Any? = null
    var editable: Any? = null
    var responsive: Any? = null

    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                if (detail == null) {
                    element.asObjectOrNull()?.get("musicDetailHeaderRenderer")?.asObjectOrNull()?.let { obj ->
                        detail =
                            runCatching {
                                headerJson
                                    .decodeFromJsonElement<BrowseResponse.Header.MusicDetailHeaderRenderer>(obj)
                            }.getOrNull()
                    }
                }
                if (editable == null) {
                    element.asObjectOrNull()?.get("musicEditablePlaylistDetailHeaderRenderer")?.asObjectOrNull()?.let { obj ->
                        editable =
                            runCatching {
                                headerJson
                                    .decodeFromJsonElement<BrowseResponse.Header.MusicEditablePlaylistDetailHeaderRenderer>(obj)
                            }.getOrNull()
                    }
                }
                if (responsive == null) {
                    element.asObjectOrNull()?.get("musicResponsiveHeaderRenderer")?.asObjectOrNull()?.let { obj ->
                        responsive =
                            runCatching {
                                headerJson
                                    .decodeFromJsonElement<SectionListRenderer.Content.MusicResponsiveHeaderRenderer>(obj)
                            }.getOrNull()
                    }
                }
                element.values.forEach { walk(it) }
            }

            is JsonArray -> element.forEach { walk(it) }
            else -> Unit
        }
    }

    walk(this)
    // The editable playlist header wraps a detail header of its own; the wrapper is the one the
    // parser reads the fields from, so it wins over the detail nested inside it.
    return editable ?: detail ?: responsive
}
