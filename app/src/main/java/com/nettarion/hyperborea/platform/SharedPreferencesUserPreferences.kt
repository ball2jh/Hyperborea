package com.nettarion.hyperborea.platform

import android.content.Context
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.BroadcastId
import com.nettarion.hyperborea.core.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedPreferencesUserPreferences(
    context: Context,
    private val logger: AppLogger,
) : UserPreferences {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabledBroadcasts = MutableStateFlow(loadEnabledBroadcasts())
    override val enabledBroadcasts: StateFlow<Set<BroadcastId>> = _enabledBroadcasts.asStateFlow()

    override fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean) {
        logger.i(TAG, "Broadcast ${id.name} ${if (enabled) "enabled" else "disabled"}")
        val current = _enabledBroadcasts.value.toMutableSet()
        if (enabled) current.add(id) else current.remove(id)
        _enabledBroadcasts.value = current
        prefs.edit()
            .putStringSet(KEY_ENABLED_BROADCASTS, current.map { it.name }.toSet())
            .apply()
    }

    private fun loadEnabledBroadcasts(): Set<BroadcastId> {
        val stored = prefs.getStringSet(KEY_ENABLED_BROADCASTS, null)
            ?: return BroadcastId.entries.toSet()
        val result = stored.mapNotNull { name ->
            try { BroadcastId.valueOf(name) } catch (_: IllegalArgumentException) { null }
        }.toSet()
        logger.d(TAG, "Loaded enabled broadcasts: ${result.map { it.name }}")
        return result
    }

    private companion object {
        const val TAG = "UserPreferences"
        const val PREFS_NAME = "user_preferences"
        const val KEY_ENABLED_BROADCASTS = "enabled_broadcasts"
    }
}
