package com.nettarion.hyperborea.core.profile

import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.model.FanMode

import kotlinx.coroutines.flow.StateFlow

interface UserPreferences {
    val enabledBroadcasts: StateFlow<Set<BroadcastId>>
    val overlayEnabled: StateFlow<Boolean>
    val savedSensorAddress: StateFlow<String?>
    val fanMode: StateFlow<FanMode>
    fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean)
    fun setOverlayEnabled(enabled: Boolean)
    fun setSavedSensorAddress(address: String?)
    fun setFanMode(mode: FanMode)
}
