package com.nettarion.hyperborea.core.adapter

import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.ExerciseData

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

    /**
     * Transient hardware identification: connect, perform handshake to get device
     * identity, then disconnect. Does not affect adapter state or exercise data.
     * Returns DeviceInfo derived from the handshake, or null on failure.
     */
    suspend fun identify(): DeviceInfo?

    suspend fun sendCommand(command: DeviceCommand)

    fun setInitialElapsedTime(seconds: Long)

    /**
     * Re-resolve device info from the current device identity.
     * Call after saving a custom device config so broadcasts/UI pick up the new values.
     */
    fun refreshDeviceInfo()
}
