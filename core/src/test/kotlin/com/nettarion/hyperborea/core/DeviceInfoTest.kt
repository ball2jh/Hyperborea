package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceInfoTest {

    @Test
    fun `construction with all fields`() {
        val info = DeviceInfo(
            name = "Test Bike",
            type = DeviceType.BIKE,
            supportedMetrics = setOf(Metric.POWER, Metric.CADENCE),
        )
        assertThat(info.name).isEqualTo("Test Bike")
        assertThat(info.type).isEqualTo(DeviceType.BIKE)
        assertThat(info.supportedMetrics).containsExactly(Metric.POWER, Metric.CADENCE)
    }

    @Test
    fun `supportedMetrics is a Set — no duplicates`() {
        val info = DeviceInfo(
            name = "Bike",
            type = DeviceType.BIKE,
            supportedMetrics = setOf(Metric.POWER, Metric.POWER, Metric.CADENCE),
        )
        assertThat(info.supportedMetrics).hasSize(2)
    }

    @Test
    fun `data class equality`() {
        val a = DeviceInfo("Bike", DeviceType.BIKE, setOf(Metric.POWER))
        val b = DeviceInfo("Bike", DeviceType.BIKE, setOf(Metric.POWER))
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `DeviceType enum has all expected entries`() {
        assertThat(DeviceType.entries).containsExactly(
            DeviceType.BIKE,
            DeviceType.TREADMILL,
            DeviceType.ROWER,
            DeviceType.ELLIPTICAL,
        )
    }

    @Test
    fun `Metric enum has all expected entries`() {
        assertThat(Metric.entries).containsExactly(
            Metric.POWER,
            Metric.CADENCE,
            Metric.SPEED,
            Metric.RESISTANCE,
            Metric.INCLINE,
            Metric.HEART_RATE,
            Metric.DISTANCE,
            Metric.CALORIES,
        )
    }

    @Test
    fun `DeviceType valueOf round-trip`() {
        for (type in DeviceType.entries) {
            assertThat(type).isEqualTo(DeviceType.valueOf(type.name))
        }
    }

    @Test
    fun `Metric valueOf round-trip`() {
        for (metric in Metric.entries) {
            assertThat(metric).isEqualTo(Metric.valueOf(metric.name))
        }
    }
}
