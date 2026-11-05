package com.maxrave.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficialBuildValidationTest {

    private val naudioCert = "0208a0c5291d07483d23ec92f2c9b5a877d1d49969990e872211c5effc0fb41c"
    private val unknownCert = "1111111111111111111111111111111111111111111111111111111111111111"
    private val secondaryCert = "2222222222222222222222222222222222222222222222222222222222222222"

    @Test
    fun officialNaudioPackage_withCorrectSigningFingerprint_isAccepted() {
        val result = Config.validateOfficialBuild(
            packageName = "com.maxrave.simpmusic",
            signingCerts = listOf(naudioCert),
        )
        assertTrue(result, "Official package with Naudio production certificate must be accepted")

        // Dev flavor and vivo flavor with official fingerprint are also accepted
        assertTrue(Config.validateOfficialBuild("com.maxrave.simpmusic.dev", listOf(naudioCert)))
        assertTrue(Config.validateOfficialBuild("com.spotify.music", listOf(naudioCert)))
    }

    @Test
    fun officialPackage_withUnknownSigningFingerprint_isRejected() {
        val result = Config.validateOfficialBuild(
            packageName = "com.maxrave.simpmusic",
            signingCerts = listOf(unknownCert),
        )
        assertFalse(result, "Official package with unknown signing certificate must be rejected")
    }

    @Test
    fun unapprovedPackage_withCorrectFingerprint_isRejected() {
        val unapprovedPackages = listOf(
            "com.fake.naudio",
            "cn.kuwo.player",
            "com.apple.android.music",
            "com.anxhify.music",
        )
        for (pkg in unapprovedPackages) {
            val result = Config.validateOfficialBuild(
                packageName = pkg,
                signingCerts = listOf(naudioCert),
            )
            assertFalse(result, "Unapproved package '$pkg' must be rejected even with a valid fingerprint")
        }
    }

    @Test
    fun approvedPackage_withEmptySigningCertificates_isNotBlocked() {
        val result = Config.validateOfficialBuild(
            packageName = "com.maxrave.simpmusic",
            signingCerts = emptyList(),
        )
        assertTrue(
            result,
            "An approved package with unavailable/empty signing certificates must not be blocked (unknown result)",
        )

        // Also for other approved flavors
        assertTrue(Config.validateOfficialBuild("com.maxrave.simpmusic.dev", emptyList()))
        assertTrue(Config.validateOfficialBuild("com.spotify.music", emptyList()))
    }

    @Test
    fun unapprovedPackage_withEmptySigningCertificates_isRejected() {
        val result = Config.validateOfficialBuild(
            packageName = "com.fake.app",
            signingCerts = emptyList(),
        )
        assertFalse(
            result,
            "An unapproved package must be rejected even when its certificate list is empty",
        )
    }

    @Test
    fun multipleTrustedSigningKeys_areSupported() {
        val multiKeyTrust = setOf(naudioCert, secondaryCert)

        // Primary key accepted
        assertTrue(
            Config.validateOfficialBuild(
                packageName = "com.maxrave.simpmusic",
                signingCerts = listOf(naudioCert),
                trustedCertHashes = multiKeyTrust,
            ),
            "Primary key should be accepted under multiple trusted keys",
        )

        // Rotated/secondary key accepted
        assertTrue(
            Config.validateOfficialBuild(
                packageName = "com.maxrave.simpmusic",
                signingCerts = listOf(secondaryCert),
                trustedCertHashes = multiKeyTrust,
            ),
            "Rotated secondary key should be accepted under multiple trusted keys",
        )

        // Untrusted key still rejected
        assertFalse(
            Config.validateOfficialBuild(
                packageName = "com.maxrave.simpmusic",
                signingCerts = listOf(unknownCert),
                trustedCertHashes = multiKeyTrust,
            ),
            "Unknown key must be rejected under multiple trusted keys",
        )
    }

    @Test
    fun failureToFetchOptionalRemoteMetadata_doesNotBlockValidNaudioApk() {
        // Null remote keys (e.g. network failure / timeout / 404)
        val nullRemoteResult = Config.validateOfficialBuild(
            packageName = "com.maxrave.simpmusic",
            signingCerts = listOf(naudioCert),
            remoteKeys = null,
        )
        assertTrue(nullRemoteResult, "Network failure / null remote keys must not block genuine Naudio APK")

        // Empty remote keys
        val emptyRemoteResult = Config.validateOfficialBuild(
            packageName = "com.maxrave.simpmusic",
            signingCerts = listOf(naudioCert),
            remoteKeys = emptyList(),
        )
        assertTrue(emptyRemoteResult, "Empty remote keys list must not block genuine Naudio APK")

        // Remote metadata containing different / legacy keys (such as original SimpMusic key)
        val legacyRemoteKeys = listOf("d7a85be9419cf0e81881cf69beabf9be016d910795c721f599ddfd44c9b1395b")
        val withLegacyRemoteResult = Config.validateOfficialBuild(
            packageName = "com.maxrave.simpmusic",
            signingCerts = listOf(naudioCert),
            remoteKeys = legacyRemoteKeys,
        )
        assertTrue(
            withLegacyRemoteResult,
            "Presence of other remote keys must not override or block Naudio's production key",
        )
    }

    @Test
    fun fingerprintNormalization_normalizesVariationsCorrectly() {
        val withColons = "02:08:A0:C5:29:1D:07:48:3D:23:EC:92:F2:C9:B5:A8:77:D1:D4:99:69:99:0E:87:22:11:C5:EF:FC:0F:B4:1C"
        val normalized = Config.normalizeSigningFingerprint(withColons)
        assertEquals(naudioCert, normalized, "Colons and uppercase must normalize to lowercase 64-char hex")

        val withSpaces = "  0208a0c5291d07483d23ec92f2c9b5a877d1d49969990e872211c5effc0fb41c  "
        assertEquals(naudioCert, Config.normalizeSigningFingerprint(withSpaces))

        // Malformed inputs return null
        assertNull(Config.normalizeSigningFingerprint("short"))
        assertNull(Config.normalizeSigningFingerprint("zzzz" + "0".repeat(60)))
        assertNull(Config.normalizeSigningFingerprint(""))
    }
}
