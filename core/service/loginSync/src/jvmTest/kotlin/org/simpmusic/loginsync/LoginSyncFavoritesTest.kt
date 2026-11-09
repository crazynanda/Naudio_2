package org.simpmusic.loginsync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoginSyncFavoritesTest {

    @Test
    fun clientAndServerExchangeFavoritesOverEncryptedTransport() = runBlocking {
        var serverReceived: String? = null
        val server = LoginSyncServer(
            object : LoginSyncServer.Handler {
                override fun onHello() {}
                override fun onCancel() {}
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
}
