package com.nettarion.hyperborea.core

import kotlinx.coroutines.flow.StateFlow

interface HardwareAdapter : Adapter {
    val deviceInfo: StateFlow<DeviceInfo?>
    val exerciseData: StateFlow<ExerciseData?>
    val deviceIdentity: StateFlow<DeviceIdentity?>

    /**
     * Establish a connection to the hardware device.
     *
     * - If the adapter is already [AdapterState.Active] or [AdapterState.Activating], this is a no-op.
     * - On success, transitions state through [AdapterState.Activating] to [AdapterState.Active].
     * - On failure, transitions to [AdapterState.Error] with the exception detail.
     * - A disconnected adapter can be reconnected by calling [connect] again.
     */
    suspend fun connect()

    /**
     * Disconnect from the hardware device and release resources.
     *
     * - If the adapter is already [AdapterState.Inactive], this is a no-op.
     * - Clears [exerciseData] to null.
     * - Transitions state to [AdapterState.Inactive].
     */
    suspend fun disconnect()

    suspend fun sendCommand(command: DeviceCommand)
}
