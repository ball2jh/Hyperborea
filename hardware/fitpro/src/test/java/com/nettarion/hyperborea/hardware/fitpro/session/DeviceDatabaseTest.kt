package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.Metric
import org.junit.Test

class DeviceDatabaseTest {

    @Test
    fun `fallback returns FitPro Device with BIKE type`() {
        val info = DeviceDatabase.fallback()
        assertThat(info.name).isEqualTo("FitPro Device")
        assertThat(info.type).isEqualTo(DeviceType.BIKE)
    }

    @Test
    fun `fallback includes standard bike metrics`() {
        val info = DeviceDatabase.fallback()
        assertThat(info.supportedMetrics).containsExactly(
            Metric.POWER, Metric.CADENCE, Metric.SPEED,
            Metric.RESISTANCE, Metric.INCLINE,
            Metric.DISTANCE, Metric.CALORIES,
        )
    }

    @Test
    fun `unknown model number returns fallback`() {
        val info = DeviceDatabase.fromModel(99999)
        assertThat(info.name).isEqualTo("FitPro Device")
        assertThat(info.type).isEqualTo(DeviceType.BIKE)
    }

    @Test
    fun `fromModel 2117 returns NordicTrack S22i with correct capabilities`() {
        val info = DeviceDatabase.fromModel(2117)
        assertThat(info.name).isEqualTo("NordicTrack S22i")
        assertThat(info.type).isEqualTo(DeviceType.BIKE)
        assertThat(info.maxResistance).isEqualTo(24)
        assertThat(info.minIncline).isEqualTo(-6f)
        assertThat(info.maxIncline).isEqualTo(40f)
    }
}
