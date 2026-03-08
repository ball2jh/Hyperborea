package com.nettarion.hyperborea.broadcast.ftms

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.nettarion.hyperborea.core.ByteUtils.sint16LE
import com.nettarion.hyperborea.core.ByteUtils.uint16LE
import com.nettarion.hyperborea.core.DeviceInfo
import com.nettarion.hyperborea.core.DeviceType
import com.nettarion.hyperborea.core.FtmsDataEncoder
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
    val SUPPORTED_INCLINATION_UUID: UUID = bleUuid(0x2AD5)
    val SUPPORTED_POWER_UUID: UUID = bleUuid(0x2AD7)
    val FTMS_CONTROL_POINT_UUID: UUID = bleUuid(0x2AD9)
    val TRAINING_STATUS_UUID: UUID = bleUuid(0x2AD3)
    val FITNESS_MACHINE_STATUS_UUID: UUID = bleUuid(0x2ADA)

    // CPS characteristic UUIDs
    val CPS_FEATURE_UUID: UUID = bleUuid(0x2A65)
    val SENSOR_LOCATION_UUID: UUID = bleUuid(0x2A5D)
    val CPS_MEASUREMENT_UUID: UUID = bleUuid(0x2A63)

    // CCCD
    val CCCD_UUID: UUID = bleUuid(0x2902)

    // Static read values
    val TRAINING_STATUS_VALUE = byteArrayOf(0x00, 0x01)
    val CPS_FEATURE_VALUE = byteArrayOf(0x0C, 0x00, 0x00, 0x00)
    val SENSOR_LOCATION_VALUE = byteArrayOf(0x0D)

    // Default CCCD value (notifications/indications disabled)
    val CCCD_DISABLED = byteArrayOf(0x00, 0x00)

    fun dataCharacteristicUuid(deviceType: DeviceType): UUID =
        bleUuid(FtmsDataEncoder.dataCharacteristicShortUuid(deviceType))

    // Both BLE and WFTNP paths advertise the same feature set.
    fun ftmsFeatureValue(deviceType: DeviceType): ByteArray = when (deviceType) {
        DeviceType.BIKE -> byteArrayOf(
            0x8F.toByte(), 0x56, 0x00, 0x00, 0x0E, 0xE0.toByte(), 0x00, 0x00,
        )
        DeviceType.TREADMILL -> TODO("Treadmill FTMS feature value")
        DeviceType.ROWER -> TODO("Rower FTMS feature value")
        DeviceType.ELLIPTICAL -> TODO("Cross Trainer FTMS feature value")
    }

    // Service Data AD Type (Section 3.1): Flags + Fitness Machine Type
    // Flags: bit 0 = Fitness Machine Available (1 = true)
    // Machine Type bits: 0=Treadmill, 1=Cross Trainer, 4=Rower, 5=Indoor Bike
    fun serviceDataAdValue(deviceType: DeviceType): ByteArray = when (deviceType) {
        DeviceType.BIKE -> byteArrayOf(0x01, 0x20, 0x00)       // bit 5
        DeviceType.TREADMILL -> byteArrayOf(0x01, 0x01, 0x00)  // bit 0
        DeviceType.ROWER -> byteArrayOf(0x01, 0x10, 0x00)      // bit 4
        DeviceType.ELLIPTICAL -> byteArrayOf(0x01, 0x02, 0x00) // bit 1
    }

    fun buildFtmsService(deviceType: DeviceType): BluetoothGattService {
        val service = BluetoothGattService(
            FTMS_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        // FTMS Feature — READ
        service.addCharacteristic(readCharacteristic(FTMS_FEATURE_UUID))

        // Supported Resistance Range — READ
        service.addCharacteristic(readCharacteristic(SUPPORTED_RESISTANCE_UUID))

        // Supported Inclination Range — READ
        service.addCharacteristic(readCharacteristic(SUPPORTED_INCLINATION_UUID))

        // Supported Power Range — READ
        service.addCharacteristic(readCharacteristic(SUPPORTED_POWER_UUID))

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

        // Data characteristic (device-type-specific) — NOTIFY
        service.addCharacteristic(notifyCharacteristic(dataCharacteristicUuid(deviceType)))

        // Training Status — READ + NOTIFY
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                TRAINING_STATUS_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ).apply {
                addDescriptor(cccdDescriptor())
            },
        )

        // Fitness Machine Status — NOTIFY
        service.addCharacteristic(notifyCharacteristic(FITNESS_MACHINE_STATUS_UUID))

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

    fun resistanceRangeValue(info: DeviceInfo): ByteArray =
        sint16LE(info.minResistance * 10) + sint16LE(info.maxResistance * 10) + uint16LE(10)

    fun inclinationRangeValue(info: DeviceInfo): ByteArray =
        sint16LE((info.minIncline * 10).toInt()) + sint16LE((info.maxIncline * 10).toInt()) + uint16LE(5)

    fun powerRangeValue(info: DeviceInfo): ByteArray =
        sint16LE(0) + sint16LE(info.maxPower) + uint16LE(1)

    fun staticValueFor(charUuid: UUID, deviceInfo: DeviceInfo): ByteArray? = when (charUuid) {
        FTMS_FEATURE_UUID -> ftmsFeatureValue(deviceInfo.type)
        SUPPORTED_RESISTANCE_UUID -> resistanceRangeValue(deviceInfo)
        SUPPORTED_INCLINATION_UUID -> inclinationRangeValue(deviceInfo)
        SUPPORTED_POWER_UUID -> powerRangeValue(deviceInfo)
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
