package com.nettarion.hyperborea.data

import android.content.Context
import android.content.SharedPreferences
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.model.FanMode
import com.nettarion.hyperborea.core.profile.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ProfileUserPreferences(
    context: Context,
    private val logger: AppLogger,
) : UserPreferences {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("user_preferences", Context.MODE_PRIVATE)

    private val _enabledBroadcasts = MutableStateFlow(loadEnabledBroadcasts())
    override val enabledBroadcasts: StateFlow<Set<BroadcastId>> = _enabledBroadcasts

    private val _overlayEnabled = MutableStateFlow(prefs.getBoolean(KEY_OVERLAY_ENABLED, false))
    override val overlayEnabled: StateFlow<Boolean> = _overlayEnabled

    private val _savedSensorAddress = MutableStateFlow(prefs.getString(KEY_SENSOR_ADDRESS, null))
    override val savedSensorAddress: StateFlow<String?> = _savedSensorAddress

    private val _fanMode = MutableStateFlow(loadFanMode())
    override val fanMode: StateFlow<FanMode> = _fanMode

    override fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean) {
        val current = _enabledBroadcasts.value.toMutableSet()
        if (enabled) current.add(id) else current.remove(id)
        _enabledBroadcasts.value = current
        prefs.edit().putStringSet(KEY_ENABLED_BROADCASTS, current.map { it.name }.toSet()).apply()
        logger.i(TAG, "Broadcast ${id.name} ${if (enabled) "enabled" else "disabled"}")
    }

    override fun setOverlayEnabled(enabled: Boolean) {
        _overlayEnabled.value = enabled
        prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
        logger.i(TAG, "Overlay ${if (enabled) "enabled" else "disabled"}")
    }

    override fun setSavedSensorAddress(address: String?) {
        _savedSensorAddress.value = address
        prefs.edit().apply {
            if (address != null) putString(KEY_SENSOR_ADDRESS, address)
            else remove(KEY_SENSOR_ADDRESS)
        }.apply()
        logger.i(TAG, "Sensor address ${address ?: "cleared"}")
    }

    override fun setFanMode(mode: FanMode) {
        _fanMode.value = mode
        prefs.edit().putString(KEY_FAN_MODE, mode.name).apply()
        logger.i(TAG, "Fan mode set to ${mode.name}")
    }

    private fun loadEnabledBroadcasts(): Set<BroadcastId> {
        val stored = prefs.getStringSet(KEY_ENABLED_BROADCASTS, null) ?: return BroadcastId.entries.toSet()
        return stored.mapNotNull { name ->
            try { BroadcastId.valueOf(name) } catch (_: IllegalArgumentException) { null }
        }.toSet()
    }

    private fun loadFanMode(): FanMode {
        val stored = prefs.getString(KEY_FAN_MODE, null) ?: return FanMode.OFF
        return try { FanMode.valueOf(stored) } catch (_: IllegalArgumentException) { FanMode.OFF }
    }

    private companion object {
        const val TAG = "UserPreferences"
        const val KEY_ENABLED_BROADCASTS = "enabled_broadcasts"
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        const val KEY_SENSOR_ADDRESS = "saved_sensor_address"
        const val KEY_FAN_MODE = "fan_mode"
    }
}
