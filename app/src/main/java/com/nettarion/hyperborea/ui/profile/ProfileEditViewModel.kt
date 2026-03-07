package com.nettarion.hyperborea.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.Profile
import com.nettarion.hyperborea.core.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _weight = MutableStateFlow("")
    val weight: StateFlow<String> = _weight.asStateFlow()

    // Metric: single height field (cm). Imperial: feet and inches.
    private val _height = MutableStateFlow("")
    val height: StateFlow<String> = _height.asStateFlow()

    private val _heightInches = MutableStateFlow("")
    val heightInches: StateFlow<String> = _heightInches.asStateFlow()

    private val _age = MutableStateFlow("")
    val age: StateFlow<String> = _age.asStateFlow()

    private val _ftpWatts = MutableStateFlow("")
    val ftpWatts: StateFlow<String> = _ftpWatts.asStateFlow()

    private val _maxHeartRate = MutableStateFlow("")
    val maxHeartRate: StateFlow<String> = _maxHeartRate.asStateFlow()

    private val _useImperial = MutableStateFlow(true)
    val useImperial: StateFlow<Boolean> = _useImperial.asStateFlow()

    private var editingProfile: Profile? = null

    fun loadProfile(profileId: Long?) {
        if (profileId == null || editingProfile != null) return
        viewModelScope.launch {
            val profiles = profileRepository.profiles.first()
            val profile = profiles.find { it.id == profileId } ?: return@launch
            editingProfile = profile
            _name.value = profile.name
            _useImperial.value = profile.useImperial
            _age.value = profile.age?.toString() ?: ""
            _ftpWatts.value = profile.ftpWatts?.toString() ?: ""
            _maxHeartRate.value = profile.maxHeartRate?.toString() ?: ""
            loadBodyFields(profile)
        }
    }

    private fun loadBodyFields(profile: Profile) {
        if (_useImperial.value) {
            _weight.value = profile.weightKg?.let { "%.1f".format(it * KG_TO_LBS) } ?: ""
            val totalInches = profile.heightCm?.let { (it / CM_PER_INCH).roundToInt() }
            if (totalInches != null) {
                _height.value = (totalInches / 12).toString()
                _heightInches.value = (totalInches % 12).toString()
            } else {
                _height.value = ""
                _heightInches.value = ""
            }
        } else {
            _weight.value = profile.weightKg?.toString() ?: ""
            _height.value = profile.heightCm?.toString() ?: ""
            _heightInches.value = ""
        }
    }

    fun setName(value: String) { _name.value = value }
    fun setWeight(value: String) { _weight.value = value }
    fun setHeight(value: String) { _height.value = value }
    fun setHeightInches(value: String) { _heightInches.value = value }
    fun setAge(value: String) { _age.value = value }
    fun setFtpWatts(value: String) { _ftpWatts.value = value }
    fun setMaxHeartRate(value: String) { _maxHeartRate.value = value }

    fun toggleUnits() {
        // Convert current display values to metric, flip, then reload display
        val weightKg = parseWeightToKg()
        val heightCm = parseHeightToCm()
        _useImperial.value = !_useImperial.value

        if (_useImperial.value) {
            _weight.value = weightKg?.let { "%.1f".format(it * KG_TO_LBS) } ?: ""
            val totalInches = heightCm?.let { (it / CM_PER_INCH).roundToInt() }
            if (totalInches != null) {
                _height.value = (totalInches / 12).toString()
                _heightInches.value = (totalInches % 12).toString()
            } else {
                _height.value = ""
                _heightInches.value = ""
            }
        } else {
            _weight.value = weightKg?.toString() ?: ""
            _height.value = heightCm?.toString() ?: ""
            _heightInches.value = ""
        }
    }

    private fun parseWeightToKg(): Float? {
        val v = _weight.value.toFloatOrNull() ?: return null
        return if (_useImperial.value) v / KG_TO_LBS else v
    }

    private fun parseHeightToCm(): Int? {
        return if (_useImperial.value) {
            val feet = _height.value.toIntOrNull() ?: 0
            val inches = _heightInches.value.toIntOrNull() ?: 0
            if (feet == 0 && inches == 0) null
            else ((feet * 12 + inches) * CM_PER_INCH).roundToInt()
        } else {
            _height.value.toIntOrNull()
        }
    }

    fun save(onSaved: () -> Unit) {
        val nameValue = _name.value.trim()
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
                        age = _age.value.toIntOrNull(),
                        ftpWatts = _ftpWatts.value.toIntOrNull(),
                        maxHeartRate = _maxHeartRate.value.toIntOrNull(),
                        useImperial = _useImperial.value,
                    ),
                )
            } else {
                val profile = profileRepository.createProfile(nameValue)
                profileRepository.updateProfile(
                    profile.copy(
                        weightKg = weightKg,
                        heightCm = heightCm,
                        age = _age.value.toIntOrNull(),
                        ftpWatts = _ftpWatts.value.toIntOrNull(),
                        maxHeartRate = _maxHeartRate.value.toIntOrNull(),
                        useImperial = _useImperial.value,
                    ),
                )
                profileRepository.setActiveProfile(profile.id)
            }
            onSaved()
        }
    }

    private companion object {
        const val KG_TO_LBS = 2.20462f
        const val CM_PER_INCH = 2.54f
    }
}
