package com.maxrave.kotlinytmusicscraper.models.response

import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The object of this element, or null when it is not one. */
private fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

/**
 * The object under this key, when there is one.
 *
 * A key that is there but holds no object counts as absent: the models write every field they
 * know, and a field that is null is not a shelf of recommendations - it is no shelf at all.
 */
private fun JsonObject.objectAt(key: String): JsonObject? = this[key] as? JsonObject

/**
 * The song rows of a library answer, and the tokens that lead to the next page of them.
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
 * The JSON the rows are read with.
 *
 * The same settings the scraper uses everywhere: unknown keys are ignored, so an answer that
 * carries a field the models do not know is still read.
 */
private val libraryJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

/**
 * Every song row of this answer, and the tokens of the pages that follow.
 *
 * The answer is read as the JSON it is, not as the model of it.
 *
 * The model of a browse answer declares fields as required, and one missing field fails the whole
 * answer: the library page of an account carries a recommendation shelf, and the `itemSize` of
 * that shelf was a required field of the model - so the decode of the library page failed, the
 * page was thrown away, and the import of the liked songs found nothing in it. The library page of
 * every account carries that shelf, so the import never worked.
 *
 * Reading the answer as the JSON it is cannot fail that way: a shelf that is not known is simply
 * not read, and the rows that are there are read whatever else the answer carries.
 *
 * The shelves that hold recommendations are skipped: their songs are not songs the account liked.
 */
fun JsonElement.libraryRows(): LibraryRows {
    val rows = mutableListOf<MusicResponsiveListItemRenderer>()
    val continuations = mutableListOf<String>()
    var shelfCount = 0

    fun readRows(contents: JsonElement?) {
        val list = contents as? JsonArray ?: return
        list.forEach { item ->
            val obj = item.jsonObjectOrNull() ?: return@forEach
            val renderer = obj.objectAt("musicResponsiveListItemRenderer")
            if (renderer != null) {
                val decoded =
                    runCatching {
                        libraryJson.decodeFromJsonElement<MusicResponsiveListItemRenderer>(renderer)
                    }.getOrNull()
                if (decoded != null) {
                    rows.add(decoded)
                }
                return@forEach
            }
            // A continuation page may hold the rows directly, or a shelf with them.
            obj.objectAt("musicShelfRenderer")?.let { readRows(it["contents"]) }
            obj.objectAt("musicPlaylistShelfRenderer")?.let { readRows(it["contents"]) }
        }
    }

    fun readTokens(element: JsonElement?) {
        val obj = element as? JsonObject ?: return
        fun tokenOf(source: JsonElement?): String? {
            val sourceObj = source.jsonObjectOrNull() ?: return null
            sourceObj.objectAt("nextContinuationData")?.get("continuation")?.jsonPrimitive?.contentOrNull?.let { return it }
            sourceObj.objectAt("reloadContinuationData")?.get("continuation")?.jsonPrimitive?.contentOrNull?.let { return it }
            // The token of a row that is the last of a page: the row carries it, and the row may
            // be the continuation item itself.
            val command =
                sourceObj.objectAt("continuationItemRenderer")?.get("continuationEndpoint")
                    ?: sourceObj["continuationEndpoint"]
            command?.jsonObjectOrNull()
                ?.get("continuationCommand")?.jsonObjectOrNull()
                ?.get("token")?.jsonPrimitive?.contentOrNull?.let { return it }
            return null
        }
        (obj["continuations"] as? JsonArray)?.forEach { entry ->
            tokenOf(entry)?.let { continuations.add(it) }
            entry.jsonObjectOrNull()?.objectAt("continuationItemRenderer")?.let { tokenOf(it)?.let { token -> continuations.add(token) } }
        }
        tokenOf(obj["continuationItemRenderer"])?.let { continuations.add(it) }
        (obj["contents"] as? JsonArray)?.forEach { entry ->
            entry.jsonObjectOrNull()?.objectAt("continuationItemRenderer")?.let { tokenOf(it)?.let { token -> continuations.add(token) } }
        }
    }

    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                // A shelf of recommendations holds songs the account did not like.
                val isRecommendation =
                    element.objectAt("musicCarouselShelfRenderer") != null ||
                        element.objectAt("musicCardShelfRenderer") != null ||
                        element.objectAt("musicImmersiveCarouselShelfRenderer") != null
                if (!isRecommendation) {
                    element.objectAt("musicShelfRenderer")?.let { shelf ->
                        shelfCount++
                        readRows(shelf["contents"])
                        readTokens(shelf)
                    }
                    element.objectAt("musicPlaylistShelfRenderer")?.let { shelf ->
                        shelfCount++
                        readRows(shelf["contents"])
                        readTokens(shelf)
                    }
                    // A section may hold the rows itself, without a shelf around them.
                    element.objectAt("itemSectionRenderer")?.let { section ->
                        readRows(section["contents"])
                        readTokens(section)
                    }
                    // A continuation page holds its rows directly, or in a shelf.
                    element.objectAt("musicShelfContinuation")?.let { continuation ->
                        readRows(continuation["contents"])
                        readTokens(continuation)
                    }
                    element.objectAt("musicPlaylistShelfContinuation")?.let { continuation ->
                        readRows(continuation["contents"])
                        readTokens(continuation)
                    }
                    // A playlist continuation page (the shape every page of a playlist browse
                    // answers with after its first one, measured on a live account on
                    // 2026-11-21): onResponseReceivedActions[].appendContinuationItemsAction
                    // .continuationItems - or the reload variant. The rows are read out of the
                    // continuationItems array, and the token that leads to the next page rides
                    // the last item of that same array as a continuationItemRenderer. Missing
                    // this envelope is what truncated the canonical liked playlist at its first
                    // 100 rows and made the reconciliation unlike 524 favorites it believed
                    // remotely deleted.
                    element.objectAt("appendContinuationItemsAction")?.let { action ->
                        val items = action["continuationItems"]
                        readRows(items)
                        (items as? JsonArray)?.forEach { item ->
                            item.jsonObjectOrNull()?.let(::readTokens)
                        }
                    }
                    element.objectAt("reloadContinuationItemsAction")?.let { action ->
                        val items = action["continuationItems"]
                        readRows(items)
                        (items as? JsonArray)?.forEach { item ->
                            item.jsonObjectOrNull()?.let(::readTokens)
                        }
                    }
                    // The classic playlist continuation shape, still served by some clients.
                    element.objectAt("playlistVideoListContinuation")?.let { continuation ->
                        readRows(continuation["contents"])
                        readTokens(continuation)
                    }
                    element.values.forEach { walk(it) }
                }
            }

            is JsonArray -> element.forEach { walk(it) }
            else -> Unit
        }
    }

    walk(this)

    return LibraryRows(
        rows = rows,
        continuations = continuations.distinct(),
        shelfCount = shelfCount,
    )
}

/**
 * Whether this answer is the page that asks for a sign-in.
 *
 * A page the session may not read is not an empty library: it is a page that was refused, and
 * reading it as "the account has no liked songs" is what makes a refused sign-in look like a
 * library that is simply empty.
 *
 * The message is looked for anywhere in the answer, the same way the rows are: the page that asks
 * for a sign-in is not always built the same way.
 */
fun JsonElement.librarySignInRequired(): Boolean {
    var found = false

    fun walk(element: JsonElement) {
        if (found) return
        when (element) {
            is JsonObject -> {
                val message = element.objectAt("messageRenderer")
                if (message != null) {
                    val signIn =
                        message.objectAt("button")?.objectAt("buttonRenderer")
                            ?.objectAt("navigationEndpoint")
                            ?.get("signInEndpoint")
                    if (signIn != null && signIn !is kotlinx.serialization.json.JsonNull) {
                        found = true
                        return
                    }
                }
                element.values.forEach { walk(it) }
            }

            is JsonArray -> element.forEach { walk(it) }
            else -> Unit
        }
    }

    walk(this)
    return found
}
