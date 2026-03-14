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
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceConfigViewModel @Inject constructor(
    private val deviceConfigRepository: DeviceConfigRepository,
    private val hardwareAdapter: HardwareAdapter,
    private val logger: AppLogger,
) : ViewModel() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _type = MutableStateFlow(DeviceType.BIKE)
    val type: StateFlow<DeviceType> = _type.asStateFlow()

    private val _supportedMetrics = MutableStateFlow(emptySet<Metric>())
    val supportedMetrics: StateFlow<Set<Metric>> = _supportedMetrics.asStateFlow()

    private val _maxResistance = MutableStateFlow("")
    val maxResistance: StateFlow<String> = _maxResistance.asStateFlow()

    private val _minResistance = MutableStateFlow("")
    val minResistance: StateFlow<String> = _minResistance.asStateFlow()

    private val _maxIncline = MutableStateFlow("")
    val maxIncline: StateFlow<String> = _maxIncline.asStateFlow()

    private val _minIncline = MutableStateFlow("")
    val minIncline: StateFlow<String> = _minIncline.asStateFlow()

    private val _maxPower = MutableStateFlow("")
    val maxPower: StateFlow<String> = _maxPower.asStateFlow()

    private val _minPower = MutableStateFlow("")
    val minPower: StateFlow<String> = _minPower.asStateFlow()

    private val _resistanceStep = MutableStateFlow("")
    val resistanceStep: StateFlow<String> = _resistanceStep.asStateFlow()

    private val _inclineStep = MutableStateFlow("")
    val inclineStep: StateFlow<String> = _inclineStep.asStateFlow()

    private val _speedStep = MutableStateFlow("")
    val speedStep: StateFlow<String> = _speedStep.asStateFlow()

    private val _powerStep = MutableStateFlow("")
    val powerStep: StateFlow<String> = _powerStep.asStateFlow()

    private val _maxSpeed = MutableStateFlow("")
    val maxSpeed: StateFlow<String> = _maxSpeed.asStateFlow()

    private val _isCustom = MutableStateFlow(false)
    val isCustom: StateFlow<Boolean> = _isCustom.asStateFlow()

    private var currentModelNumber: Int? = null

    fun load(modelNumber: Int?) {
        currentModelNumber = modelNumber
        if (modelNumber == null) {
            populateFrom(DeviceInfo.DEFAULT_INDOOR_BIKE)
            return
        }
        viewModelScope.launch {
            val custom = deviceConfigRepository.getConfig(modelNumber)
            _isCustom.value = custom != null
            val info = custom ?: DeviceDatabase.fromModel(modelNumber)
            populateFrom(info)
        }
    }

    private fun populateFrom(info: DeviceInfo) {
        _name.value = info.name
        _type.value = info.type
        _supportedMetrics.value = info.supportedMetrics
        _maxResistance.value = info.maxResistance.toString()
        _minResistance.value = info.minResistance.toString()
        _maxIncline.value = info.maxIncline.toString()
        _minIncline.value = info.minIncline.toString()
        _maxPower.value = info.maxPower.toString()
        _minPower.value = info.minPower.toString()
        _resistanceStep.value = info.resistanceStep.toString()
        _inclineStep.value = info.inclineStep.toString()
        _speedStep.value = info.speedStep.toString()
        _powerStep.value = info.powerStep.toString()
        _maxSpeed.value = info.maxSpeed.toString()
    }

    fun setName(value: String) { _name.value = value }
    fun setType(value: DeviceType) { _type.value = value }

    fun toggleMetric(metric: Metric) {
        _supportedMetrics.value = if (metric in _supportedMetrics.value) {
            _supportedMetrics.value - metric
        } else {
            _supportedMetrics.value + metric
        }
    }

    fun setMaxResistance(value: String) { _maxResistance.value = value }
    fun setMinResistance(value: String) { _minResistance.value = value }
    fun setMaxIncline(value: String) { _maxIncline.value = value }
    fun setMinIncline(value: String) { _minIncline.value = value }
    fun setMaxPower(value: String) { _maxPower.value = value }
    fun setMinPower(value: String) { _minPower.value = value }
    fun setResistanceStep(value: String) { _resistanceStep.value = value }
    fun setInclineStep(value: String) { _inclineStep.value = value }
    fun setSpeedStep(value: String) { _speedStep.value = value }
    fun setPowerStep(value: String) { _powerStep.value = value }
    fun setMaxSpeed(value: String) { _maxSpeed.value = value }

    fun save(onSaved: () -> Unit) {
        val model = currentModelNumber ?: return
        val info = DeviceInfo(
            name = _name.value,
            type = _type.value,
            supportedMetrics = _supportedMetrics.value,
            maxResistance = _maxResistance.value.toIntOrNull() ?: 0,
            minResistance = _minResistance.value.toIntOrNull() ?: 0,
            minIncline = _minIncline.value.toFloatOrNull() ?: 0f,
            maxIncline = _maxIncline.value.toFloatOrNull() ?: 0f,
            maxPower = _maxPower.value.toIntOrNull() ?: 0,
            minPower = _minPower.value.toIntOrNull() ?: 0,
            powerStep = _powerStep.value.toIntOrNull() ?: 1,
            resistanceStep = _resistanceStep.value.toFloatOrNull() ?: 1f,
            inclineStep = _inclineStep.value.toFloatOrNull() ?: 0.5f,
            speedStep = _speedStep.value.toFloatOrNull() ?: 0.5f,
            maxSpeed = _maxSpeed.value.toFloatOrNull() ?: 0f,
        )
        viewModelScope.launch {
            deviceConfigRepository.saveConfig(model, info)
            hardwareAdapter.refreshDeviceInfo()
            _isCustom.value = true
            logger.i(TAG, "Saved config for model $model")
            onSaved()
        }
    }

    fun resetToDefaults() {
        val model = currentModelNumber ?: return
        viewModelScope.launch {
            deviceConfigRepository.deleteConfig(model)
            val defaults = DeviceDatabase.fromModel(model)
            populateFrom(defaults)
            hardwareAdapter.refreshDeviceInfo()
            _isCustom.value = false
            logger.i(TAG, "Reset config for model $model to defaults")
        }
    }

    private companion object {
        const val TAG = "DeviceConfigViewModel"
    }
}
