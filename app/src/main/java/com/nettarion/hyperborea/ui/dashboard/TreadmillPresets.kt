package com.nettarion.hyperborea.ui.dashboard

import com.nettarion.hyperborea.ui.util.UnitFormatter

/**
 * Quick-set values for the treadmill dashboard's preset columns (Technogym-style). Pure Kotlin so
 * the derivation rules are unit-testable.
 */
internal object TreadmillPresets {

    data class SpeedPreset(val display: String, val kph: Float)

    /**
     * Incline quick-set values derived from the machine's real range, descending: the max, its
     * two thirds and one third (each rounded to a round number), 0, and — when the deck
     * declines — the negative minimum. E.g. −3..15 → [15, 10, 5, 0, −3]; 0..12 → [12, 8, 4, 0];
     * −6..40 → [40, 25, 15, 0, −6].
     */
    fun inclinePresets(minIncline: Float, maxIncline: Float): List<Float> {
        val presets = mutableListOf<Float>()
        if (maxIncline > 0f) {
            // Round intermediate rungs to whole percents on ordinary decks, fives on the tall
            // incline-trainer ranges.
            val increment = if (maxIncline <= 15f) 1f else 5f
            presets.add(maxIncline)
            presets.add(roundTo(maxIncline * 2f / 3f, increment))
            presets.add(roundTo(maxIncline / 3f, increment))
        }
        presets.add(0f)
        if (minIncline < 0f) presets.add(minIncline)
        return presets.filter { it == 0f || it == maxIncline || it == minIncline || (it > 0f && it < maxIncline) }
            .distinct()
    }

    /**
     * Speed quick-set values in the user's display unit — whole "nice" numbers like the reference
     * console (metric 4/6/8/10 km/h; imperial 2/4/6/8 mph) — capped to the machine's max speed and
     * sent as the exact km/h equivalent. Descending (fastest on top).
     */
    fun speedPresets(maxSpeedKph: Float, imperial: Boolean): List<SpeedPreset> {
        return if (imperial) {
            MPH_LADDER
                .map { mph -> SpeedPreset(formatValue(mph), mph / UnitFormatter.KM_TO_MI) }
                .filter { it.kph <= maxSpeedKph }
                .sortedByDescending { it.kph }
        } else {
            KPH_LADDER
                .map { kph -> SpeedPreset(formatValue(kph), kph) }
                .filter { it.kph <= maxSpeedKph }
                .sortedByDescending { it.kph }
        }
    }

    private fun roundTo(value: Float, increment: Float): Float =
        Math.round(value / increment) * increment

    /** Whole numbers render bare ("15"), fractional values with one decimal ("−2.5"). */
    fun formatValue(value: Float): String =
        if (value == value.toLong().toFloat()) value.toInt().toString() else "%.1f".format(value)

    // Walking → brisk → jog → run ranges, matching the Technogym reference's whole numbers.
    private val KPH_LADDER = listOf(4f, 6f, 8f, 10f, 12f)
    private val MPH_LADDER = listOf(2f, 4f, 6f, 8f)
}
