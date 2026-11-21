package com.maxrave.kotlinytmusicscraper

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The scraper's InnerTube client must never advertise brotli (or zstd) in Accept-Encoding.
 *
 * Google's brotli-serving frontend does not honor the signed-in session: on the frontend edge
 * this app's devices resolve to, an InnerTube request advertising `br` is answered with the
 * anonymous sign-in stub (serviceTrackingParams logged_in=0) even though the same cookie
 * accepted by the account switcher seconds earlier. A request advertising only gzip gets the
 * real page (logged_in=1). Measured against live music.youtube.com on 2026-11-20, see the
 * comment at Ytmusic.createClient().
 */
class AcceptEncodingContractTest {
    private class CapturingEngineFactory(
        private val captured: MutableList<String>,
    ) : HttpClientEngineFactory<HttpClientEngineConfig> {
        override fun create(block: HttpClientEngineConfig.() -> Unit) =
            MockEngine { request ->
                request.headers[HttpHeaders.AcceptEncoding]?.let { captured.add(it) }
                respondOk("")
            }
    }

    @Test
    fun `scraper requests must not advertise brotli or zstd`() = runBlocking<Unit> {
        val captured = mutableListOf<String>()
        val ytmusic = Ytmusic(engineFactory = CapturingEngineFactory(captured))
        // Any request through the real client carries the ContentEncoding plugin's header.
        runCatching { ytmusic.getSwJsData() } // the mock answer is not parseable; the header is what matters
        println("AE wire header: '${captured.firstOrNull()}'")
        assertTrue(captured.isNotEmpty(), "no request captured")
        for (ae in captured) {
            assertTrue("br" !in ae, "Accept-Encoding advertises brotli: $ae")
            assertTrue("zstd" !in ae, "Accept-Encoding advertises zstd: $ae")
            assertTrue(ae.startsWith("gzip"), "Accept-Encoding must lead with gzip: $ae")
        }
        assertNotNull(captured.first())
    }
}
