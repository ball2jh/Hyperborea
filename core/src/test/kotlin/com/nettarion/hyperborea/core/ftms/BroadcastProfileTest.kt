package com.nettarion.hyperborea.core.ftms

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.DeviceType
import org.junit.Test

class BroadcastProfileTest {

    @Test
    fun `bike advertises FTMS and Cycling Power, not RSC`() {
        val services = BroadcastProfile.servicesFor(DeviceType.BIKE)
        assertThat(services).containsExactly(GattService.FTMS, GattService.CYCLING_POWER).inOrder()
        assertThat(services).doesNotContain(GattService.RUNNING_SPEED_CADENCE)
    }

    @Test
    fun `treadmill advertises FTMS and RSC, not Cycling Power`() {
        val services = BroadcastProfile.servicesFor(DeviceType.TREADMILL)
        assertThat(services).containsExactly(GattService.FTMS, GattService.RUNNING_SPEED_CADENCE).inOrder()
        assertThat(services).doesNotContain(GattService.CYCLING_POWER)
    }

    @Test
    fun `rower and elliptical keep FTMS and Cycling Power`() {
        assertThat(BroadcastProfile.servicesFor(DeviceType.ROWER))
            .containsExactly(GattService.FTMS, GattService.CYCLING_POWER).inOrder()
        assertThat(BroadcastProfile.servicesFor(DeviceType.ELLIPTICAL))
            .containsExactly(GattService.FTMS, GattService.CYCLING_POWER).inOrder()
    }

    @Test
    fun `every device type always exposes FTMS`() {
        for (type in DeviceType.entries) {
            assertThat(BroadcastProfile.servicesFor(type)).contains(GattService.FTMS)
        }
    }

    @Test
    fun `advertisedUuidCsv uses lowercase full 128-bit UUIDs`() {
        assertThat(BroadcastProfile.advertisedUuidCsv(DeviceType.BIKE)).isEqualTo(
            "00001826-0000-1000-8000-00805f9b34fb,00001818-0000-1000-8000-00805f9b34fb",
        )
    }

    @Test
    fun `advertisedUuidCsv for treadmill lists RSC and omits Cycling Power`() {
        val csv = BroadcastProfile.advertisedUuidCsv(DeviceType.TREADMILL)
        assertThat(csv).contains("00001814-0000-1000-8000-00805f9b34fb") // RSC
        assertThat(csv).contains("00001826-0000-1000-8000-00805f9b34fb") // FTMS
        assertThat(csv).doesNotContain("00001818") // no Cycling Power
    }
}
