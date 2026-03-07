package com.nettarion.hyperborea.broadcast.wftnp

object WftnpServiceDefinition {

    // WFTNP property flags (NOT standard BLE ATT flags)
    const val PROP_READ: Byte = 0x01
    const val PROP_WRITE: Byte = 0x02
    const val PROP_NOTIFY: Byte = 0x04
    const val PROP_INDICATE: Byte = 0x08

    // Services
    val FTMS_SERVICE = ShortUuid(0x1826)
    val CPS_SERVICE = ShortUuid(0x1818)

    // FTMS characteristics
    val FTMS_FEATURE = ShortUuid(0x2ACC)
    val SUPPORTED_RESISTANCE = ShortUuid(0x2AD6)
    val FTMS_CONTROL_POINT = ShortUuid(0x2AD9)
    val WAHOO_CONTROL = ShortUuid(0xE005)
    val INDOOR_BIKE_DATA = ShortUuid(0x2AD2)
    val TRAINING_STATUS = ShortUuid(0x2AD3)

    // CPS characteristics
    val CPS_FEATURE = ShortUuid(0x2A65)
    val SENSOR_LOCATION = ShortUuid(0x2A5D)
    val CPS_MEASUREMENT = ShortUuid(0x2A63)

    // Static read values (verified against Zwift's FTMS_ProcessMachineFeatures)
    val FTMS_FEATURE_VALUE = byteArrayOf(
        0x83.toByte(), 0x14, 0x00, 0x00, 0x0C, 0xE0.toByte(), 0x00, 0x00,
    )
    val RESISTANCE_RANGE_VALUE = byteArrayOf(
        0x0A, 0x00, 0x96.toByte(), 0x00, 0x0A, 0x00,
    )
    val TRAINING_STATUS_VALUE = byteArrayOf(0x00, 0x01)
    val CPS_FEATURE_VALUE = byteArrayOf(0x0C, 0x00, 0x00, 0x00)
    val SENSOR_LOCATION_VALUE = byteArrayOf(0x0D)

    val services: List<ShortUuid> = listOf(FTMS_SERVICE, CPS_SERVICE)

    private data class CharDef(val uuid: ShortUuid, val properties: Byte, val readValue: ByteArray?)

    private val ftmsChars = listOf(
        CharDef(FTMS_FEATURE, PROP_READ, FTMS_FEATURE_VALUE),
        CharDef(SUPPORTED_RESISTANCE, PROP_READ, RESISTANCE_RANGE_VALUE),
        CharDef(FTMS_CONTROL_POINT, (PROP_WRITE.toInt() or PROP_INDICATE.toInt()).toByte(), null),
        CharDef(WAHOO_CONTROL, PROP_WRITE, null),
        CharDef(INDOOR_BIKE_DATA, PROP_NOTIFY, null),
        CharDef(TRAINING_STATUS, PROP_READ, TRAINING_STATUS_VALUE),
    )

    private val cpsChars = listOf(
        CharDef(CPS_FEATURE, PROP_READ, CPS_FEATURE_VALUE),
        CharDef(SENSOR_LOCATION, PROP_READ, SENSOR_LOCATION_VALUE),
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
}
