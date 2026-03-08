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
    fun `fallback has S22i incline range`() {
        val info = DeviceDatabase.fallback()
        assertThat(info.minIncline).isEqualTo(-10f)
        assertThat(info.maxIncline).isEqualTo(20f)
    }

    @Test
    fun `unknown model number returns fallback`() {
        val info = DeviceDatabase.fromModel(99999)
        assertThat(info.name).isEqualTo("FitPro Device")
        assertThat(info.type).isEqualTo(DeviceType.BIKE)
    }

    // ── NordicTrack Bikes ──

    @Test
    fun `fromModel 2117 returns NordicTrack S22i with correct capabilities`() {
        val info = DeviceDatabase.fromModel(2117)
        assertThat(info.name).isEqualTo("NordicTrack S22i")
        assertThat(info.type).isEqualTo(DeviceType.BIKE)
        assertThat(info.maxResistance).isEqualTo(24)
        assertThat(info.minIncline).isEqualTo(-10f)
        assertThat(info.maxIncline).isEqualTo(20f)
    }

    @Test
    fun `S22i variants share same capabilities`() {
        val v1 = DeviceDatabase.fromModel(2117)
        val v2 = DeviceDatabase.fromModel(2121)
        val v3 = DeviceDatabase.fromModel(2422)
        assertThat(v2.maxResistance).isEqualTo(v1.maxResistance)
        assertThat(v2.minIncline).isEqualTo(v1.minIncline)
        assertThat(v2.maxIncline).isEqualTo(v1.maxIncline)
        assertThat(v3.maxResistance).isEqualTo(v1.maxResistance)
    }

    @Test
    fun `S15i and S10i have lower max resistance`() {
        val s15i = DeviceDatabase.fromModel(5119)
        val s10i = DeviceDatabase.fromModel(3121)
        assertThat(s15i.maxResistance).isEqualTo(22)
        assertThat(s10i.maxResistance).isEqualTo(22)
        assertThat(s15i.type).isEqualTo(DeviceType.BIKE)
    }

    // ── NordicTrack Treadmills ──

    @Test
    fun `X32i is a treadmill with decline and steep incline`() {
        val info = DeviceDatabase.fromModel(39225)
        assertThat(info.name).isEqualTo("NordicTrack Commercial X32i")
        assertThat(info.type).isEqualTo(DeviceType.TREADMILL)
        assertThat(info.minIncline).isEqualTo(-6f)
        assertThat(info.maxIncline).isEqualTo(40f)
        assertThat(info.maxResistance).isEqualTo(0)
        assertThat(info.minResistance).isEqualTo(0)
    }

    @Test
    fun `treadmills have treadmill metric set`() {
        val info = DeviceDatabase.fromModel(39225)
        assertThat(info.supportedMetrics).containsExactly(
            Metric.POWER, Metric.SPEED, Metric.INCLINE,
            Metric.DISTANCE, Metric.CALORIES,
        )
        assertThat(info.supportedMetrics).containsNoneOf(Metric.CADENCE, Metric.RESISTANCE)
    }

    @Test
    fun `Commercial 2450 has limited incline range`() {
        val info = DeviceDatabase.fromModel(19125)
        assertThat(info.minIncline).isEqualTo(-3f)
        assertThat(info.maxIncline).isEqualTo(12f)
    }

    // ── NordicTrack Rowers ──

    @Test
    fun `RW900 is a rower with no incline or speed`() {
        val info = DeviceDatabase.fromModel(19425)
        assertThat(info.name).isEqualTo("NordicTrack RW900")
        assertThat(info.type).isEqualTo(DeviceType.ROWER)
        assertThat(info.maxResistance).isEqualTo(26)
        assertThat(info.minIncline).isEqualTo(0f)
        assertThat(info.maxIncline).isEqualTo(0f)
        assertThat(info.maxSpeed).isEqualTo(0f)
    }

    @Test
    fun `rowers have rower metric set`() {
        val info = DeviceDatabase.fromModel(19425)
        assertThat(info.supportedMetrics).containsExactly(
            Metric.POWER, Metric.CADENCE, Metric.SPEED,
            Metric.RESISTANCE,
            Metric.DISTANCE, Metric.CALORIES,
        )
        assertThat(info.supportedMetrics).doesNotContain(Metric.INCLINE)
    }

    // ── NordicTrack Ellipticals ──

    @Test
    fun `FS14i is an elliptical with symmetric incline`() {
        val info = DeviceDatabase.fromModel(71620)
        assertThat(info.name).isEqualTo("NordicTrack FS14i")
        assertThat(info.type).isEqualTo(DeviceType.ELLIPTICAL)
        assertThat(info.minIncline).isEqualTo(-10f)
        assertThat(info.maxIncline).isEqualTo(10f)
        assertThat(info.maxResistance).isEqualTo(26)
    }

    @Test
    fun `ellipticals have elliptical metric set`() {
        val info = DeviceDatabase.fromModel(71620)
        assertThat(info.supportedMetrics).containsExactly(
            Metric.POWER, Metric.CADENCE, Metric.SPEED,
            Metric.RESISTANCE, Metric.INCLINE,
            Metric.DISTANCE, Metric.CALORIES,
        )
    }

    // ── ProForm Bikes ──

    @Test
    fun `ProForm bikes have no incline`() {
        val info = DeviceDatabase.fromModel(92220)
        assertThat(info.name).isEqualTo("ProForm Studio Bike Pro 22")
        assertThat(info.type).isEqualTo(DeviceType.BIKE)
        assertThat(info.minIncline).isEqualTo(0f)
        assertThat(info.maxIncline).isEqualTo(0f)
        assertThat(info.inclineStep).isEqualTo(0f)
        assertThat(info.maxResistance).isEqualTo(24)
    }

    @Test
    fun `ProForm Studio Bike Pro 14 has 22 resistance levels`() {
        val info = DeviceDatabase.fromModel(16723)
        assertThat(info.maxResistance).isEqualTo(22)
    }

    // ── ProForm Treadmills ──

    @Test
    fun `ProForm Pro 9000 is a treadmill with decline`() {
        val info = DeviceDatabase.fromModel(15820)
        assertThat(info.name).isEqualTo("ProForm Pro 9000")
        assertThat(info.type).isEqualTo(DeviceType.TREADMILL)
        assertThat(info.minIncline).isEqualTo(-3f)
        assertThat(info.maxIncline).isEqualTo(12f)
        assertThat(info.maxSpeed).isEqualTo(19.3f)
    }

    @Test
    fun `ProForm Carbon models have no decline`() {
        val carbon9000 = DeviceDatabase.fromModel(16925)
        val carbon2000 = DeviceDatabase.fromModel(10925)
        assertThat(carbon9000.minIncline).isEqualTo(0f)
        assertThat(carbon2000.minIncline).isEqualTo(0f)
    }

    @Test
    fun `ProForm Carbon T14 and T10 have lower max speed`() {
        val t14 = DeviceDatabase.fromModel(12823)
        val t10 = DeviceDatabase.fromModel(99920)
        assertThat(t14.maxSpeed).isEqualTo(16.1f)
        assertThat(t10.maxSpeed).isEqualTo(16.1f)
    }

    // ── ProForm Rower ──

    @Test
    fun `ProForm Pro R10 is a rower`() {
        val info = DeviceDatabase.fromModel(98120)
        assertThat(info.name).isEqualTo("ProForm Pro R10")
        assertThat(info.type).isEqualTo(DeviceType.ROWER)
        assertThat(info.maxResistance).isEqualTo(24)
    }

    // ── ProForm Elliptical ──

    @Test
    fun `ProForm HIIT H14 is an elliptical with no incline`() {
        val info = DeviceDatabase.fromModel(1420)
        assertThat(info.name).isEqualTo("ProForm Pro HIIT H14")
        assertThat(info.type).isEqualTo(DeviceType.ELLIPTICAL)
        assertThat(info.minIncline).isEqualTo(0f)
        assertThat(info.maxIncline).isEqualTo(0f)
        assertThat(info.maxResistance).isEqualTo(26)
    }
}
