package com.nettarion.hyperborea.broadcast.ftms

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.ControlPointParser
import com.nettarion.hyperborea.core.DeviceCommand
import com.nettarion.hyperborea.core.DeviceInfo
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FtmsGattCallback(
    private val context: Context,
    private val logger: AppLogger,
    private val deviceInfo: DeviceInfo,
    private val onClientConnected: (BluetoothDevice) -> Unit,
    private val onClientDisconnected: (BluetoothDevice) -> Unit,
    private val onCommand: (DeviceCommand) -> Unit,
    private val onServiceAdded: () -> Unit,
) : BluetoothGattServerCallback() {

    internal var gattServer: BluetoothGattServer? = null

    // CCCD subscription state per characteristic UUID
    private val cccdState = ConcurrentHashMap<UUID, ByteArray>()

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                logger.i(TAG, "Client connected: ${device.address}")
                onClientConnected(device)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                logger.i(TAG, "Client disconnected: ${device.address}")
                cccdState.clear()
                onClientDisconnected(device)
            }
        }
    }

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic,
    ) {
        val value = FtmsServiceBuilder.staticValueFor(characteristic.uuid, deviceInfo)
        if (value == null) {
            sendGattResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
            return
        }
        if (offset >= value.size) {
            sendGattResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
            return
        }
        val slice = value.copyOfRange(offset, value.size)
        sendGattResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
    ) {
        if (characteristic.uuid != FtmsServiceBuilder.FTMS_CONTROL_POINT_UUID || value == null) {
            if (responseNeeded) {
                sendGattResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null)
            }
            return
        }

        // Send GATT write response first (Zwift uses write-with-response)
        if (responseNeeded) {
            sendGattResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        // Parse the control point command
        val result = ControlPointParser.parseFtmsControlPoint(value)

        // Send CP indication response
        if (value.isNotEmpty()) {
            val resultCode = when (result) {
                is ControlPointParser.ControlPointResult.Unsupported -> ControlPointParser.RESULT_NOT_SUPPORTED
                else -> ControlPointParser.RESULT_SUCCESS
            }
            val cpResp = ControlPointParser.encodeResponse(value[0], resultCode)
            val cpChar = gattServer?.getService(FtmsServiceBuilder.FTMS_SERVICE_UUID)
                ?.getCharacteristic(FtmsServiceBuilder.FTMS_CONTROL_POINT_UUID)
            if (cpChar != null) {
                cpChar.value = cpResp
                notifyGattChange(device, cpChar, true) // confirm = true for indication
            }
        }

        // Emit device command
        if (result is ControlPointParser.ControlPointResult.DeviceCmd) {
            onCommand(result.command)
        }
    }

    override fun onDescriptorReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor,
    ) {
        if (descriptor.uuid == FtmsServiceBuilder.CCCD_UUID) {
            val charUuid = descriptor.characteristic.uuid
            val value = cccdState[charUuid] ?: FtmsServiceBuilder.CCCD_DISABLED
            sendGattResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        } else {
            sendGattResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
        }
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
    ) {
        if (descriptor.uuid == FtmsServiceBuilder.CCCD_UUID && value != null) {
            val charUuid = descriptor.characteristic.uuid
            cccdState[charUuid] = value.copyOf()
            logger.d(TAG, "CCCD write for ${charUuid}: ${value.joinToString { "%02X".format(it) }}")
            if (responseNeeded) {
                sendGattResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        } else {
            if (responseNeeded) {
                sendGattResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null)
            }
        }
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            logger.d(TAG, "Service added: ${service.uuid}")
        } else {
            logger.e(TAG, "Failed to add service ${service.uuid}, status=$status")
        }
        onServiceAdded.invoke()
    }

    fun isSubscribed(charUuid: UUID): Boolean {
        val cccd = cccdState[charUuid] ?: return false
        return cccd.size >= 2 && (cccd[0].toInt() != 0 || cccd[1].toInt() != 0)
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Checked by hasBluetoothPermission() above
    private fun sendGattResponse(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        if (!hasBluetoothPermission()) return
        gattServer?.sendResponse(device, requestId, status, offset, value)
    }

    @SuppressLint("MissingPermission") // Checked by hasBluetoothPermission() above
    @Suppress("DEPRECATION")
    private fun notifyGattChange(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        confirm: Boolean,
    ) {
        if (!hasBluetoothPermission()) return
        gattServer?.notifyCharacteristicChanged(device, characteristic, confirm)
    }

    private companion object {
        const val TAG = "FtmsGatt"
    }
}
