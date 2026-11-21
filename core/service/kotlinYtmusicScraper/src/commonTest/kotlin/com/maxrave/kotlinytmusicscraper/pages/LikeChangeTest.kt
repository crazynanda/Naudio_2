package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.response.LikeChange
import com.maxrave.kotlinytmusicscraper.models.response.LikeChangeAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The answer of YouTube to a change of the like state of a song.
 *
 * The change used to be reported as done when the request was answered with a status code in the
 * two hundreds - which says that YouTube answered, not that the song was liked. A song that
 * YouTube refused to like was therefore reported as liked, the change was dropped from the queue
 * of the pending changes, and the library of the account and the library of the app drifted apart
 * without a word.
 */
class LikeChangeTest {
    @Test
    fun theAnswerOfALikeSaysThatTheSongIsLiked() {
        val body =
            """{"responseContext":{"visitorData":"abc"},
            "actions":[{
                "addToToastAction":{"item":{"notificationTextRenderer":{"successResponseText":{"runs":[{"text":"Saved to Library"}]}}}},
                "updateLikeStatusAction":{"status":"LIKE","target":{"videoId":"the-song"},"likeStatusEntityKey":"key"},
                "reloadContinuationItemsCommand":{"slot":"RELOAD_CONTINUATION_SLOT_HEADER"}
            }],
            "trackingParams":"abc"}"""

        val answer = LikeChange.read(body)

        val changed = assertIs<LikeChangeAnswer.Changed>(answer)
        assertEquals("LIKE", changed.status)
        assertEquals("the-song", changed.videoId)
    }

    @Test
    fun theAnswerOfAnUnlikeSaysThatTheSongIsNotLikedAnyMore() {
        val body =
            """{"actions":[{
                "updateLikeStatusAction":{"status":"INDIFFERENT","target":{"videoId":"the-song"}}
            }]}"""

        val answer = LikeChange.read(body)

        assertEquals("INDIFFERENT", assertIs<LikeChangeAnswer.Changed>(answer).status)
    }

    @Test
    fun anAnswerThatSaysTheSongIsNotLikedIsNotAChangeThatHappened() {
        // The answer of a like that did not happen: YouTube says the song is not liked.
        val body =
            """{"actions":[{
                "updateLikeStatusAction":{"status":"INDIFFERENT","target":{"videoId":"the-song"}}
            }]}"""

        val answer = LikeChange.read(body)

        assertEquals("INDIFFERENT", assertIs<LikeChangeAnswer.Changed>(answer).status)
    }

    @Test
    fun anAnswerThatRefusesTheChangeIsARefusal() {
        val body = """{"error":{"code":400,"message":"Precondition check failed."}}"""

        val answer = LikeChange.read(body)

        val refused = assertIs<LikeChangeAnswer.Refused>(answer)
        assertTrue(refused.message.contains("Precondition"))
    }

    @Test
    fun anAnswerThatSaysNothingIsUnsaid() {
        val body = """{"responseContext":{"visitorData":"abc"},"trackingParams":"abc"}"""

        val answer = LikeChange.read(body)

        assertEquals(LikeChangeAnswer.Unsaid, answer)
    }

    @Test
    fun anAnswerThatIsNotAnAnswerAtAllIsUnreadable() {
        val answer = LikeChange.read("<html>not the answer</html>")

        assertIs<LikeChangeAnswer.Unreadable>(answer)
    }
}
