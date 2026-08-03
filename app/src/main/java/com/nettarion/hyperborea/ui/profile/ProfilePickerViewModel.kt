package com.nettarion.hyperborea.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.core.profile.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilePickerViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    hardwareAdapter: HardwareAdapter,
    userPreferences: UserPreferences,
) : ViewModel() {

    val profiles: StateFlow<List<Profile>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Equipment type for device-aware copy: the live identification when the probe has run,
     * else the persisted type from the last successful handshake, else null (generic copy).
     */
    val deviceType: StateFlow<DeviceType?> = combine(
        hardwareAdapter.deviceInfo,
        userPreferences.lastKnownDeviceType,
    ) { live, lastKnown -> live?.type ?: lastKnown }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            hardwareAdapter.deviceInfo.value?.type ?: userPreferences.lastKnownDeviceType.value,
        )

    fun selectProfile(id: Long, onSelected: () -> Unit) {
        viewModelScope.launch {
            profileRepository.setActiveProfile(id)
            onSelected()
        }
    }
}
