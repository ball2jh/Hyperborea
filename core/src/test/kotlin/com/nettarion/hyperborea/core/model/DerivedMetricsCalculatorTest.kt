package com.nettarion.hyperborea.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DerivedMetricsCalculatorTest {

    private fun sample(power: Int? = null, heartRate: Int? = null) = WorkoutSample(
        timestampSeconds = 0,
        power = power,
        cadence = null,
        speedKph = null,
        heartRate = heartRate,
        resistance = null,
        incline = null,
        calories = null,
        distanceKm = null,
    )

    private fun summary(
        avgPower: Int? = null,
        maxPower: Int? = null,
        normalizedPower: Int? = null,
        avgHeartRate: Int? = null,
        calories: Int = 0,
        durationSeconds: Long = 3600,
    ) = RideSummary(
        id = 1,
        profileId = 1,
        startedAt = 0,
        durationSeconds = durationSeconds,
        distanceKm = 10f,
        calories = calories,
        avgPower = avgPower,
        maxPower = maxPower,
        normalizedPower = normalizedPower,
        avgHeartRate = avgHeartRate,
    )

    private fun profile(
        weightKg: Float? = null,
        ftpWatts: Int? = null,
        maxHeartRate: Int? = null,
    ) = Profile(
        name = "Test",
        weightKg = weightKg,
        ftpWatts = ftpWatts,
        maxHeartRate = maxHeartRate,
    )

    // --- Work ---

    @Test
    fun `work from known power samples`() {
        val samples = List(10) { sample(power = 200) }
        val result = computeDerivedMetrics(summary(), samples, null)
        assertThat(result.workKj).isWithin(0.01f).of(2.0f)
    }

    @Test
    fun `work null when no power samples`() {
        val samples = List(10) { sample() }
        val result = computeDerivedMetrics(summary(), samples, null)
        assertThat(result.workKj).isNull()
    }

    @Test
    fun `work handles mixed null and non-null power`() {
        val samples = listOf(sample(power = 500), sample(), sample(power = 500))
        val result = computeDerivedMetrics(summary(), samples, null)
        assertThat(result.workKj).isWithin(0.01f).of(1.0f)
    }

    // --- Variability Index ---

    @Test
    fun `variability index from NP and avgPower`() {
        val result = computeDerivedMetrics(
            summary(normalizedPower = 220, avgPower = 200), emptyList(), null,
        )
        assertThat(result.variabilityIndex).isWithin(0.01f).of(1.10f)
    }

    @Test
    fun `variability index null when NP missing`() {
        val result = computeDerivedMetrics(summary(avgPower = 200), emptyList(), null)
        assertThat(result.variabilityIndex).isNull()
    }

    @Test
    fun `variability index null when avgPower missing`() {
        val result = computeDerivedMetrics(summary(normalizedPower = 220), emptyList(), null)
        assertThat(result.variabilityIndex).isNull()
    }

    @Test
    fun `variability index null when avgPower zero`() {
        val result = computeDerivedMetrics(
            summary(normalizedPower = 220, avgPower = 0), emptyList(), null,
        )
        assertThat(result.variabilityIndex).isNull()
    }

    // --- Efficiency Factor ---

    @Test
    fun `efficiency factor from NP and avgHR`() {
        val result = computeDerivedMetrics(
            summary(normalizedPower = 200, avgHeartRate = 150), emptyList(), null,
        )
        assertThat(result.efficiencyFactor).isWithin(0.01f).of(1.33f)
    }

    @Test
    fun `efficiency factor null when NP missing`() {
        val result = computeDerivedMetrics(summary(avgHeartRate = 150), emptyList(), null)
        assertThat(result.efficiencyFactor).isNull()
    }

    @Test
    fun `efficiency factor null when avgHR missing`() {
        val result = computeDerivedMetrics(summary(normalizedPower = 200), emptyList(), null)
        assertThat(result.efficiencyFactor).isNull()
    }

    @Test
    fun `efficiency factor null when avgHR zero`() {
        val result = computeDerivedMetrics(
            summary(normalizedPower = 200, avgHeartRate = 0), emptyList(), null,
        )
        assertThat(result.efficiencyFactor).isNull()
    }

    // --- W/kg ---

    @Test
    fun `avg power per kg with weight`() {
        val result = computeDerivedMetrics(
            summary(avgPower = 200), emptyList(), profile(weightKg = 80f),
        )
        assertThat(result.avgPowerPerKg).isWithin(0.01f).of(2.50f)
    }

    @Test
    fun `max power per kg with weight`() {
        val result = computeDerivedMetrics(
            summary(maxPower = 400), emptyList(), profile(weightKg = 80f),
        )
        assertThat(result.maxPowerPerKg).isWithin(0.01f).of(5.0f)
    }

    @Test
    fun `power per kg null without weight`() {
        val result = computeDerivedMetrics(summary(avgPower = 200, maxPower = 400), emptyList(), null)
        assertThat(result.avgPowerPerKg).isNull()
        assertThat(result.maxPowerPerKg).isNull()
    }

    @Test
    fun `power per kg null without power`() {
        val result = computeDerivedMetrics(summary(), emptyList(), profile(weightKg = 80f))
        assertThat(result.avgPowerPerKg).isNull()
        assertThat(result.maxPowerPerKg).isNull()
    }

    // --- Cal/hr ---

    @Test
    fun `calories per hour for one hour ride`() {
        val result = computeDerivedMetrics(
            summary(calories = 600, durationSeconds = 3600), emptyList(), null,
        )
        assertThat(result.caloriesPerHour).isWithin(0.1f).of(600f)
    }

    @Test
    fun `calories per hour for half hour ride`() {
        val result = computeDerivedMetrics(
            summary(calories = 300, durationSeconds = 1800), emptyList(), null,
        )
        assertThat(result.caloriesPerHour).isWithin(0.1f).of(600f)
    }

    @Test
    fun `calories per hour null when duration zero`() {
        val result = computeDerivedMetrics(
            summary(calories = 600, durationSeconds = 0), emptyList(), null,
        )
        assertThat(result.caloriesPerHour).isNull()
    }

    // --- Power Zones ---

    @Test
    fun `power zones with known FTP`() {
        val ftp = 200
        val samples = listOf(
            sample(power = 100),  // Z1 (50% FTP)
            sample(power = 130),  // Z2 (65% FTP)
            sample(power = 170),  // Z3 (85% FTP)
            sample(power = 200),  // Z4 (100% FTP)
            sample(power = 220),  // Z5 (110% FTP)
            sample(power = 260),  // Z6 (130% FTP)
            sample(power = 320),  // Z7 (160% FTP)
        )
        val result = computeDerivedMetrics(summary(), samples, profile(ftpWatts = ftp))

        val zones = result.powerZones!!
        assertThat(zones.referenceValue).isEqualTo(200)
        assertThat(zones.referenceLabel).isEqualTo("FTP")
        assertThat(zones.zones).hasSize(7)
        assertThat(zones.zones[0].seconds).isEqualTo(1) // Z1
        assertThat(zones.zones[1].seconds).isEqualTo(1) // Z2
        assertThat(zones.zones[2].seconds).isEqualTo(1) // Z3
        assertThat(zones.zones[3].seconds).isEqualTo(1) // Z4
        assertThat(zones.zones[4].seconds).isEqualTo(1) // Z5
        assertThat(zones.zones[5].seconds).isEqualTo(1) // Z6
        assertThat(zones.zones[6].seconds).isEqualTo(1) // Z7
    }

    @Test
    fun `power zones null without FTP`() {
        val samples = listOf(sample(power = 200))
        val result = computeDerivedMetrics(summary(), samples, profile())
        assertThat(result.powerZones).isNull()
    }

    @Test
    fun `power zones null without power samples`() {
        val samples = listOf(sample())
        val result = computeDerivedMetrics(summary(), samples, profile(ftpWatts = 200))
        assertThat(result.powerZones).isNull()
    }

    @Test
    fun `power zone percentages sum to 100`() {
        val samples = List(100) { sample(power = (50..400).random()) }
        val result = computeDerivedMetrics(summary(), samples, profile(ftpWatts = 200))
        val totalPct = result.powerZones!!.zones.sumOf { it.percentage.toDouble() }
        assertThat(totalPct.toFloat()).isWithin(0.1f).of(100f)
    }

    // --- HR Zones ---

    @Test
    fun `hr zones with known maxHR`() {
        val maxHr = 200
        val samples = listOf(
            sample(heartRate = 100),  // Z1 (50%)
            sample(heartRate = 130),  // Z2 (65%)
            sample(heartRate = 150),  // Z3 (75%)
            sample(heartRate = 170),  // Z4 (85%)
            sample(heartRate = 190),  // Z5 (95%)
        )
        val result = computeDerivedMetrics(summary(), samples, profile(maxHeartRate = maxHr))

        val zones = result.hrZones!!
        assertThat(zones.referenceValue).isEqualTo(200)
        assertThat(zones.referenceLabel).isEqualTo("Max HR")
        assertThat(zones.zones).hasSize(5)
        assertThat(zones.zones[0].seconds).isEqualTo(1) // Z1
        assertThat(zones.zones[1].seconds).isEqualTo(1) // Z2
        assertThat(zones.zones[2].seconds).isEqualTo(1) // Z3
        assertThat(zones.zones[3].seconds).isEqualTo(1) // Z4
        assertThat(zones.zones[4].seconds).isEqualTo(1) // Z5
    }

    @Test
    fun `hr zones null without maxHR`() {
        val samples = listOf(sample(heartRate = 150))
        val result = computeDerivedMetrics(summary(), samples, profile())
        assertThat(result.hrZones).isNull()
    }

    @Test
    fun `hr zones null without hr samples`() {
        val samples = listOf(sample())
        val result = computeDerivedMetrics(summary(), samples, profile(maxHeartRate = 200))
        assertThat(result.hrZones).isNull()
    }

    @Test
    fun `hr zone percentages sum to 100`() {
        val samples = List(100) { sample(heartRate = (80..200).random()) }
        val result = computeDerivedMetrics(summary(), samples, profile(maxHeartRate = 200))
        val totalPct = result.hrZones!!.zones.sumOf { it.percentage.toDouble() }
        assertThat(totalPct.toFloat()).isWithin(0.1f).of(100f)
    }

    // --- Edge cases ---

    @Test
    fun `empty samples list returns all nulls except cal per hour`() {
        val result = computeDerivedMetrics(
            summary(calories = 600, durationSeconds = 3600), emptyList(), null,
        )
        assertThat(result.workKj).isNull()
        assertThat(result.variabilityIndex).isNull()
        assertThat(result.efficiencyFactor).isNull()
        assertThat(result.avgPowerPerKg).isNull()
        assertThat(result.maxPowerPerKg).isNull()
        assertThat(result.caloriesPerHour).isWithin(0.1f).of(600f)
        assertThat(result.powerZones).isNull()
        assertThat(result.hrZones).isNull()
    }

    @Test
    fun `null profile returns null for weight and zone metrics`() {
        val samples = listOf(sample(power = 200, heartRate = 150))
        val result = computeDerivedMetrics(
            summary(avgPower = 200, maxPower = 300), samples, null,
        )
        assertThat(result.avgPowerPerKg).isNull()
        assertThat(result.maxPowerPerKg).isNull()
        assertThat(result.powerZones).isNull()
        assertThat(result.hrZones).isNull()
        // work should still be computed
        assertThat(result.workKj).isNotNull()
    }
}
