package com.nettarion.hyperborea.broadcast.ftms

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import java.util.UUID

object FtmsServiceBuilder {

    // Standard BLE base UUID: 0000xxxx-0000-1000-8000-00805F9B34FB
    private fun bleUuid(shortUuid: Int): UUID =
        UUID.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", shortUuid))

    // Service UUIDs
    val FTMS_SERVICE_UUID: UUID = bleUuid(0x1826)
    val CPS_SERVICE_UUID: UUID = bleUuid(0x1818)

    // FTMS characteristic UUIDs
    val FTMS_FEATURE_UUID: UUID = bleUuid(0x2ACC)
    val SUPPORTED_RESISTANCE_UUID: UUID = bleUuid(0x2AD6)
    val FTMS_CONTROL_POINT_UUID: UUID = bleUuid(0x2AD9)
    val INDOOR_BIKE_DATA_UUID: UUID = bleUuid(0x2AD2)
    val TRAINING_STATUS_UUID: UUID = bleUuid(0x2AD3)

    // CPS characteristic UUIDs
    val CPS_FEATURE_UUID: UUID = bleUuid(0x2A65)
    val SENSOR_LOCATION_UUID: UUID = bleUuid(0x2A5D)
    val CPS_MEASUREMENT_UUID: UUID = bleUuid(0x2A63)

    // CCCD
    val CCCD_UUID: UUID = bleUuid(0x2902)

    // Static read values
    val FTMS_FEATURE_VALUE = byteArrayOf(
        0x83.toByte(), 0x14, 0x00, 0x00, 0x0C, 0xE0.toByte(), 0x00, 0x00,
    )
    val RESISTANCE_RANGE_VALUE = byteArrayOf(0x0A, 0x00, 0x96.toByte(), 0x00, 0x0A, 0x00)
    val TRAINING_STATUS_VALUE = byteArrayOf(0x00, 0x01)
    val CPS_FEATURE_VALUE = byteArrayOf(0x0C, 0x00, 0x00, 0x00)
    val SENSOR_LOCATION_VALUE = byteArrayOf(0x0D)

    // Default CCCD value (notifications/indications disabled)
    val CCCD_DISABLED = byteArrayOf(0x00, 0x00)

    fun buildFtmsService(): BluetoothGattService {
        val service = BluetoothGattService(
            FTMS_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        // FTMS Feature — READ
        service.addCharacteristic(readCharacteristic(FTMS_FEATURE_UUID))

        // Supported Resistance Range — READ
        service.addCharacteristic(readCharacteristic(SUPPORTED_RESISTANCE_UUID))

        // FTMS Control Point — WRITE | INDICATE
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                FTMS_CONTROL_POINT_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ).apply {
                addDescriptor(cccdDescriptor())
            },
        )

        // Indoor Bike Data — NOTIFY
        service.addCharacteristic(notifyCharacteristic(INDOOR_BIKE_DATA_UUID))

        // Training Status — READ
        service.addCharacteristic(readCharacteristic(TRAINING_STATUS_UUID))

        return service
    }

    fun buildCpsService(): BluetoothGattService {
        val service = BluetoothGattService(
            CPS_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        // CPS Feature — READ
        service.addCharacteristic(readCharacteristic(CPS_FEATURE_UUID))

        // Sensor Location — READ
        service.addCharacteristic(readCharacteristic(SENSOR_LOCATION_UUID))

        // CPS Measurement — NOTIFY
        service.addCharacteristic(notifyCharacteristic(CPS_MEASUREMENT_UUID))

        return service
    }

    fun staticValueFor(charUuid: UUID): ByteArray? = when (charUuid) {
        FTMS_FEATURE_UUID -> FTMS_FEATURE_VALUE.copyOf()
        SUPPORTED_RESISTANCE_UUID -> RESISTANCE_RANGE_VALUE.copyOf()
        TRAINING_STATUS_UUID -> TRAINING_STATUS_VALUE.copyOf()
        CPS_FEATURE_UUID -> CPS_FEATURE_VALUE.copyOf()
        SENSOR_LOCATION_UUID -> SENSOR_LOCATION_VALUE.copyOf()
        else -> null
    }

    private fun readCharacteristic(uuid: UUID) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ,
    )

    private fun notifyCharacteristic(uuid: UUID) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        0, // No permissions needed for notify-only characteristics
    ).apply {
        addDescriptor(cccdDescriptor())
    }

    private fun cccdDescriptor() = BluetoothGattDescriptor(
        CCCD_UUID,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
    )
}
