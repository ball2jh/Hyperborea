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
    fun `fallback has bike type defaults`() {
        val info = DeviceDatabase.fallback()
        assertThat(info.inclineStep).isEqualTo(0.5f)
        assertThat(info.speedStep).isEqualTo(0.5f)
    }

    @Test
    fun `unknown model number returns fallback`() {
        val info = DeviceDatabase.fromModel(99999)
        assertThat(info.name).isEqualTo("FitPro Device")
        assertThat(info.type).isEqualTo(DeviceType.BIKE)
    }

    // ── Type-Based Defaults (via defaultsForType) ──

    @Test
    fun `bike defaults have correct bounds`() {
        val info = DeviceDatabase.defaultsForType(DeviceType.BIKE)
        assertThat(info.maxResistance).isEqualTo(24)
        assertThat(info.minResistance).isEqualTo(1)
        assertThat(info.minIncline).isEqualTo(-10f)
        assertThat(info.maxIncline).isEqualTo(20f)
        assertThat(info.maxSpeed).isEqualTo(60f)
        assertThat(info.maxPower).isEqualTo(2000)
        assertThat(info.minPower).isEqualTo(0)
        assertThat(info.powerStep).isEqualTo(1)
        assertThat(info.resistanceStep).isEqualTo(1.0f)
    }

    @Test
    fun `treadmill defaults have correct bounds`() {
        val info = DeviceDatabase.defaultsForType(DeviceType.TREADMILL)
        assertThat(info.maxResistance).isEqualTo(0)
        assertThat(info.minResistance).isEqualTo(0)
        assertThat(info.minIncline).isEqualTo(-6f)
        assertThat(info.maxIncline).isEqualTo(40f)
        assertThat(info.maxSpeed).isEqualTo(24f)
    }

    @Test
    fun `rower defaults have correct bounds`() {
        val info = DeviceDatabase.defaultsForType(DeviceType.ROWER)
        assertThat(info.maxResistance).isEqualTo(26)
        assertThat(info.minResistance).isEqualTo(1)
        assertThat(info.minIncline).isEqualTo(0f)
        assertThat(info.maxIncline).isEqualTo(0f)
        assertThat(info.maxSpeed).isEqualTo(0f)
    }

    @Test
    fun `elliptical defaults have correct bounds`() {
        val info = DeviceDatabase.defaultsForType(DeviceType.ELLIPTICAL)
        assertThat(info.maxResistance).isEqualTo(26)
        assertThat(info.minResistance).isEqualTo(1)
        assertThat(info.minIncline).isEqualTo(-10f)
        assertThat(info.maxIncline).isEqualTo(15f)
        assertThat(info.maxSpeed).isEqualTo(60f)
    }

    // ── Metrics by Type ──

    @Test
    fun `treadmill metrics via defaultsForType`() {
        val info = DeviceDatabase.defaultsForType(DeviceType.TREADMILL)
        assertThat(info.supportedMetrics).containsExactly(
            Metric.POWER, Metric.SPEED, Metric.INCLINE,
            Metric.DISTANCE, Metric.CALORIES,
        )
        assertThat(info.supportedMetrics).containsNoneOf(Metric.CADENCE, Metric.RESISTANCE)
    }

    @Test
    fun `rower metrics via defaultsForType`() {
        val info = DeviceDatabase.defaultsForType(DeviceType.ROWER)
        assertThat(info.supportedMetrics).containsExactly(
            Metric.POWER, Metric.CADENCE, Metric.SPEED,
            Metric.RESISTANCE,
            Metric.DISTANCE, Metric.CALORIES,
        )
        assertThat(info.supportedMetrics).doesNotContain(Metric.INCLINE)
    }

    @Test
    fun `elliptical metrics via defaultsForType`() {
        val info = DeviceDatabase.defaultsForType(DeviceType.ELLIPTICAL)
        assertThat(info.supportedMetrics).containsExactly(
            Metric.POWER, Metric.CADENCE, Metric.SPEED,
            Metric.RESISTANCE, Metric.INCLINE,
            Metric.DISTANCE, Metric.CALORIES,
        )
    }

    // ── deviceTypeFromEquipmentId ──

    @Test
    fun `deviceTypeFromEquipmentId maps treadmill IDs`() {
        assertThat(DeviceDatabase.deviceTypeFromEquipmentId(4)).isEqualTo(DeviceType.TREADMILL)
        assertThat(DeviceDatabase.deviceTypeFromEquipmentId(5)).isEqualTo(DeviceType.TREADMILL)
    }

    @Test
    fun `deviceTypeFromEquipmentId maps elliptical IDs`() {
        assertThat(DeviceDatabase.deviceTypeFromEquipmentId(6)).isEqualTo(DeviceType.ELLIPTICAL)
        assertThat(DeviceDatabase.deviceTypeFromEquipmentId(9)).isEqualTo(DeviceType.ELLIPTICAL)
        assertThat(DeviceDatabase.deviceTypeFromEquipmentId(19)).isEqualTo(DeviceType.ELLIPTICAL)
    }

    @Test
    fun `deviceTypeFromEquipmentId maps bike IDs`() {
        assertThat(DeviceDatabase.deviceTypeFromEquipmentId(7)).isEqualTo(DeviceType.BIKE)
        assertThat(DeviceDatabase.deviceTypeFromEquipmentId(8)).isEqualTo(DeviceType.BIKE)
    }

    @Test
    fun `deviceTypeFromEquipmentId maps rower ID`() {
        assertThat(DeviceDatabase.deviceTypeFromEquipmentId(20)).isEqualTo(DeviceType.ROWER)
    }

    @Test
    fun `deviceTypeFromEquipmentId defaults to BIKE for unknown`() {
        assertThat(DeviceDatabase.deviceTypeFromEquipmentId(99)).isEqualTo(DeviceType.BIKE)
    }

    // ── Product ID lookup ──

    @Test
    fun `fromProductId returns fallback for known product IDs`() {
        assertThat(DeviceDatabase.fromProductId(2)?.name).isEqualTo("FitPro Device")
        assertThat(DeviceDatabase.fromProductId(3)?.name).isEqualTo("FitPro Device")
        assertThat(DeviceDatabase.fromProductId(4)?.name).isEqualTo("FitPro Device")
    }

    @Test
    fun `fromProductId returns null for unknown product ID`() {
        assertThat(DeviceDatabase.fromProductId(999)).isNull()
    }

    // ── fromHandshake with catalog enrichment ──

    @Test
    fun `fromHandshake returns fallback for unknown part number`() {
        val info = DeviceDatabase.fromHandshake(0, 0)
        assertThat(info.name).isEqualTo("FitPro Device")
    }

    @Test
    fun `fromHandshake returns fallback for all zeros`() {
        val info = DeviceDatabase.fromHandshake(0, 0)
        assertThat(info.name).isEqualTo("FitPro Device")
    }

    @Test
    fun `fromHandshake uses part number for device name`() {
        val info = DeviceDatabase.fromHandshake(0, 425738)
        assertThat(info.name).isEqualTo("NordicTrack S22i")
    }

    @Test
    fun `fromHandshake returns correct type for S22i bike`() {
        val info = DeviceDatabase.fromHandshake(0, 392570)
        assertThat(info.name).isEqualTo("NordicTrack S22i")
        assertThat(info.type).isEqualTo(DeviceType.BIKE)
        assertThat(info.maxResistance).isEqualTo(24)
        assertThat(info.minIncline).isEqualTo(-10f)
        assertThat(info.maxIncline).isEqualTo(20f)
    }

    @Test
    fun `fromHandshake returns correct type for treadmill`() {
        // 397807 = FREEMOTION i10.9 INCLINE TRAINER, equipment_type=treadmill
        val info = DeviceDatabase.fromHandshake(0, 397807)
        assertThat(info.type).isEqualTo(DeviceType.TREADMILL)
        assertThat(info.minResistance).isEqualTo(0)
        assertThat(info.minIncline).isEqualTo(-3f)
        assertThat(info.maxIncline).isEqualTo(30f)
    }

    @Test
    fun `fromHandshake returns correct type for rower`() {
        // 398211 = NordicTrack RW900, equipment_type=rower
        val info = DeviceDatabase.fromHandshake(0, 398211)
        assertThat(info.type).isEqualTo(DeviceType.ROWER)
        assertThat(info.maxResistance).isEqualTo(26)
    }

    @Test
    fun `unknown part number falls back to BIKE defaults`() {
        val info = DeviceDatabase.fromHandshake(0, 1)
        assertThat(info.name).isEqualTo("FitPro Device")
        assertThat(info.type).isEqualTo(DeviceType.BIKE)
        assertThat(info.maxResistance).isEqualTo(24) // BIKE default
    }

    // ── Power Curve Index ──

    @Test
    fun `powerCurveIndexForPartNumber returns index for known part`() {
        // Part number 425738 = NordicTrack S22i, powerCurveIndex=17
        val index = DeviceDatabase.powerCurveIndexForPartNumber(425738)
        assertThat(index).isEqualTo(17)
    }

    @Test
    fun `powerCurveIndexForPartNumber returns null for unknown part`() {
        val index = DeviceDatabase.powerCurveIndexForPartNumber(0)
        assertThat(index).isNull()
    }

    @Test
    fun `powerCurveIndexForPartNumber returns null for part with name only`() {
        // Part 123546 = FREEMOTION t10.8, has name but no power curve
        val index = DeviceDatabase.powerCurveIndexForPartNumber(123546)
        assertThat(index).isNull()
    }

    // ── Catalog Summary ──

    @Test
    fun `catalogSummary returns diagnostic string for known part`() {
        val summary = DeviceDatabase.catalogSummary(392570)
        assertThat(summary).isNotNull()
        assertThat(summary).contains("NordicTrack S22i")
        assertThat(summary).contains("EBNT02117")
        assertThat(summary).contains("bike")
        assertThat(summary).contains("res=0-24")
        assertThat(summary).contains("incline=-10..20")
        assertThat(summary).contains("curve=17")
    }

    @Test
    fun `catalogSummary returns null for unknown part`() {
        val summary = DeviceDatabase.catalogSummary(0)
        assertThat(summary).isNull()
    }
}
