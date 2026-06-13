package com.nettarion.hyperborea.core.ftms

import com.nettarion.hyperborea.core.model.DeviceType

/**
 * Standard fitness GATT services we expose alongside FTMS, identified by their 16-bit assigned UUID.
 */
enum class GattService(val shortUuid: Int) {
    FTMS(FtmsServiceMetadata.FTMS_SERVICE),                       // 0x1826
    CYCLING_POWER(FtmsServiceMetadata.CPS_SERVICE),               // 0x1818
    RUNNING_SPEED_CADENCE(FtmsServiceMetadata.RSC_SERVICE),       // 0x1814
}

/**
 * Single source of truth for which GATT services a given [DeviceType] advertises across every
 * broadcast path (BLE GATT, the WiFi/TNP service definition, and the mDNS TXT record). Keeping the
 * set here means those three paths can never drift out of sync.
 *
 * The service set is what fitness apps classify a device by *before* they inspect FTMS data
 * characteristics. Treadmills must present the Running Speed & Cadence service so run-oriented apps
 * surface them on the run pairing screen; advertising Cycling Power on a treadmill makes it look
 * like a bike. Bikes/ellipticals/rowers keep Cycling Power, which acts as a power-meter source.
 */
object BroadcastProfile {

    fun servicesFor(type: DeviceType): List<GattService> = when (type) {
        DeviceType.BIKE -> listOf(GattService.FTMS, GattService.CYCLING_POWER)
        DeviceType.ELLIPTICAL -> listOf(GattService.FTMS, GattService.CYCLING_POWER)
        DeviceType.ROWER -> listOf(GattService.FTMS, GattService.CYCLING_POWER)
        DeviceType.TREADMILL -> listOf(GattService.FTMS, GattService.RUNNING_SPEED_CADENCE)
    }

    /**
     * Comma-separated full 128-bit UUIDs for the mDNS `ble-service-uuids` TXT attribute. Lowercase
     * to match the format fitness clients have historically been handed.
     */
    fun advertisedUuidCsv(type: DeviceType): String =
        servicesFor(type).joinToString(",") {
            "0000%04x-0000-1000-8000-00805f9b34fb".format(it.shortUuid)
        }
}
