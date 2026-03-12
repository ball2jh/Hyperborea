package com.nettarion.hyperborea.sensor.hrm

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.SensorReading
import java.util.UUID

internal class HrmGattCallback(
    private val logger: AppLogger,
    private val onConnectionStateChange: (connected: Boolean) -> Unit,
    private val onHeartRate: (SensorReading.HeartRate) -> Unit,
) : BluetoothGattCallback() {

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            logger.i(TAG, "GATT connected, discovering services")
            gatt.discoverServices()
            onConnectionStateChange(true)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            logger.i(TAG, "GATT disconnected (status=$status)")
            onConnectionStateChange(false)
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            logger.e(TAG, "Service discovery failed: status=$status")
            return
        }

        val hrService = gatt.getService(HR_SERVICE_UUID)
        if (hrService == null) {
            logger.e(TAG, "Heart Rate service not found on device")
            return
        }

        val hrMeasurement = hrService.getCharacteristic(HR_MEASUREMENT_UUID)
        if (hrMeasurement == null) {
            logger.e(TAG, "Heart Rate Measurement characteristic not found")
            return
        }

        gatt.setCharacteristicNotification(hrMeasurement, true)
        val descriptor = hrMeasurement.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
            logger.i(TAG, "Subscribed to HR Measurement notifications")
        }
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (characteristic.uuid != HR_MEASUREMENT_UUID) return
        val value = characteristic.value ?: return
        if (value.isEmpty()) return

        val bpm = parseHeartRate(value)
        if (bpm > 0) {
            onHeartRate(SensorReading.HeartRate(bpm))
        }
    }

    companion object {
        private const val TAG = "HrmGatt"

        val HR_SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun parseHeartRate(value: ByteArray): Int {
            if (value.isEmpty()) return 0
            val flags = value[0].toInt() and 0xFF
            val isUint16 = (flags and 0x01) != 0
            return if (isUint16 && value.size >= 3) {
                (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
            } else if (value.size >= 2) {
                value[1].toInt() and 0xFF
            } else {
                0
            }
        }
    }
}
