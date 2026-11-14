package org.simpmusic.loginsync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginSyncCancellationConcurrencyTest {

    /**
     * Scenario 1: CANCEL arrives while a favorites batch is being processed.
     * The server must hold favoritesMutex so CANCEL suspends until favorites processing and reply finishes.
     */
    @Test
    fun cancelArrivesWhileFavoritesBatchIsMerging() = runBlocking {
        val favoritesStarted = CompletableDeferred<Unit>()
        val canCompleteFavorites = CompletableDeferred<Unit>()
        val cancelReachedServer = CompletableDeferred<Unit>()
        val cancelExecuted = AtomicBoolean(false)
        val favoritesCompleted = AtomicBoolean(false)

        val server = LoginSyncServer(
            handler = object : LoginSyncServer.Handler {
                override fun onHello() {}
                override suspend fun onCancel() {
                    cancelExecuted.set(true)
                }
                override suspend fun onPayload(payload: ByteArray): ByteArray = "{}".encodeToByteArray()
                override suspend fun onFavorites(payload: ByteArray): ByteArray {
                    favoritesStarted.complete(Unit)
                    canCompleteFavorites.await()
                    favoritesCompleted.set(true)
                    return """{"status":"fav_ok"}""".encodeToByteArray()
                }
                override fun onDelivered() {}
                override fun onRejected(reason: String) {}
            },
            onBeforeCancelLock = {
                cancelReachedServer.complete(Unit)
            },
        )

        val session = server.start()
        try {
            val favClient = LoginSyncClient()
            val cancelClient = LoginSyncClient()
            favClient.connect(session.invite("127.0.0.1"))
            cancelClient.connect(session.invite("127.0.0.1"))

            val favTask = async(Dispatchers.IO) {
                favClient.sendFavorites("""{"batch":0}""".encodeToByteArray()).decodeToString()
            }

            // Wait until server has entered onFavorites (favoritesMutex acquired)
            favoritesStarted.await()

            // Concurrently send CANCEL while favorites processing is ongoing
            val cancelTask = async(Dispatchers.IO) {
                cancelClient.cancelSync()
            }

            // Explicitly prove CANCEL has arrived at the server and is waiting for favoritesMutex
            cancelReachedServer.await()

            // CANCEL must be blocked by favoritesMutex and must NOT have executed yet
            assertFalse(cancelExecuted.get(), "CANCEL must wait for in-flight favorites operation")
            assertFalse(favoritesCompleted.get(), "Favorites operation still in progress")

            // Now let favorites finish
            canCompleteFavorites.complete(Unit)

            val favReply = favTask.await()
            assertEquals("""{"status":"fav_ok"}""", favReply)
            assertTrue(favoritesCompleted.get(), "Favorites completed successfully")

            // Now CANCEL finishes
            cancelTask.await()
            assertTrue(cancelExecuted.get(), "CANCEL executed only after favorites completed")
        } finally {
            server.stop()
        }
    }

    /**
     * Scenario 2: CANCEL arrives between favorites batch preparation and reply completion.
     * favoritesMutex wraps both onFavorites and respondBytes, guaranteeing client receives the reply.
     */
    @Test
    fun cancelArrivesBetweenFavoritesBatchPreparationAndReplyCompletion() = runBlocking {
        val favoritesPrepDone = CompletableDeferred<Unit>()
        val canCompleteReply = CompletableDeferred<Unit>()
        val cancelReachedServer = CompletableDeferred<Unit>()
        val cancelExecuted = AtomicBoolean(false)

        val server = LoginSyncServer(
            handler = object : LoginSyncServer.Handler {
                override fun onHello() {}
                override suspend fun onCancel() {
                    cancelExecuted.set(true)
                }
                override suspend fun onPayload(payload: ByteArray): ByteArray = "{}".encodeToByteArray()
                override suspend fun onFavorites(payload: ByteArray): ByteArray {
                    favoritesPrepDone.complete(Unit)
                    return """{"batch_prep":"complete"}""".encodeToByteArray()
                }
                override fun onDelivered() {}
                override fun onRejected(reason: String) {}
            },
            onBeforeCancelLock = {
                cancelReachedServer.complete(Unit)
            },
            onFavoritesProcessing = {
                canCompleteReply.await()
            },
        )

        val session = server.start()
        try {
            val favClient = LoginSyncClient()
            val cancelClient = LoginSyncClient()
            favClient.connect(session.invite("127.0.0.1"))
            cancelClient.connect(session.invite("127.0.0.1"))

            val favTask = async(Dispatchers.IO) {
                favClient.sendFavorites("""{"req":1}""".encodeToByteArray()).decodeToString()
            }

            favoritesPrepDone.await()

            val cancelTask = async(Dispatchers.IO) {
                cancelClient.cancelSync()
            }

            // Explicitly prove CANCEL has reached the server and is suspended waiting on favoritesMutex
            cancelReachedServer.await()
            assertFalse(cancelExecuted.get(), "CANCEL must wait while favorites reply is in-flight")

            // Release reply transmission
            canCompleteReply.complete(Unit)

            val favReply = favTask.await()
            assertEquals("""{"batch_prep":"complete"}""", favReply)

            cancelTask.await()
            assertTrue(cancelExecuted.get(), "CANCEL executed after favorites reply completed")
        } finally {
            server.stop()
        }
    }

    /**
     * Scenario 3: CANCEL arrives while a login payload is being restored.
     * CANCEL must wait for loginPayloadMutex and not disrupt login restoration or delivery completion.
     */
    @Test
    fun cancelArrivesWhileLoginPayloadIsBeingRestored() = runBlocking {
        val loginStarted = CompletableDeferred<Unit>()
        val canCompleteLogin = CompletableDeferred<Unit>()
        val cancelReachedServer = CompletableDeferred<Unit>()
        val cancelExecuted = AtomicBoolean(false)
        val loginDelivered = AtomicBoolean(false)

        val server = LoginSyncServer(
            handler = object : LoginSyncServer.Handler {
                override fun onHello() {}
                override suspend fun onCancel() {
                    cancelExecuted.set(true)
                }
                override suspend fun onPayload(payload: ByteArray): ByteArray {
                    loginStarted.complete(Unit)
                    canCompleteLogin.await()
                    return "ack".encodeToByteArray()
                }
                override suspend fun onFavorites(payload: ByteArray): ByteArray = "{}".encodeToByteArray()
                override fun onDelivered() {
                    loginDelivered.set(true)
                }
                override fun onRejected(reason: String) {}
            },
            onBeforeCancelLock = {
                cancelReachedServer.complete(Unit)
            },
        )

        val session = server.start()
        try {
            val loginClient = LoginSyncClient()
            val cancelClient = LoginSyncClient()
            loginClient.connect(session.invite("127.0.0.1"))
            cancelClient.connect(session.invite("127.0.0.1"))

            val loginTask = async(Dispatchers.IO) {
                loginClient.send("""{"creds":"user"}""".encodeToByteArray()).decodeToString()
            }

            loginStarted.await()

            val cancelTask = async(Dispatchers.IO) {
                cancelClient.cancelSync()
            }

            // Explicitly prove CANCEL has reached the server and is suspended waiting on loginPayloadMutex
            cancelReachedServer.await()
            assertFalse(cancelExecuted.get(), "CANCEL must wait for in-flight login payload")
            assertFalse(loginDelivered.get(), "Login not yet delivered")

            canCompleteLogin.complete(Unit)

            val reply = loginTask.await()
            assertEquals("ack", reply)
            assertTrue(loginDelivered.get(), "Login delivered successfully")

            cancelTask.await()
            assertTrue(cancelExecuted.get(), "CANCEL executed after login was delivered")
        } finally {
            server.stop()
        }
    }

    /**
     * Scenario 4: CANCEL arrives after successful favorites completion.
     */
    @Test
    fun cancelArrivesAfterSuccessfulFavoritesCompletion() = runBlocking {
        val events = mutableListOf<String>()
        val server = LoginSyncServer(
            handler = object : LoginSyncServer.Handler {
                override fun onHello() { events += "hello" }
                override suspend fun onCancel() { events += "cancel" }
                override suspend fun onPayload(payload: ByteArray): ByteArray = "{}".encodeToByteArray()
                override suspend fun onFavorites(payload: ByteArray): ByteArray {
                    events += "favorites"
                    return """{"status":"done"}""".encodeToByteArray()
                }
                override fun onDelivered() {}
                override fun onRejected(reason: String) {}
            },
        )

        val session = server.start()
        try {
            val client = LoginSyncClient()
            client.connect(session.invite("127.0.0.1"))

            val reply = client.sendFavorites("""{"test":"data"}""".encodeToByteArray()).decodeToString()
            assertEquals("""{"status":"done"}""", reply)

            client.cancelSync()
            assertEquals(listOf("hello", "favorites", "cancel"), events)
        } finally {
            server.stop()
        }
    }

    /**
     * Scenario 5: CANCEL arrives after successful login delivery.
     */
    @Test
    fun cancelArrivesAfterSuccessfulLoginDelivery() = runBlocking {
        val events = mutableListOf<String>()
        val server = LoginSyncServer(
            handler = object : LoginSyncServer.Handler {
                override fun onHello() { events += "hello" }
                override suspend fun onCancel() { events += "cancel" }
                override suspend fun onPayload(payload: ByteArray): ByteArray {
                    events += "payload"
                    return "ack".encodeToByteArray()
                }
                override suspend fun onFavorites(payload: ByteArray): ByteArray = "{}".encodeToByteArray()
                override fun onDelivered() {
                    events += "delivered"
                }
                override fun onRejected(reason: String) {}
            },
        )

        val session = server.start()
        try {
            val client = LoginSyncClient()
            client.connect(session.invite("127.0.0.1"))

            val reply = client.send("""{"test":"creds"}""".encodeToByteArray()).decodeToString()
            assertEquals("ack", reply)

            client.cancelSync()
            assertEquals(listOf("hello", "payload", "delivered", "cancel"), events)
        } finally {
            server.stop()
        }
    }
}
