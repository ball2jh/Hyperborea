package com.nettarion.hyperborea.ui.dashboard

/**
 * Lap arithmetic for the running-track widget. Pure Kotlin so the wrap-around edge cases are
 * unit-testable without Compose.
 *
 * The lap length follows the rider's units: a 400 m lap in metric, a quarter-mile (440 yd) lap in
 * imperial. A 400 m lap is 2.3 m short of a quarter mile, so counting 400 m laps against a
 * distance shown in miles drifts — by lap 10 the lap rolls over at 2.49 mi instead of 2.50, and
 * the gap keeps growing. With the quarter-mile lap every fourth lap is exactly a mile.
 */
internal object TrackMath {
    const val LAP_METERS = 400f

    /** A quarter mile — the 440-yard lap of an imperial track. */
    const val QUARTER_MILE_METERS = 402.336f

    fun lapMeters(imperial: Boolean): Float = if (imperial) QUARTER_MILE_METERS else LAP_METERS

    // Standard outdoor 400 m track measuring line: two 84.39 m straightaways joined by two
    // semicircular curves. The measuring line is 30 cm outside the 36.50 m inner curb, so its
    // effective curve radius is 36.80 m. These only set the *drawn* proportions (the quarter-mile
    // lap reuses the same outline); lap counting never depends on them.
    const val STRAIGHT_METERS = 84.39f
    const val CURVE_RADIUS_METERS = 36.80f

    /**
     * Width : height of the track's bounding box — (straight + two curve radii) : (curve
     * diameter) ≈ 2.156 — so the drawn stadium always has the real track's proportions
     * regardless of the canvas shape.
     */
    const val TRACK_ASPECT_RATIO = (STRAIGHT_METERS + 2f * CURVE_RADIUS_METERS) / (2f * CURVE_RADIUS_METERS)

    /** Whole laps completed. 0 for null/negative distance. */
    fun completedLaps(distanceKm: Float?, imperial: Boolean = false): Int {
        val meters = metersOrZero(distanceKm)
        return (meters / lapMeters(imperial)).toInt()
    }

    /** Position within the current lap as a fraction in [0, 1). 0 for null/negative distance. */
    fun lapProgress(distanceKm: Float?, imperial: Boolean = false): Float {
        val meters = metersOrZero(distanceKm)
        val lap = lapMeters(imperial)
        val progress = (meters % lap) / lap
        // Float rounding can land exactly on 1.0 (e.g. 0.79999995 km); keep the contract [0, 1).
        return if (progress >= 1f) 0f else progress
    }

    /**
     * Monotonic laps-plus-fraction value (e.g. 2.37 = lap 3, 37% around). Animating this and
     * taking `% 1` for the drawn fraction avoids a backwards sweep at each lap boundary.
     */
    fun continuousLaps(distanceKm: Float?, imperial: Boolean = false): Float =
        metersOrZero(distanceKm) / lapMeters(imperial)

    private fun metersOrZero(distanceKm: Float?): Float {
        if (distanceKm == null || distanceKm <= 0f) return 0f
        return distanceKm * 1000f
    }
}
