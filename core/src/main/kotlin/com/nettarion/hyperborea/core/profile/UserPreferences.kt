package com.nettarion.hyperborea.core.profile

import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.model.FanMode

import kotlinx.coroutines.flow.StateFlow

interface UserPreferences {
    val enabledBroadcasts: StateFlow<Set<BroadcastId>>
    val overlayEnabled: StateFlow<Boolean>
    val savedSensorAddress: StateFlow<String?>
    val fanMode: StateFlow<FanMode>
    val immersiveModeEnabled: StateFlow<Boolean>
    /** `true` = imperial (mph, mi, lbs, ft/in), `false` = metric. Global, applies to guests too. */
    val useImperial: StateFlow<Boolean>
    /**
     * When `true`, the platform layer lets the console screen turn off after
     * [screenSleepTimeoutMinutes] of inactivity (the display is held on during an active
     * workout regardless). When `false` the screen never sleeps — the field default.
     */
    val screenSleepEnabled: StateFlow<Boolean>
    /** Minutes of inactivity before the screen is allowed to sleep when [screenSleepEnabled]. */
    val screenSleepTimeoutMinutes: StateFlow<Int>
    fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean)
    fun setOverlayEnabled(enabled: Boolean)
    fun setSavedSensorAddress(address: String?)
    fun setFanMode(mode: FanMode)
    fun setImmersiveModeEnabled(enabled: Boolean)
    fun setUseImperial(enabled: Boolean)
    fun setScreenSleepEnabled(enabled: Boolean)
    fun setScreenSleepTimeoutMinutes(minutes: Int)
}
