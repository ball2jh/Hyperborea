package com.nettarion.hyperborea.platform

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.BroadcastId
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.core.profile.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileUserPreferences(
    private val profileRepository: ProfileRepository,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) : UserPreferences {

    override val enabledBroadcasts: StateFlow<Set<BroadcastId>> =
        profileRepository.activeProfile
            .map { it?.enabledBroadcasts ?: BroadcastId.entries.toSet() }
            .stateIn(scope, SharingStarted.Eagerly, BroadcastId.entries.toSet())

    override fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean) {
        val profile = profileRepository.activeProfile.value ?: return
        logger.i(TAG, "Broadcast ${id.name} ${if (enabled) "enabled" else "disabled"}")
        val current = profile.enabledBroadcasts.toMutableSet()
        if (enabled) current.add(id) else current.remove(id)
        val updated = profile.copy(enabledBroadcasts = current)
        scope.launch {
            profileRepository.updateProfile(updated)
        }
    }

    private companion object {
        const val TAG = "UserPreferences"
    }
}
