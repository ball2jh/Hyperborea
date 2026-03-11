package com.nettarion.hyperborea.platform

import com.nettarion.hyperborea.BuildConfig

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

@Suppress("DEPRECATION")
object SignatureVerifier {

    private const val TAG = "SignatureVerify"

    fun verify(context: Context): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
            val signature = packageInfo.signatures?.firstOrNull() ?: run {
                Log.e(TAG, "No signatures found")
                return false
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            val actual = digest.joinToString("") { "%02X".format(it) }
            val expected = BuildConfig.SIGNING_CERTIFICATE_SHA256
            if (expected.isEmpty()) {
                Log.e(TAG, "No expected fingerprint configured, failing verification")
                return false
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                Log.e(TAG, "Signature mismatch")
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Verification failed", e)
            false
        }
    }
}
