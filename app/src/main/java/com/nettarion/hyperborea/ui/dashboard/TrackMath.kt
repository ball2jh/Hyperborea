package com.nettarion.hyperborea.ui.dashboard

/**
 * Lap arithmetic for the 400 m running-track widget. Pure Kotlin so the wrap-around edge cases
 * are unit-testable without Compose.
 */
internal object TrackMath {
    const val LAP_METERS = 400f

    // Standard outdoor 400 m track (inner measuring line): two 84.39 m straightaways joined by
    // two semicircular curves of 36.50 m radius.
    const val STRAIGHT_METERS = 84.39f
    const val CURVE_RADIUS_METERS = 36.50f

    /**
     * Width : height of the track's bounding box — (straight + two curve radii) : (curve
     * diameter) ≈ 2.156 — so the drawn stadium always has the real track's proportions
     * regardless of the canvas shape.
     */
    const val TRACK_ASPECT_RATIO = (STRAIGHT_METERS + 2f * CURVE_RADIUS_METERS) / (2f * CURVE_RADIUS_METERS)

    /** Whole 400 m laps completed. 0 for null/negative distance. */
    fun completedLaps(distanceKm: Float?): Int {
        val meters = metersOrZero(distanceKm)
        return (meters / LAP_METERS).toInt()
    }

    /** Position within the current lap as a fraction in [0, 1). 0 for null/negative distance. */
    fun lapProgress(distanceKm: Float?): Float {
        val meters = metersOrZero(distanceKm)
        val progress = (meters % LAP_METERS) / LAP_METERS
        // Float rounding can land exactly on 1.0 (e.g. 0.79999995 km); keep the contract [0, 1).
        return if (progress >= 1f) 0f else progress
    }

    /**
     * Monotonic laps-plus-fraction value (e.g. 2.37 = lap 3, 37% around). Animating this and
     * taking `% 1` for the drawn fraction avoids a backwards sweep at each 400 m boundary.
     */
    fun continuousLaps(distanceKm: Float?): Float = metersOrZero(distanceKm) / LAP_METERS

    private fun metersOrZero(distanceKm: Float?): Float {
        if (distanceKm == null || distanceKm <= 0f) return 0f
        return distanceKm * 1000f
    }
}
