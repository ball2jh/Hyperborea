package com.nettarion.hyperborea.ui.sensor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.DiscoveredSensor
import com.nettarion.hyperborea.core.adapter.SensorAdapter
import com.nettarion.hyperborea.core.adapter.SensorReading
import com.nettarion.hyperborea.core.profile.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SensorViewModel @Inject constructor(
    private val sensorAdapter: SensorAdapter,
    private val userPreferences: UserPreferences,
    private val logger: AppLogger,
) : ViewModel() {

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredSensor>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredSensor>> = _discoveredDevices.asStateFlow()

    val sensorState: StateFlow<AdapterState> = sensorAdapter.state

    val savedAddress: StateFlow<String?> = userPreferences.savedSensorAddress

    val heartRate: StateFlow<Int?> = sensorAdapter.reading
        .combine(MutableStateFlow(Unit)) { reading, _ ->
            (reading as? SensorReading.HeartRate)?.bpm
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var scanJob: Job? = null

    fun startScan() {
        if (_scanning.value) return
        _scanning.value = true
        _discoveredDevices.value = emptyList()

        scanJob = viewModelScope.launch {
            try {
                sensorAdapter.startScan().collect { sensor ->
                    val current = _discoveredDevices.value
                    if (current.none { it.address == sensor.address }) {
                        _discoveredDevices.value = current + sensor
                    }
                }
            } catch (e: Exception) {
                logger.w(TAG, "Scan ended: ${e.message}")
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    fun connectDevice(address: String) {
        stopScan()
        viewModelScope.launch {
            sensorAdapter.connect(address)
            userPreferences.setSavedSensorAddress(address)
        }
    }

    fun forgetDevice() {
        viewModelScope.launch {
            sensorAdapter.disconnect()
            userPreferences.setSavedSensorAddress(null)
        }
    }

    override fun onCleared() {
        stopScan()
    }

    private companion object {
        const val TAG = "SensorVM"
    }
}
