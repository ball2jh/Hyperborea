package com.nettarion.hyperborea.core

import kotlinx.coroutines.flow.StateFlow

interface UserPreferences {
    val enabledBroadcasts: StateFlow<Set<BroadcastId>>
    fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean)
}
