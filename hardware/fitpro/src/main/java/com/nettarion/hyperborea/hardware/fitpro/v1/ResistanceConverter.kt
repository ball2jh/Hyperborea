package com.nettarion.hyperborea.hardware.fitpro.v1

import kotlin.math.ceil

/**
 * Converts between a resistance *level* (1…[maxResistance], what the UI and fitness apps speak) and
 * the *raw* value the FitPro [V1DataField.RESISTANCE] bitfield carries, exactly as the stock
 * firmware's resistance converter does: the scale is **integer** division `10000 / maxResistance`,
 * `raw = (level × scale) − 1` clamped at ≥ 0 (truncating, no rounding), and `level = ceil(raw ÷ scale)`.
 * Using an integer scale + truncation matters — a double scale with rounding commands a raw value a
 * few steps higher than the console's own software would for a maxResistance that doesn't divide 10000.
 */
class ResistanceConverter(maxResistance: Int) {
    private val scale: Int = if (maxResistance > 0) (10_000 / maxResistance).coerceAtLeast(1) else 1

    fun levelToRaw(level: Int): Int = maxOf(0, level * scale - 1)

    fun rawToLevel(raw: Int): Int = ceil(raw.toDouble() / scale).toInt()
}
