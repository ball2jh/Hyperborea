package com.nettarion.hyperborea.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.Metric
import com.nettarion.hyperborea.core.profile.DeviceConfigRepository
import com.nettarion.hyperborea.hardware.fitpro.session.DeviceDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Editable form state for the device-configuration screen. Numeric fields are held as raw
 * strings so partially-typed values ("-", "1.") survive recomposition; they're parsed on save.
 */
data class DeviceConfigUiState(
    val name: String = "",
    val type: DeviceType = DeviceType.BIKE,
    val supportedMetrics: Set<Metric> = emptySet(),
    val maxResistance: String = "",
    val minResistance: String = "",
    val maxIncline: String = "",
    val minIncline: String = "",
    val maxPower: String = "",
    val minPower: String = "",
    val resistanceStep: String = "",
    val inclineStep: String = "",
    val speedStep: String = "",
    val powerStep: String = "",
    val maxSpeed: String = "",
    val isCustom: Boolean = false,
)

@HiltViewModel
class DeviceConfigViewModel @Inject constructor(
    private val deviceConfigRepository: DeviceConfigRepository,
    private val hardwareAdapter: HardwareAdapter,
    private val logger: AppLogger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceConfigUiState())
    val uiState: StateFlow<DeviceConfigUiState> = _uiState.asStateFlow()

    private var currentConfigKey: Int? = null

    fun load(configKey: Int?) {
        currentConfigKey = configKey
        viewModelScope.launch {
            val custom = configKey?.let { deviceConfigRepository.getConfig(it) }
            // Defaults preference: the user's saved config, else the live adapter info (carries
            // the session-detected type and MCU-reported bounds — model-less consoles have no
            // catalog entry to fall back to), else the catalog for a real model number.
            val info = custom
                ?: hardwareAdapter.deviceInfo.value
                ?: configKey?.takeIf { it > 0 }?.let { DeviceDatabase.fromModel(it) }
                ?: DeviceInfo.DEFAULT_INDOOR_BIKE
            _uiState.value = info.toUiState(isCustom = custom != null)
        }
    }

    fun setName(value: String) = _uiState.update { it.copy(name = value) }
    fun setType(value: DeviceType) = _uiState.update { it.copy(type = value) }

    fun toggleMetric(metric: Metric) = _uiState.update {
        val metrics = if (metric in it.supportedMetrics) {
            it.supportedMetrics - metric
        } else {
            it.supportedMetrics + metric
        }
        it.copy(supportedMetrics = metrics)
    }

    fun setMaxResistance(value: String) = _uiState.update { it.copy(maxResistance = value) }
    fun setMinResistance(value: String) = _uiState.update { it.copy(minResistance = value) }
    fun setMaxIncline(value: String) = _uiState.update { it.copy(maxIncline = value) }
    fun setMinIncline(value: String) = _uiState.update { it.copy(minIncline = value) }
    fun setMaxPower(value: String) = _uiState.update { it.copy(maxPower = value) }
    fun setMinPower(value: String) = _uiState.update { it.copy(minPower = value) }
    fun setResistanceStep(value: String) = _uiState.update { it.copy(resistanceStep = value) }
    fun setInclineStep(value: String) = _uiState.update { it.copy(inclineStep = value) }
    fun setSpeedStep(value: String) = _uiState.update { it.copy(speedStep = value) }
    fun setPowerStep(value: String) = _uiState.update { it.copy(powerStep = value) }
    fun setMaxSpeed(value: String) = _uiState.update { it.copy(maxSpeed = value) }

    fun save(onSaved: () -> Unit) {
        val key = currentConfigKey
        if (key == null) {
            // No equipment has been identified yet, so there's nothing stable to attach the
            // config to. Surfacing this beats silently dropping the user's edits.
            logger.w(TAG, "No device config key (equipment never connected) — config not saved")
            return
        }
        val info = _uiState.value.toDeviceInfo()
        viewModelScope.launch {
            deviceConfigRepository.saveConfig(key, info)
            hardwareAdapter.refreshDeviceInfo()
            _uiState.update { it.copy(isCustom = true) }
            logger.i(TAG, "Saved config for key $key")
            onSaved()
        }
    }

    fun resetToDefaults() {
        val key = currentConfigKey ?: return
        viewModelScope.launch {
            deviceConfigRepository.deleteConfig(key)
            // Re-resolve first so the adapter (and our fallback below) reflect the deletion.
            hardwareAdapter.refreshDeviceInfo()
            val defaults = hardwareAdapter.deviceInfo.value
                ?: key.takeIf { it > 0 }?.let { DeviceDatabase.fromModel(it) }
                ?: DeviceInfo.DEFAULT_INDOOR_BIKE
            _uiState.value = defaults.toUiState(isCustom = false)
            logger.i(TAG, "Reset config for key $key to defaults")
        }
    }

    private fun DeviceInfo.toUiState(isCustom: Boolean) = DeviceConfigUiState(
        name = name,
        type = type,
        supportedMetrics = supportedMetrics,
        maxResistance = maxResistance.toString(),
        minResistance = minResistance.toString(),
        maxIncline = maxIncline.toString(),
        minIncline = minIncline.toString(),
        maxPower = maxPower.toString(),
        minPower = minPower.toString(),
        resistanceStep = resistanceStep.toString(),
        inclineStep = inclineStep.toString(),
        speedStep = speedStep.toString(),
        powerStep = powerStep.toString(),
        maxSpeed = maxSpeed.toString(),
        isCustom = isCustom,
    )

    private fun DeviceConfigUiState.toDeviceInfo() = DeviceInfo(
        name = name,
        type = type,
        supportedMetrics = supportedMetrics,
        maxResistance = maxResistance.toIntOrNull() ?: 0,
        minResistance = minResistance.toIntOrNull() ?: 0,
        minIncline = minIncline.toFloatOrNull() ?: 0f,
        maxIncline = maxIncline.toFloatOrNull() ?: 0f,
        maxPower = maxPower.toIntOrNull() ?: 0,
        minPower = minPower.toIntOrNull() ?: 0,
        powerStep = powerStep.toIntOrNull() ?: 1,
        resistanceStep = resistanceStep.toFloatOrNull() ?: 1f,
        inclineStep = inclineStep.toFloatOrNull() ?: 0.5f,
        speedStep = speedStep.toFloatOrNull() ?: 0.5f,
        maxSpeed = maxSpeed.toFloatOrNull() ?: 0f,
    )

    private companion object {
        const val TAG = "DeviceConfigViewModel"
    }
}
