package com.nettarion.hyperborea.di

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nettarion.hyperborea.BuildConfig
import com.nettarion.hyperborea.core.LicenseChecker
import com.nettarion.hyperborea.platform.license.HttpUrlConnectionLicenseClient
import com.nettarion.hyperborea.platform.license.LicenseCheckerImpl
import com.nettarion.hyperborea.platform.license.LicenseHttpClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LicenseModule {

    @Binds
    @Singleton
    abstract fun bindLicenseChecker(impl: LicenseCheckerImpl): LicenseChecker

    @Binds
    @Singleton
    abstract fun bindLicenseHttpClient(impl: HttpUrlConnectionLicenseClient): LicenseHttpClient

    companion object {
        private const val TAG = "LicenseModule"
        private const val OLD_PREFS_NAME = "hyperborea_license"
        private const val ENCRYPTED_PREFS_NAME = "hyperborea_license_encrypted"

        @Provides
        @Singleton
        fun provideLicensePreferences(
            @ApplicationContext context: Context,
        ): SharedPreferences {
            val encrypted = try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    ENCRYPTED_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Exception) {
                Log.w(TAG, "KeyStore unavailable, falling back to plaintext prefs", e)
                return context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)
            }
            migrateFromPlaintext(context, encrypted)
            return encrypted
        }

        private fun migrateFromPlaintext(context: Context, encrypted: SharedPreferences) {
            val old = context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)
            if (old.all.isEmpty()) return
            val editor = encrypted.edit()
            for ((key, value) in old.all) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            editor.apply()
            old.edit().clear().apply()
            Log.i(TAG, "Migrated license prefs to encrypted storage")
        }

        @Provides
        @Named("licensePublicKey")
        fun provideLicensePublicKey(): String = BuildConfig.LICENSE_PUBLIC_KEY
    }
}
