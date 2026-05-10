package com.nettarion.hyperborea.platform

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable, randomly-generated identifier for this app installation.
 *
 * Generated on first access and persisted in private SharedPreferences. Used only to correlate
 * support-diagnostics uploads from the same device; it carries no personal information and is
 * reset by clearing app data or reinstalling. Falls back to an ephemeral id if storage is
 * unavailable.
 */
@Singleton
class InstallId @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val value: String by lazy {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { generated ->
                prefs.edit().putString(KEY, generated).apply()
            }
        }.getOrElse { UUID.randomUUID().toString() }
    }

    private companion object {
        const val PREFS_NAME = "hyperborea_install"
        const val KEY = "install_id"
    }
}
