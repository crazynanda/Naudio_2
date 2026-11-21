package com.maxrave.kotlinytmusicscraper

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No InnerTube request may carry a header name twice (case-insensitively) once it reaches the
 * wire.
 *
 * Ktor's mergeHeaders joins the values of a repeated header with commas — everything except
 * Cookie. The scraper used to append `X-Goog-AuthUser` twice (once under a different case), so
 * the wire header became `X-Goog-AuthUser: 0,0`, and Google's frontend served the anonymous
 * sign-in interstitial for every InnerTube call that carried it — exactly the shape of an
 * expired session, with the session itself perfectly valid. Proven on device 2026-11-21: the
 * same request with the clean single value returned the logged-in liked-songs page (73 kB)
 * while the merged value returned the interstitial (4 kB).
 *
 * This test reads the header set the way the wire does (values of one name already merged) and
 * asserts no header that must be single-valued was repeated. The three Accept appends are
 * intentional: their comma-merge is what a browser sends and Google accepts it (proven with the
 * same replay matrix that convicted X-Goog-AuthUser).
 */
class InnerTubeHeaderContractTest {
    private class CapturingEngineFactory(
        private val captured: MutableList<HttpRequestData>,
    ) : HttpClientEngineFactory<HttpClientEngineConfig> {
        override fun create(block: HttpClientEngineConfig.() -> Unit) =
            MockEngine { request ->
                captured.add(request)
                respondOk("")
            }
    }

    /** Headers the scraper appends more than once on purpose; their merge is known-good. */
    private val allowedRepeated = setOf(HttpHeaders.Accept)

    @Test
    fun `no header value is merged by repetition`() = runBlocking<Unit> {
        val captured = mutableListOf<HttpRequestData>()
        val ytmusic = Ytmusic(engineFactory = CapturingEngineFactory(captured))
        runCatching { ytmusic.getSwJsData() }
        assertTrue(captured.isNotEmpty(), "no request captured")

        for (request in captured) {
            for (name in request.headers.names()) {
                val values = request.headers.getAll(name) ?: continue
                if (name in allowedRepeated) continue
                // A header appended once must arrive once. Repetition is merged into a
                // comma-joined value on the wire and Google refuses such requests.
                assertEquals(1, values.size, "header appended more than once: $name -> $values")
                assertTrue(
                    !values.single().contains(",") || name == HttpHeaders.Cookie,
                    "header value looks merged from repetitions: $name = ${values.single()}",
                )
            }
            request.headers["X-Goog-AuthUser"]?.let { authUser ->
                assertTrue(
                    authUser.toIntOrNull() != null,
                    "X-Goog-AuthUser must be a single integer, was: $authUser",
                )
            }
        }
    }
}
