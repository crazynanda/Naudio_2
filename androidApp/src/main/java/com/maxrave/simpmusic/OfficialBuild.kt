package com.maxrave.simpmusic

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.maxrave.common.Config
import java.security.MessageDigest

/** SHA-256 hex of each certificate this APK is signed with — normalized lowercase hex without colons. */
fun Context.signingCertSha256(): List<String> {
    val signatures: Array<Signature>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
    return signatures.orEmpty().mapNotNull { signature ->
        val rawHex = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        Config.normalizeSigningFingerprint(rawHex)
    }
}
