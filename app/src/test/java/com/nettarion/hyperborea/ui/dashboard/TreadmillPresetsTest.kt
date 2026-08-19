package com.nettarion.hyperborea.ui.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TreadmillPresetsTest {

    @Test
    fun `incline presets for a -3 to 15 deck`() {
        assertThat(TreadmillPresets.inclinePresets(minIncline = -3f, maxIncline = 15f))
            .containsExactly(15f, 10f, 5f, 0f, -3f).inOrder()
    }

    @Test
    fun `incline presets for a 0 to 12 deck have no negative entry`() {
        assertThat(TreadmillPresets.inclinePresets(minIncline = 0f, maxIncline = 12f))
            .containsExactly(12f, 8f, 4f, 0f).inOrder()
    }

    @Test
    fun `incline presets for a tall incline-trainer range round to fives`() {
        assertThat(TreadmillPresets.inclinePresets(minIncline = -6f, maxIncline = 40f))
            .containsExactly(40f, 25f, 15f, 0f, -6f).inOrder()
    }

    @Test
    fun `incline presets cap at five entries and stay distinct`() {
        val presets = TreadmillPresets.inclinePresets(minIncline = -3f, maxIncline = 15f)
        assertThat(presets.size).isAtMost(5)
        assertThat(presets).containsNoDuplicates()
    }

    @Test
    fun `incline presets for a flat-only deck are just zero`() {
        assertThat(TreadmillPresets.inclinePresets(minIncline = 0f, maxIncline = 0f))
            .containsExactly(0f)
    }

    @Test
    fun `metric speed presets are the whole-kph ladder capped at maxSpeed, descending`() {
        val presets = TreadmillPresets.speedPresets(maxSpeedKph = 20f, imperial = false)
        assertThat(presets.map { it.display }).containsExactly("12", "10", "8", "6", "4").inOrder()
        assertThat(presets.map { it.kph }).containsExactly(12f, 10f, 8f, 6f, 4f).inOrder()
    }

    @Test
    fun `metric speed presets drop rungs above a low maxSpeed`() {
        val presets = TreadmillPresets.speedPresets(maxSpeedKph = 9f, imperial = false)
        assertThat(presets.map { it.kph }).containsExactly(8f, 6f, 4f).inOrder()
    }

    @Test
    fun `imperial speed presets show whole mph and carry exact kph targets`() {
        val presets = TreadmillPresets.speedPresets(maxSpeedKph = 20f, imperial = true)
        assertThat(presets.map { it.display }).containsExactly("8", "6", "4", "2").inOrder()
        // 8 mph ≈ 12.87 km/h — the sent target is the exact conversion, not a rounded kph.
        assertThat(presets[0].kph).isWithin(0.01f).of(12.87f)
    }

    @Test
    fun `imperial speed presets respect maxSpeed after conversion`() {
        // 10 km/h max: 8 mph (12.9 km/h) must be dropped; 6 mph (9.7) fits.
        val presets = TreadmillPresets.speedPresets(maxSpeedKph = 10f, imperial = true)
        assertThat(presets.map { it.display }).containsExactly("6", "4", "2").inOrder()
    }

    @Test
    fun `format renders whole numbers bare and fractions with one decimal`() {
        assertThat(TreadmillPresets.formatValue(15f)).isEqualTo("15")
        assertThat(TreadmillPresets.formatValue(-3f)).isEqualTo("-3")
        assertThat(TreadmillPresets.formatValue(2.5f)).isEqualTo("2.5")
    }
}
