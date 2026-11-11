package org.simpmusic.loginsync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoginSyncFavoritesTest {

    @Test
    fun clientAndServerExchangeFavoritesOverEncryptedTransport() = runBlocking {
        var serverReceived: String? = null
        val server = LoginSyncServer(
            object : LoginSyncServer.Handler {
                override fun onHello() {}
                override suspend fun onCancel() {}
                override suspend fun onPayload(payload: ByteArray): ByteArray = "logins_ok".encodeToByteArray()
                override suspend fun onFavorites(payload: ByteArray): ByteArray {
                    serverReceived = payload.decodeToString()
                    return """{"status":"windows_favorites_bundle"}""".encodeToByteArray()
                }
                override fun onDelivered() {}
                override fun onRejected(reason: String) {}
            },
        )

        val session = server.start()
        try {
            val client = LoginSyncClient()
            val device = client.connect(session.invite("127.0.0.1"))
            assertTrue(device.name.isNotBlank())

            val clientPayload = """{"status":"android_favorites_bundle"}""".encodeToByteArray()
            val replyBytes = client.sendFavorites(clientPayload)
            val reply = replyBytes.decodeToString()

            assertEquals("""{"status":"android_favorites_bundle"}""", serverReceived)
            assertEquals("""{"status":"windows_favorites_bundle"}""", reply)

            // Multiple favorites sync operations can be performed over the same session
            val secondPayload = """{"status":"android_batch_2"}""".encodeToByteArray()
            val secondReply = client.sendFavorites(secondPayload).decodeToString()
            assertEquals("""{"status":"android_batch_2"}""", serverReceived)
            assertEquals("""{"status":"windows_favorites_bundle"}""", secondReply)
        } finally {
            server.stop()
        }
    }

    @Test
    fun payloadExceedingMaxBodyBytesIsRejected() = runBlocking {
        val server = LoginSyncServer(
            object : LoginSyncServer.Handler {
                override fun onHello() {}
                override suspend fun onCancel() {}
                override suspend fun onPayload(payload: ByteArray): ByteArray = "ok".encodeToByteArray()
                override suspend fun onFavorites(payload: ByteArray): ByteArray = "ok".encodeToByteArray()
                override fun onDelivered() {}
                override fun onRejected(reason: String) {}
            },
        )
        val session = server.start()
        try {
            val client = LoginSyncClient()
            client.connect(session.invite("127.0.0.1"))

            // Payload greater than 4 MB
            val hugePayload = ByteArray(LoginSyncWire.MAX_BODY_BYTES + 1024)
            val exception = kotlin.test.assertFailsWith<LoginSyncTransportException> {
                client.sendFavorites(hugePayload)
            }
            assertTrue(
                exception.message?.contains("exceeds transport limit") == true ||
                    exception.message?.contains("413") == true ||
                    exception.message?.contains("FAILED") == true,
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun completeSequenceFavoritesThenLoginThenSyncAgainThenBlockedLogins() = runBlocking {
        val events = mutableListOf<String>()
        var lastFavoritesPayload: String? = null
        val server = LoginSyncServer(
            object : LoginSyncServer.Handler {
                override fun onHello() { events += "hello" }
                override suspend fun onCancel() { events += "cancel" }
                override suspend fun onPayload(payload: ByteArray): ByteArray {
                    events += "payload:${payload.decodeToString()}"
                    return "ack_logins".encodeToByteArray()
                }
                override suspend fun onFavorites(payload: ByteArray): ByteArray {
                    lastFavoritesPayload = payload.decodeToString()
                    events += "favorites:$lastFavoritesPayload"
                    return """{"status":"host_favs"}""".encodeToByteArray()
                }
                override fun onDelivered() { events += "delivered" }
                override fun onRejected(reason: String) { events += "rejected:$reason" }
            },
        )
        val session = server.start()
        try {
            val client = LoginSyncClient()

            // 1. Connect Android to Windows
            val device = client.connect(session.invite("127.0.0.1"))
            assertTrue(device.name.isNotBlank())
            assertEquals(listOf("hello"), events)

            // 2. Synchronize favorites
            val favResult1 = client.sendFavorites("""{"fav":"initial_batch"}""".encodeToByteArray()).decodeToString()
            assertEquals("""{"status":"host_favs"}""", favResult1)
            assertEquals("""{"fav":"initial_batch"}""", lastFavoritesPayload)

            // 3. Transfer login credentials
            val loginAck = client.send("""{"login":"youtube"}""".encodeToByteArray()).decodeToString()
            assertEquals("ack_logins", loginAck)
            assertTrue(events.contains("delivered"))

            // 4. Press Sync Again & 5. Synchronize newly added or removed favorites successfully
            val favResult2 = client.sendFavorites("""{"fav":"sync_again_new_tracks"}""".encodeToByteArray()).decodeToString()
            assertEquals("""{"status":"host_favs"}""", favResult2)
            assertEquals("""{"fav":"sync_again_new_tracks"}""", lastFavoritesPayload)

            // 6. Verify that login credentials were not transferred again
            val refused = kotlin.test.assertFailsWith<LoginSyncTransportException> {
                client.send("""{"login":"spotify"}""".encodeToByteArray())
            }
            assertEquals(LoginSyncTransportException.Kind.REFUSED, refused.kind)

            // 7. Verify the server stops after the session expires or the dialog closes
            server.stop()
            assertFailsWith<LoginSyncTransportException> {
                client.sendFavorites("""{"fav":"after_stop"}""".encodeToByteArray())
            }
            val stoppedConnect = kotlin.test.assertFailsWith<LoginSyncTransportException> {
                LoginSyncClient().connect(session.invite("127.0.0.1"))
            }
            assertEquals(LoginSyncTransportException.Kind.UNREACHABLE, stoppedConnect.kind)
        } finally {
            server.stop()
        }
    }

    @Test
    fun favoritesOnlyFlow() = runBlocking {
        var favCount = 0
        val server = LoginSyncServer(
            object : LoginSyncServer.Handler {
                override fun onHello() {}
                override suspend fun onCancel() {}
                override suspend fun onPayload(payload: ByteArray): ByteArray = "logins".encodeToByteArray()
                override suspend fun onFavorites(payload: ByteArray): ByteArray {
                    favCount++
                    return """{"count":$favCount}""".encodeToByteArray()
                }
                override fun onDelivered() {}
                override fun onRejected(reason: String) {}
            },
        )
        val session = server.start()
        try {
            val client = LoginSyncClient()
            client.connect(session.invite("127.0.0.1"))

            // Sync favorites
            val r1 = client.sendFavorites("""{"item":"song1"}""".encodeToByteArray()).decodeToString()
            assertEquals("""{"count":1}""", r1)

            // Sync again
            val r2 = client.sendFavorites("""{"item":"song2"}""".encodeToByteArray()).decodeToString()
            assertEquals("""{"count":2}""", r2)

            // Close session / server stops
            server.stop()
            assertFailsWith<LoginSyncTransportException> {
                client.sendFavorites("""{"item":"song3"}""".encodeToByteArray())
            }
            val stopped = kotlin.test.assertFailsWith<LoginSyncTransportException> {
                LoginSyncClient().connect(session.invite("127.0.0.1"))
            }
            assertEquals(LoginSyncTransportException.Kind.UNREACHABLE, stopped.kind)
        } finally {
            server.stop()
        }
    }

    @Test
    fun loginOnlyFlow() = runBlocking {
        var loginsDelivered = false
        val server = LoginSyncServer(
            object : LoginSyncServer.Handler {
                override fun onHello() {}
                override suspend fun onCancel() {}
                override suspend fun onPayload(payload: ByteArray): ByteArray = "ok_login".encodeToByteArray()
                override suspend fun onFavorites(payload: ByteArray): ByteArray = "ok_fav".encodeToByteArray()
                override fun onDelivered() { loginsDelivered = true }
                override fun onRejected(reason: String) {}
            },
        )
        val session = server.start()
        try {
            val client = LoginSyncClient()
            client.connect(session.invite("127.0.0.1"))

            // Transfer login credentials
            val reply = client.send("""{"user":"test"}""".encodeToByteArray()).decodeToString()
            assertEquals("ok_login", reply)
            assertTrue(loginsDelivered)

            // Re-transfer is blocked
            val blocked = kotlin.test.assertFailsWith<LoginSyncTransportException> {
                client.send("""{"user":"another"}""".encodeToByteArray())
            }
            assertEquals(LoginSyncTransportException.Kind.REFUSED, blocked.kind)

            // Server stop
            server.stop()
            val unreachable = kotlin.test.assertFailsWith<LoginSyncTransportException> {
                client.connect(session.invite("127.0.0.1"))
            }
            assertEquals(LoginSyncTransportException.Kind.UNREACHABLE, unreachable.kind)
        } finally {
            server.stop()
        }
    }

    @Test
    fun concurrentDuplicateLoginRequestsOnlyOneSucceeds() = runBlocking {
        val payloadCount = AtomicInteger(0)
        val deliveredCount = AtomicInteger(0)
        val server = LoginSyncServer(
            object : LoginSyncServer.Handler {
                override fun onHello() {}
                override suspend fun onCancel() {}
                override suspend fun onPayload(payload: ByteArray): ByteArray {
                    payloadCount.incrementAndGet()
                    delay(100) // Ensure concurrent overlap
                    return "ack_concurrent".encodeToByteArray()
                }
                override suspend fun onFavorites(payload: ByteArray): ByteArray = "fav_ack".encodeToByteArray()
                override fun onDelivered() {
                    deliveredCount.incrementAndGet()
                }
                override fun onRejected(reason: String) {}
            },
        )
        val session = server.start()
        try {
            val client1 = LoginSyncClient()
            val client2 = LoginSyncClient()
            client1.connect(session.invite("127.0.0.1"))
            client2.connect(session.invite("127.0.0.1"))

            val d1 = async(Dispatchers.IO) {
                runCatching { client1.send("""{"client":1}""".encodeToByteArray()) }
            }
            val d2 = async(Dispatchers.IO) {
                runCatching { client2.send("""{"client":2}""".encodeToByteArray()) }
            }

            val res1 = d1.await()
            val res2 = d2.await()

            // Exactly one should succeed, and one should fail with REFUSED (HTTP 410)
            val successCount = listOf(res1, res2).count { it.isSuccess }
            val failureCount = listOf(res1, res2).count { it.isFailure }
            assertEquals(1, successCount, "Exactly one login payload request must succeed")
            assertEquals(1, failureCount, "Duplicate concurrent login payload request must fail")

            val failedResult = if (res1.isFailure) res1 else res2
            val exception = failedResult.exceptionOrNull()
            assertTrue(exception is LoginSyncTransportException)
            assertEquals(LoginSyncTransportException.Kind.REFUSED, exception.kind)

            // Only one payload processed and delivered
            assertEquals(1, payloadCount.get(), "handler.onPayload() must only be invoked once")
            assertEquals(1, deliveredCount.get(), "handler.onDelivered() must only be invoked once")

            // Favorites remain available after the login operation
            val favReply = client1.sendFavorites("""{"fav":"post_login"}""".encodeToByteArray()).decodeToString()
            assertEquals("fav_ack", favReply)
        } finally {
            server.stop()
        }
    }

    @Test
    fun failedLoginTransferDoesNotPermanentlyConsumeOneTimeOperation() = runBlocking {
        var attempts = 0
        var delivered = false
        val server = LoginSyncServer(
            object : LoginSyncServer.Handler {
                override fun onHello() {}
                override suspend fun onCancel() {}
                override suspend fun onPayload(payload: ByteArray): ByteArray {
                    attempts++
                    if (attempts == 1) {
                        throw IllegalStateException("simulated failure during first payload processing")
                    }
                    return "success_payload".encodeToByteArray()
                }
                override suspend fun onFavorites(payload: ByteArray): ByteArray = "fav_ack".encodeToByteArray()
                override fun onDelivered() {
                    delivered = true
                }
                override fun onRejected(reason: String) {}
            },
        )
        val session = server.start()
        try {
            val client = LoginSyncClient()
            client.connect(session.invite("127.0.0.1"))

            // First attempt fails due to server-side error
            val firstAttempt = assertFailsWith<LoginSyncTransportException> {
                client.send("""{"try":1}""".encodeToByteArray())
            }
            assertEquals(LoginSyncTransportException.Kind.FAILED, firstAttempt.kind)
            assertTrue(!delivered, "Delivered should not be set on failure")

            // Second attempt succeeds
            val reply = client.send("""{"try":2}""".encodeToByteArray()).decodeToString()
            assertEquals("success_payload", reply)
            assertTrue(delivered, "Delivered must be set after successful response")

            // Third attempt is now blocked (REFUSED / 410)
            val thirdAttempt = assertFailsWith<LoginSyncTransportException> {
                client.send("""{"try":3}""".encodeToByteArray())
            }
            assertEquals(LoginSyncTransportException.Kind.REFUSED, thirdAttempt.kind)
        } finally {
            server.stop()
        }
    }

    @Test
    fun independentFavoritesRequestsNotBlockedByLoginPayloadProcessing() = runBlocking {
        val loginStarted = CompletableDeferred<Unit>()
        val canCompleteLogin = CompletableDeferred<Unit>()
        val server = LoginSyncServer(
            object : LoginSyncServer.Handler {
                override fun onHello() {}
                override suspend fun onCancel() {}
                override suspend fun onPayload(payload: ByteArray): ByteArray {
                    loginStarted.complete(Unit)
                    canCompleteLogin.await()
                    return "login_done".encodeToByteArray()
                }
                override suspend fun onFavorites(payload: ByteArray): ByteArray {
                    return "favorites_processed_immediately".encodeToByteArray()
                }
                override fun onDelivered() {}
                override fun onRejected(reason: String) {}
            },
        )
        val session = server.start()
        try {
            val loginClient = LoginSyncClient()
            val favClient = LoginSyncClient()
            loginClient.connect(session.invite("127.0.0.1"))
            favClient.connect(session.invite("127.0.0.1"))

            val loginTask = async(Dispatchers.IO) {
                loginClient.send("""{"user":"slow"}""".encodeToByteArray()).decodeToString()
            }

            // Wait until server has entered onPayload
            loginStarted.await()

            // Send favorites request concurrently while login payload is still in progress
            val favResult = favClient.sendFavorites("""{"fav":"concurrent"}""".encodeToByteArray()).decodeToString()
            assertEquals("favorites_processed_immediately", favResult)

            // Now allow login payload to finish
            canCompleteLogin.complete(Unit)
            val loginResult = loginTask.await()
            assertEquals("login_done", loginResult)
        } finally {
            server.stop()
        }
    }
}
