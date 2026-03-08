package com.nettarion.hyperborea.core.profile

import com.nettarion.hyperborea.core.adapter.BroadcastId

import kotlinx.coroutines.flow.StateFlow

interface UserPreferences {
    val enabledBroadcasts: StateFlow<Set<BroadcastId>>
    fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean)
}
