package com.nettarion.hyperborea.broadcast.wftnp

import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.ftms.FtmsDataEncoder
import com.nettarion.hyperborea.core.ftms.FtmsServiceMetadata

class WftnpServiceDefinition(deviceInfo: DeviceInfo) {

    val dataCharacteristic = ShortUuid(FtmsDataEncoder.dataCharacteristicShortUuid(deviceInfo.type))

    val ftmsFeatureValue: ByteArray = FtmsServiceMetadata.ftmsFeatureValue(deviceInfo.type)

    private val resistanceRangeValue: ByteArray = FtmsServiceMetadata.resistanceRangeValue(deviceInfo)
    private val inclinationRangeValue: ByteArray = FtmsServiceMetadata.inclinationRangeValue(deviceInfo)
    private val powerRangeValue: ByteArray = FtmsServiceMetadata.powerRangeValue(deviceInfo)

    private val ftmsChars = listOf(
        CharDef(FTMS_FEATURE, PROP_READ, ftmsFeatureValue),
        CharDef(SUPPORTED_RESISTANCE, PROP_READ, resistanceRangeValue),
        CharDef(SUPPORTED_INCLINATION, PROP_READ, inclinationRangeValue),
        CharDef(SUPPORTED_POWER, PROP_READ, powerRangeValue),
        CharDef(FTMS_CONTROL_POINT, (PROP_WRITE.toInt() or PROP_INDICATE.toInt()).toByte(), null),
        CharDef(WAHOO_CONTROL, PROP_WRITE, null),
        CharDef(dataCharacteristic, PROP_NOTIFY, null),
        CharDef(TRAINING_STATUS, PROP_READ, FtmsServiceMetadata.TRAINING_STATUS_VALUE),
    )

    private val cpsChars = listOf(
        CharDef(CPS_FEATURE, PROP_READ, FtmsServiceMetadata.CPS_FEATURE_VALUE),
        CharDef(SENSOR_LOCATION, PROP_READ, FtmsServiceMetadata.SENSOR_LOCATION_VALUE),
        CharDef(CPS_MEASUREMENT, PROP_NOTIFY, null),
    )

    private val charsByService: Map<ShortUuid, List<CharDef>> = mapOf(
        FTMS_SERVICE to ftmsChars,
        CPS_SERVICE to cpsChars,
    )

    private val allChars: Map<ShortUuid, CharDef> =
        (ftmsChars + cpsChars).associateBy { it.uuid }

    fun characteristicsFor(service: ShortUuid): List<Pair<ShortUuid, Byte>>? =
        charsByService[service]?.map { it.uuid to it.properties }

    fun readValue(char: ShortUuid): ByteArray? = allChars[char]?.readValue?.copyOf()

    fun isNotifiable(char: ShortUuid): Boolean {
        val props = allChars[char]?.properties?.toInt() ?: return false
        return (props and (PROP_NOTIFY.toInt() or PROP_INDICATE.toInt())) != 0
    }

    fun isWritable(char: ShortUuid): Boolean {
        val props = allChars[char]?.properties?.toInt() ?: return false
        return (props and PROP_WRITE.toInt()) != 0
    }

    fun serviceForCharacteristic(char: ShortUuid): ShortUuid? =
        charsByService.entries.firstOrNull { (_, chars) -> chars.any { it.uuid == char } }?.key

    private data class CharDef(val uuid: ShortUuid, val properties: Byte, val readValue: ByteArray?)

    companion object {
        // WFTNP property flags (NOT standard BLE ATT flags)
        const val PROP_READ: Byte = 0x01
        const val PROP_WRITE: Byte = 0x02
        const val PROP_NOTIFY: Byte = 0x04
        const val PROP_INDICATE: Byte = 0x08

        // Services — derived from FtmsServiceMetadata
        val FTMS_SERVICE = ShortUuid(FtmsServiceMetadata.FTMS_SERVICE)
        val CPS_SERVICE = ShortUuid(FtmsServiceMetadata.CPS_SERVICE)

        // FTMS characteristics
        val FTMS_FEATURE = ShortUuid(FtmsServiceMetadata.FTMS_FEATURE)
        val SUPPORTED_RESISTANCE = ShortUuid(FtmsServiceMetadata.SUPPORTED_RESISTANCE)
        val SUPPORTED_INCLINATION = ShortUuid(FtmsServiceMetadata.SUPPORTED_INCLINATION)
        val SUPPORTED_POWER = ShortUuid(FtmsServiceMetadata.SUPPORTED_POWER)
        val FTMS_CONTROL_POINT = ShortUuid(FtmsServiceMetadata.FTMS_CONTROL_POINT)
        val WAHOO_CONTROL = ShortUuid(FtmsServiceMetadata.WAHOO_CONTROL)
        val TRAINING_STATUS = ShortUuid(FtmsServiceMetadata.TRAINING_STATUS)

        // CPS characteristics
        val CPS_FEATURE = ShortUuid(FtmsServiceMetadata.CPS_FEATURE)
        val SENSOR_LOCATION = ShortUuid(FtmsServiceMetadata.SENSOR_LOCATION)
        val CPS_MEASUREMENT = ShortUuid(FtmsServiceMetadata.CPS_MEASUREMENT)

        val services: List<ShortUuid> = listOf(FTMS_SERVICE, CPS_SERVICE)
    }
}
