package com.nettarion.hyperborea.ui.util

import kotlin.math.roundToInt

object UnitFormatter {

    const val KG_TO_LBS = 2.20462f
    const val CM_PER_INCH = 2.54f
    const val KM_TO_MI = 0.621371f
    const val M_TO_FT = 3.28084f

    fun weightDisplay(kg: Float, imperial: Boolean): String =
        if (imperial) "%.0f lbs".format(kg * KG_TO_LBS)
        else "%.1f kg".format(kg)

    fun weightEditDisplay(kg: Float, imperial: Boolean): String =
        if (imperial) "%.1f".format(kg * KG_TO_LBS)
        else kg.toString()

    fun heightDisplay(cm: Int, imperial: Boolean): String =
        if (imperial) {
            val totalIn = (cm / CM_PER_INCH).toInt()
            "${totalIn / 12}'${totalIn % 12}\""
        } else "${cm} cm"

    fun heightEditFields(cm: Int, imperial: Boolean): Pair<String, String> =
        if (imperial) {
            val totalInches = (cm / CM_PER_INCH).roundToInt()
            (totalInches / 12).toString() to (totalInches % 12).toString()
        } else cm.toString() to ""

    fun parseWeightToKg(value: Float, imperial: Boolean): Float =
        if (imperial) value / KG_TO_LBS else value

    fun parseHeightToCm(feet: Int, inches: Int): Int =
        ((feet * 12 + inches) * CM_PER_INCH).roundToInt()

    fun distanceDisplay(km: Float, imperial: Boolean): String =
        if (imperial) "%.1f mi".format(km * KM_TO_MI)
        else "%.1f km".format(km)

    fun speedDisplay(kph: Float, imperial: Boolean): String =
        if (imperial) "%.1f mph".format(kph * KM_TO_MI)
        else "%.1f km/h".format(kph)

    fun elevationDisplay(meters: Float, imperial: Boolean): String =
        if (imperial) "%.0f ft".format(meters * M_TO_FT)
        else "%.0f m".format(meters)
}
