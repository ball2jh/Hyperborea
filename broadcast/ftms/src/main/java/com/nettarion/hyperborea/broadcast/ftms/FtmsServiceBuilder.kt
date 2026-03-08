package com.nettarion.hyperborea.broadcast.ftms

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.ftms.FtmsDataEncoder
import com.nettarion.hyperborea.core.ftms.FtmsServiceMetadata
import java.util.UUID

object FtmsServiceBuilder {

    // Standard BLE base UUID: 0000xxxx-0000-1000-8000-00805F9B34FB
    private fun bleUuid(shortUuid: Int): UUID =
        UUID.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", shortUuid))

    // Service UUIDs
    val FTMS_SERVICE_UUID: UUID = bleUuid(FtmsServiceMetadata.FTMS_SERVICE)
    val CPS_SERVICE_UUID: UUID = bleUuid(FtmsServiceMetadata.CPS_SERVICE)

    // FTMS characteristic UUIDs
    val FTMS_FEATURE_UUID: UUID = bleUuid(FtmsServiceMetadata.FTMS_FEATURE)
    val SUPPORTED_RESISTANCE_UUID: UUID = bleUuid(FtmsServiceMetadata.SUPPORTED_RESISTANCE)
    val SUPPORTED_INCLINATION_UUID: UUID = bleUuid(FtmsServiceMetadata.SUPPORTED_INCLINATION)
    val SUPPORTED_POWER_UUID: UUID = bleUuid(FtmsServiceMetadata.SUPPORTED_POWER)
    val FTMS_CONTROL_POINT_UUID: UUID = bleUuid(FtmsServiceMetadata.FTMS_CONTROL_POINT)
    val TRAINING_STATUS_UUID: UUID = bleUuid(FtmsServiceMetadata.TRAINING_STATUS)
    val FITNESS_MACHINE_STATUS_UUID: UUID = bleUuid(FtmsServiceMetadata.FITNESS_MACHINE_STATUS)

    // CPS characteristic UUIDs
    val CPS_FEATURE_UUID: UUID = bleUuid(FtmsServiceMetadata.CPS_FEATURE)
    val SENSOR_LOCATION_UUID: UUID = bleUuid(FtmsServiceMetadata.SENSOR_LOCATION)
    val CPS_MEASUREMENT_UUID: UUID = bleUuid(FtmsServiceMetadata.CPS_MEASUREMENT)

    // CCCD
    val CCCD_UUID: UUID = bleUuid(FtmsServiceMetadata.CCCD)

    // Default CCCD value (notifications/indications disabled)
    val CCCD_DISABLED = byteArrayOf(0x00, 0x00)

    fun dataCharacteristicUuid(deviceType: DeviceType): UUID =
        bleUuid(FtmsDataEncoder.dataCharacteristicShortUuid(deviceType))

    fun ftmsFeatureValue(deviceType: DeviceType): ByteArray =
        FtmsServiceMetadata.ftmsFeatureValue(deviceType)

    fun serviceDataAdValue(deviceType: DeviceType): ByteArray =
        FtmsServiceMetadata.serviceDataAdValue(deviceType)

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
        FtmsServiceMetadata.resistanceRangeValue(info)

    fun inclinationRangeValue(info: DeviceInfo): ByteArray =
        FtmsServiceMetadata.inclinationRangeValue(info)

    fun powerRangeValue(info: DeviceInfo): ByteArray =
        FtmsServiceMetadata.powerRangeValue(info)

    fun staticValueFor(charUuid: UUID, deviceInfo: DeviceInfo): ByteArray? = when (charUuid) {
        FTMS_FEATURE_UUID -> FtmsServiceMetadata.ftmsFeatureValue(deviceInfo.type)
        SUPPORTED_RESISTANCE_UUID -> FtmsServiceMetadata.resistanceRangeValue(deviceInfo)
        SUPPORTED_INCLINATION_UUID -> FtmsServiceMetadata.inclinationRangeValue(deviceInfo)
        SUPPORTED_POWER_UUID -> FtmsServiceMetadata.powerRangeValue(deviceInfo)
        TRAINING_STATUS_UUID -> FtmsServiceMetadata.TRAINING_STATUS_VALUE.copyOf()
        CPS_FEATURE_UUID -> FtmsServiceMetadata.CPS_FEATURE_VALUE.copyOf()
        SENSOR_LOCATION_UUID -> FtmsServiceMetadata.SENSOR_LOCATION_VALUE.copyOf()
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
