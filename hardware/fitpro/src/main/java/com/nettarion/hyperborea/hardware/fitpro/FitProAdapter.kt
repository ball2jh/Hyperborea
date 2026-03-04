package com.nettarion.hyperborea.hardware.fitpro

import com.nettarion.hyperborea.core.AdapterState
import com.nettarion.hyperborea.core.DeviceCommand
import com.nettarion.hyperborea.core.DeviceInfo
import com.nettarion.hyperborea.core.DeviceType
import com.nettarion.hyperborea.core.ExerciseData
import com.nettarion.hyperborea.core.FulfillResult
import com.nettarion.hyperborea.core.HardwareAdapter
import com.nettarion.hyperborea.core.Metric
import com.nettarion.hyperborea.core.Prerequisite
import com.nettarion.hyperborea.core.SystemSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class FitProAdapter @Inject constructor() : HardwareAdapter {

    override val prerequisites = listOf(
        Prerequisite(
            id = "usb-device-accessible",
            description = "FitPro USB device must be accessible",
            isMet = { snapshot ->
                snapshot.usbDevices.any {
                    it.vendorId == FITPRO_VENDOR_ID && it.productId == FITPRO_PRODUCT_ID
                }
            },
            fulfill = { controller ->
                if (controller.grantUsbPermission(HYPERBOREA_PACKAGE)) FulfillResult.Success
                else FulfillResult.Failed("USB permission not granted")
            },
        ),
    )

    override fun canOperate(snapshot: SystemSnapshot): Boolean =
        snapshot.status.isUsbHostAvailable

    override val deviceInfo = DeviceInfo(
        name = "NordicTrack S22i",
        type = DeviceType.BIKE,
        supportedMetrics = setOf(
            Metric.POWER, Metric.CADENCE, Metric.SPEED,
            Metric.RESISTANCE, Metric.INCLINE,
            Metric.DISTANCE, Metric.CALORIES,
        ),
    )

    private val _exerciseData = MutableStateFlow<ExerciseData?>(null)
    override val exerciseData: StateFlow<ExerciseData?> = _exerciseData.asStateFlow()

    private val _state = MutableStateFlow<AdapterState>(AdapterState.Inactive)
    override val state: StateFlow<AdapterState> = _state.asStateFlow()

    override suspend fun connect() {
        if (_state.value is AdapterState.Active || _state.value is AdapterState.Activating) return
        _state.value = AdapterState.Activating
        // TODO: USB serial connection + FitPro init sequence
        _state.value = AdapterState.Active
    }

    override suspend fun disconnect() {
        if (_state.value is AdapterState.Inactive) return
        _state.value = AdapterState.Inactive
        _exerciseData.value = null
    }

    override suspend fun sendCommand(command: DeviceCommand) {
        // TODO: Encode FitPro protocol command and send over USB serial
    }

    private companion object {
        const val FITPRO_VENDOR_ID = 0x213C
        const val FITPRO_PRODUCT_ID = 2
        const val HYPERBOREA_PACKAGE = "com.nettarion.hyperborea"
    }
}
