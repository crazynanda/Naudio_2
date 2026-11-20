package com.maxrave.kotlinytmusicscraper.models.response

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The answer of YouTube to a change of the like state of a song.
 *
 * The answer carries the state the song has **now**, in the action that updates the like status.
 * That is the only thing in it that says whether the change happened: the status code of the
 * request says that YouTube answered, not that the song was liked.
 */
@Serializable
data class LikeResponse(
    val actions: List<Action>? = null,
    val error: Error? = null,
) {
    @Serializable
    data class Action(
        val updateLikeStatusAction: UpdateLikeStatusAction? = null,
    )

    @Serializable
    data class UpdateLikeStatusAction(
        /** `LIKE`, `DISLIKE` or `INDIFFERENT`. */
        val status: String? = null,
        val target: Target? = null,
    )

    @Serializable
    data class Target(
        val videoId: String? = null,
    )

    @Serializable
    data class Error(
        val code: Int? = null,
        val message: String? = null,
    )

    /**
     * The state YouTube says the song has now, or null when the answer does not say.
     */
    val status: String?
        get() = actions.orEmpty().firstNotNullOfOrNull { it.updateLikeStatusAction?.status }

    /**
     * The song the answer is about, or null when the answer does not name one.
     */
    val videoId: String?
        get() = actions.orEmpty().firstNotNullOfOrNull { it.updateLikeStatusAction?.target?.videoId }
}

/**
 * What the answer of YouTube to a change of the like state says.
 */
sealed interface LikeChangeAnswer {
    /** YouTube says the song has this state now. */
    data class Changed(
        val status: String,
        val videoId: String?,
    ) : LikeChangeAnswer

    /** YouTube answered, but did not say what it did. */
    data object Unsaid : LikeChangeAnswer

    /** YouTube refused the change. */
    data class Refused(
        val message: String,
    ) : LikeChangeAnswer

    /** The answer could not be read. */
    data class Unreadable(
        val reason: String,
    ) : LikeChangeAnswer
}

/**
 * Reads the answer of YouTube to a change of the like state of a song.
 *
 * Reading the answer is kept apart from the request, so that what the answer says can be checked
 * without a network.
 */
object LikeChange {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun read(body: String): LikeChangeAnswer {
        val parsed =
            try {
                json.decodeFromString<LikeResponse>(body)
            } catch (e: SerializationException) {
                return LikeChangeAnswer.Unreadable(e.message ?: "the answer is not the answer of a like")
            }
        parsed.error?.let { error ->
            return LikeChangeAnswer.Refused(error.message ?: "error ${error.code}")
        }
        val status = parsed.status
            ?: return LikeChangeAnswer.Unsaid
        return LikeChangeAnswer.Changed(status, parsed.videoId)
    }
}
