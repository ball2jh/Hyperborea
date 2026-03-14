package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.DeviceType
import org.junit.Test

class PowerEstimatorTest {

    // ── Direct lookup: exact RPM + exact resistance index ──

    @Test
    fun `exact RPM and exact resistance index returns direct lookup`() {
        // Table 0, RPM=40 (speed=8kph at 5x), resistance index 0 (0%) → 9
        val result = PowerEstimator.estimate(
            tableIndex = 0, speedKph = 8.0f, resistance = 0, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        assertThat(result).isEqualTo(9)
    }

    @Test
    fun `exact RPM and 100 percent resistance returns last column`() {
        // Table 0, RPM=20 (speed=4kph at 5x), resistance 24/24=100% → index 10 → 29
        val result = PowerEstimator.estimate(
            tableIndex = 0, speedKph = 4.0f, resistance = 24, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        assertThat(result).isEqualTo(29)
    }

    // ── RPM interpolation with exact resistance ──

    @Test
    fun `RPM interpolation with exact resistance index`() {
        // Table 0, RPM=25 (speed=5kph), 0% resistance
        // Lower RPM=20 → 6, Upper RPM=30 → 8
        // ratio = (25-20)/(30-20) = 0.5 → lerp(6, 8, 0.5) = 7
        val result = PowerEstimator.estimate(
            tableIndex = 0, speedKph = 5.0f, resistance = 0, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        assertThat(result).isEqualTo(7)
    }

    // ── Resistance interpolation with exact RPM ──

    @Test
    fun `resistance interpolation with exact RPM`() {
        // Table 0, RPM=20 (speed=4kph), 15% resistance (between index 1=10% and index 2=20%)
        // Need resistance 15% of 24 max → 3.6, so resistance=4 gives 4/24*100=16.7%
        // Let's use exact: resistance=6 out of 24 → 25% → index 2, fraction 0.5
        // RPM=20 row: index 2=7, index 3=7 → lerp(7,7,0.5) = 7
        val result = PowerEstimator.estimate(
            tableIndex = 0, speedKph = 4.0f, resistance = 6, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        assertThat(result).isEqualTo(7)
    }

    // ── Full bilinear interpolation ──

    @Test
    fun `bilinear interpolation across RPM and resistance`() {
        // Table 0, RPM=25 (speed=5kph), 50% resistance (12/24)
        // RPM=20 row: index 5=10, RPM=30 row: index 5=16
        // RPM ratio = (25-20)/(30-20) = 0.5
        // lerp(10, 16, 0.5) = 13
        val result = PowerEstimator.estimate(
            tableIndex = 0, speedKph = 5.0f, resistance = 12, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        assertThat(result).isEqualTo(13)
    }

    // ── Boundary: RPM below min row ──

    @Test
    fun `RPM below min row uses first row`() {
        // Table 0, RPM=10 (speed=2kph), 0% resistance
        // Below min RPM=20, so uses first row, no interpolation ratio
        // RPM=20 row, index 0 = 6
        val result = PowerEstimator.estimate(
            tableIndex = 0, speedKph = 2.0f, resistance = 0, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        assertThat(result).isEqualTo(6)
    }

    // ── Boundary: RPM above max row ──

    @Test
    fun `RPM above max row uses last row`() {
        // Table 0, RPM=150 (speed=30kph), 0% resistance
        // Above max RPM=120, so uses last row
        // RPM=120 row, index 0 = 29
        val result = PowerEstimator.estimate(
            tableIndex = 0, speedKph = 30.0f, resistance = 0, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        assertThat(result).isEqualTo(29)
    }

    // ── Table 13: constant power (all resistance values same) ──

    @Test
    fun `table 13 constant power ignores resistance`() {
        // Table 13, RPM=40 (speed=8kph), any resistance → always 52
        val r0 = PowerEstimator.estimate(
            tableIndex = 13, speedKph = 8.0f, resistance = 0, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        val r50 = PowerEstimator.estimate(
            tableIndex = 13, speedKph = 8.0f, resistance = 12, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        val r100 = PowerEstimator.estimate(
            tableIndex = 13, speedKph = 8.0f, resistance = 24, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        assertThat(r0).isEqualTo(52)
        assertThat(r50).isEqualTo(52)
        assertThat(r100).isEqualTo(52)
    }

    // ── Returns null for invalid inputs ──

    @Test
    fun `returns null for speed zero`() {
        val result = PowerEstimator.estimate(
            tableIndex = 0, speedKph = 0.0f, resistance = 12, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        assertThat(result).isNull()
    }

    @Test
    fun `returns null for maxResistance zero`() {
        val result = PowerEstimator.estimate(
            tableIndex = 0, speedKph = 10.0f, resistance = 5, maxResistance = 0,
            deviceType = DeviceType.BIKE,
        )
        assertThat(result).isNull()
    }

    @Test
    fun `returns null for invalid table index`() {
        val result = PowerEstimator.estimate(
            tableIndex = 99, speedKph = 10.0f, resistance = 5, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        assertThat(result).isNull()
    }

    // ── Elliptical uses 8x RPM factor ──

    @Test
    fun `elliptical uses 8x RPM conversion factor`() {
        // Table 0, speed=5kph → RPM=40 (8x for elliptical)
        // RPM=40 row, 0% resistance → 9
        val result = PowerEstimator.estimate(
            tableIndex = 0, speedKph = 5.0f, resistance = 0, maxResistance = 24,
            deviceType = DeviceType.ELLIPTICAL,
        )
        assertThat(result).isEqualTo(9)
    }

    // ── Fallback formula ──

    @Test
    fun `fallback returns reasonable values`() {
        val result = PowerEstimator.estimateFallback(
            speedKph = 20.0f, resistance = 12, maxResistance = 24,
        )
        assertThat(result).isNotNull()
        assertThat(result!!).isGreaterThan(0)
    }

    @Test
    fun `fallback returns null for speed zero`() {
        val result = PowerEstimator.estimateFallback(
            speedKph = 0.0f, resistance = 12, maxResistance = 24,
        )
        assertThat(result).isNull()
    }

    @Test
    fun `fallback returns null for maxResistance zero`() {
        val result = PowerEstimator.estimateFallback(
            speedKph = 10.0f, resistance = 5, maxResistance = 0,
        )
        assertThat(result).isNull()
    }

    @Test
    fun `fallback increases with speed`() {
        val slow = PowerEstimator.estimateFallback(
            speedKph = 10.0f, resistance = 12, maxResistance = 24,
        )!!
        val fast = PowerEstimator.estimateFallback(
            speedKph = 20.0f, resistance = 12, maxResistance = 24,
        )!!
        assertThat(fast).isGreaterThan(slow)
    }

    @Test
    fun `fallback increases with resistance`() {
        val low = PowerEstimator.estimateFallback(
            speedKph = 15.0f, resistance = 5, maxResistance = 24,
        )!!
        val high = PowerEstimator.estimateFallback(
            speedKph = 15.0f, resistance = 20, maxResistance = 24,
        )!!
        assertThat(high).isGreaterThan(low)
    }

    // ── Verify against known GlassOS data point ──

    @Test
    fun `table 0 at RPM 100 and 50 percent resistance matches GlassOS`() {
        // Table 0, RPM=100 (speed=20kph), 50% resistance → index 5 → 103
        val result = PowerEstimator.estimate(
            tableIndex = 0, speedKph = 20.0f, resistance = 12, maxResistance = 24,
            deviceType = DeviceType.BIKE,
        )
        assertThat(result).isEqualTo(103)
    }
}
