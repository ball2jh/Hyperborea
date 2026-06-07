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

    private val _immersiveModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_IMMERSIVE_MODE, true))
    override val immersiveModeEnabled: StateFlow<Boolean> = _immersiveModeEnabled

    private val _useImperial = MutableStateFlow(prefs.getBoolean(KEY_USE_IMPERIAL, true))
    override val useImperial: StateFlow<Boolean> = _useImperial

    private val _screenSleepEnabled = MutableStateFlow(prefs.getBoolean(KEY_SCREEN_SLEEP_ENABLED, false))
    override val screenSleepEnabled: StateFlow<Boolean> = _screenSleepEnabled

    private val _screenSleepTimeoutMinutes =
        MutableStateFlow(prefs.getInt(KEY_SCREEN_SLEEP_TIMEOUT_MINUTES, DEFAULT_SCREEN_SLEEP_MINUTES))
    override val screenSleepTimeoutMinutes: StateFlow<Int> = _screenSleepTimeoutMinutes

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

    override fun setImmersiveModeEnabled(enabled: Boolean) {
        _immersiveModeEnabled.value = enabled
        prefs.edit().putBoolean(KEY_IMMERSIVE_MODE, enabled).apply()
        logger.i(TAG, "Immersive mode ${if (enabled) "enabled" else "disabled"}")
    }

    override fun setUseImperial(enabled: Boolean) {
        _useImperial.value = enabled
        prefs.edit().putBoolean(KEY_USE_IMPERIAL, enabled).apply()
        logger.i(TAG, "Units set to ${if (enabled) "imperial" else "metric"}")
    }

    override fun setScreenSleepEnabled(enabled: Boolean) {
        _screenSleepEnabled.value = enabled
        prefs.edit().putBoolean(KEY_SCREEN_SLEEP_ENABLED, enabled).apply()
        logger.i(TAG, "Screen sleep ${if (enabled) "enabled" else "disabled"}")
    }

    override fun setScreenSleepTimeoutMinutes(minutes: Int) {
        _screenSleepTimeoutMinutes.value = minutes
        prefs.edit().putInt(KEY_SCREEN_SLEEP_TIMEOUT_MINUTES, minutes).apply()
        logger.i(TAG, "Screen sleep timeout set to $minutes min")
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
        const val KEY_IMMERSIVE_MODE = "immersive_mode_enabled"
        const val KEY_USE_IMPERIAL = "use_imperial"
        const val KEY_SCREEN_SLEEP_ENABLED = "screen_sleep_enabled"
        const val KEY_SCREEN_SLEEP_TIMEOUT_MINUTES = "screen_sleep_timeout_minutes"
        const val DEFAULT_SCREEN_SLEEP_MINUTES = 10
    }
}
