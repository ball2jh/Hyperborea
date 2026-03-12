package com.nettarion.hyperborea.data

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProfileUserPreferences(
    private val profileRepository: ProfileRepository,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) : UserPreferences {

    override val enabledBroadcasts: StateFlow<Set<BroadcastId>> =
        profileRepository.activeProfile
            .map { it?.enabledBroadcasts ?: BroadcastId.entries.toSet() }
            .stateIn(scope, SharingStarted.Eagerly, BroadcastId.entries.toSet())

    override val overlayEnabled: StateFlow<Boolean> =
        profileRepository.activeProfile
            .map { it?.overlayEnabled ?: false }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val savedSensorAddress: StateFlow<String?> =
        profileRepository.activeProfile
            .map { it?.savedSensorAddress }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val toggleMutex = Mutex()

    override fun setBroadcastEnabled(id: BroadcastId, enabled: Boolean) {
        scope.launch {
            toggleMutex.withLock {
                val profile = profileRepository.activeProfile.value ?: return@withLock
                logger.i(TAG, "Broadcast ${id.name} ${if (enabled) "enabled" else "disabled"}")
                val current = profile.enabledBroadcasts.toMutableSet()
                if (enabled) current.add(id) else current.remove(id)
                val updated = profile.copy(enabledBroadcasts = current)
                profileRepository.updateProfile(updated)
            }
        }
    }

    override fun setOverlayEnabled(enabled: Boolean) {
        scope.launch {
            toggleMutex.withLock {
                val profile = profileRepository.activeProfile.value ?: return@withLock
                logger.i(TAG, "Overlay ${if (enabled) "enabled" else "disabled"}")
                profileRepository.updateProfile(profile.copy(overlayEnabled = enabled))
            }
        }
    }

    override fun setSavedSensorAddress(address: String?) {
        scope.launch {
            toggleMutex.withLock {
                val profile = profileRepository.activeProfile.value ?: return@withLock
                logger.i(TAG, "Sensor address ${address ?: "cleared"}")
                profileRepository.updateProfile(profile.copy(savedSensorAddress = address))
            }
        }
    }

    private companion object {
        const val TAG = "UserPreferences"
    }
}
