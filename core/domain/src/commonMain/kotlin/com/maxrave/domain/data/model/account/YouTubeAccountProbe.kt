package com.maxrave.domain.data.model.account

/**
 * What YouTube answered when it was asked which account a cookie belongs to.
 *
 * The three cases are kept apart on purpose. The sign-in used to learn about all of them as
 * "no account", so a dropped request, a rejected cookie and a response whose shape had drifted
 * were all reported to the user as the same "Login failed", and none of them reached the log.
 */
sealed interface YouTubeAccountProbe {
    /** YouTube named the accounts of the session. */
    data class Found(
        val accounts: List<AccountInfo>,
    ) : YouTubeAccountProbe

    /**
     * YouTube answered, but named no account: the cookie is not a signed-in session (yet).
     *
     * [httpStatus] is the status of the answer, [code] the code YouTube itself sent.
     */
    data class NoAccount(
        val httpStatus: Int,
        val code: String?,
    ) : YouTubeAccountProbe

    /** The request did not get an answer at all. */
    data class Failed(
        val httpStatus: Int?,
        val reason: String,
    ) : YouTubeAccountProbe
}
