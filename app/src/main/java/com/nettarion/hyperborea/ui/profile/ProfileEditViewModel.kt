package com.nettarion.hyperborea.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.profile.ProfileRepository
import com.nettarion.hyperborea.core.profile.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import com.nettarion.hyperborea.ui.util.UnitFormatter
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Editable form state for the profile-edit screen. Body fields are held as raw strings in the
 * currently-displayed units ([UserPreferences.useImperial]); they're parsed to metric on save.
 * Metric height uses [height] (cm) alone; imperial splits into [height] (feet) + [heightInches].
 */
data class ProfileEditUiState(
    val name: String = "",
    val weight: String = "",
    val height: String = "",
    val heightInches: String = "",
    val age: String = "",
    val ftpWatts: String = "",
    val maxHeartRate: String = "",
)

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileEditUiState())
    val uiState: StateFlow<ProfileEditUiState> = _uiState.asStateFlow()

    /** Global units pref. The on-screen toggle writes through to [UserPreferences]. */
    val useImperial: StateFlow<Boolean> = userPreferences.useImperial

    private var editingProfile: Profile? = null

    fun loadProfile(profileId: Long?) {
        if (profileId == null || editingProfile != null) return
        viewModelScope.launch {
            val profiles = profileRepository.profiles.first()
            val profile = profiles.find { it.id == profileId } ?: return@launch
            editingProfile = profile
            _uiState.update {
                it.copy(
                    name = profile.name,
                    age = profile.age?.toString() ?: "",
                    ftpWatts = profile.ftpWatts?.toString() ?: "",
                    maxHeartRate = profile.maxHeartRate?.toString() ?: "",
                )
            }
            loadBodyFields(profile)
        }
    }

    private fun loadBodyFields(profile: Profile) {
        val imperial = useImperial.value
        val weight = profile.weightKg?.let { UnitFormatter.weightEditDisplay(it, imperial) } ?: ""
        val (height, heightInches) = profile.heightCm
            ?.let { UnitFormatter.heightEditFields(it, imperial) }
            ?: ("" to "")
        _uiState.update {
            it.copy(weight = weight, height = height, heightInches = heightInches)
        }
    }

    fun setName(value: String) = _uiState.update { it.copy(name = value) }
    fun setWeight(value: String) = _uiState.update { it.copy(weight = value) }
    fun setHeight(value: String) = _uiState.update { it.copy(height = value) }
    fun setHeightInches(value: String) = _uiState.update { it.copy(heightInches = value) }
    fun setAge(value: String) = _uiState.update { it.copy(age = value) }
    fun setFtpWatts(value: String) = _uiState.update { it.copy(ftpWatts = value) }
    fun setMaxHeartRate(value: String) = _uiState.update { it.copy(maxHeartRate = value) }

    fun toggleUnits() {
        // Capture current displayed values in metric *before* flipping the pref,
        // then reload the display in the new units.
        val weightKg = parseWeightToKg()
        val heightCm = parseHeightToCm()
        userPreferences.setUseImperial(!useImperial.value)
        val imperial = useImperial.value

        val weight = weightKg?.let { UnitFormatter.weightEditDisplay(it, imperial) } ?: ""
        val (height, heightInches) = heightCm
            ?.let { UnitFormatter.heightEditFields(it, imperial) }
            ?: ("" to "")
        _uiState.update {
            it.copy(weight = weight, height = height, heightInches = heightInches)
        }
    }

    private fun parseWeightToKg(): Float? {
        val v = _uiState.value.weight.toFloatOrNull() ?: return null
        return UnitFormatter.parseWeightToKg(v, useImperial.value)
    }

    private fun parseHeightToCm(): Int? {
        val state = _uiState.value
        return if (useImperial.value) {
            val feet = state.height.toIntOrNull() ?: 0
            val inches = state.heightInches.toIntOrNull() ?: 0
            if (feet == 0 && inches == 0) null
            else UnitFormatter.parseHeightToCm(feet, inches)
        } else {
            state.height.toIntOrNull()
        }
    }

    fun deleteProfile(onDeleted: () -> Unit) {
        val profile = editingProfile ?: return
        viewModelScope.launch {
            profileRepository.deleteProfile(profile.id)
            onDeleted()
        }
    }

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        val nameValue = state.name.trim()
        if (nameValue.isBlank()) return

        val weightKg = parseWeightToKg()
        val heightCm = parseHeightToCm()

        viewModelScope.launch {
            val existing = editingProfile
            if (existing != null) {
                profileRepository.updateProfile(
                    existing.copy(
                        name = nameValue,
                        weightKg = weightKg,
                        heightCm = heightCm,
                        age = state.age.toIntOrNull(),
                        ftpWatts = state.ftpWatts.toIntOrNull(),
                        maxHeartRate = state.maxHeartRate.toIntOrNull(),
                    ),
                )
            } else {
                val profile = profileRepository.createProfile(nameValue)
                profileRepository.updateProfile(
                    profile.copy(
                        weightKg = weightKg,
                        heightCm = heightCm,
                        age = state.age.toIntOrNull(),
                        ftpWatts = state.ftpWatts.toIntOrNull(),
                        maxHeartRate = state.maxHeartRate.toIntOrNull(),
                    ),
                )
                profileRepository.setActiveProfile(profile.id)
            }
            onSaved()
        }
    }
}
