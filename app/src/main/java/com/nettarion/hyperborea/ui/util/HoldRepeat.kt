package com.nettarion.hyperborea.ui.util

/**
 * Auto-repeat timing for the press-and-hold − / + controls, shared by the dashboard's status-bar
 * clusters and the floating control-bar overlay so both surfaces feel identical.
 *
 * A flat repeat rate can't serve both jobs a treadmill's ± keys have. Belt speed steps in
 * 0.1 km/h (the controller's own grid), so the first repeats have to stay slow enough to stop on
 * an exact pace — but at a flat 350 ms, crossing a 20 km/h range would take 200 presses, over a
 * minute of holding. Starting slow and accelerating gives both: tap for one step, a short hold
 * trims a pace, a long hold sweeps the range in about the same ten-odd seconds the 0.5-step
 * incline cluster already took.
 *
 * Every repeat is still exactly one step — the ramp changes only how often they arrive — so a
 * held button keeps its one-press-one-step meaning, and the incline path's write coalescer still
 * collapses the burst into one moving target per interval on the wire.
 */
object HoldRepeat {

    /** Delay between the initial tap and the first auto-repeat — the "this is a hold" threshold. */
    const val INITIAL_MS = 500L

    /** Interval for the first auto-repeat, before the ramp starts biting. */
    const val START_INTERVAL_MS = 350L

    /** Floor the ramp accelerates to and then holds. */
    const val MIN_INTERVAL_MS = 60L

    /** Repeats taken to ramp [START_INTERVAL_MS] down to [MIN_INTERVAL_MS] (~2.5 s of holding). */
    const val RAMP_TICKS = 12

    /** Interval to wait *after* the [tick]-th auto-repeat (0-based). */
    fun intervalMs(tick: Int): Long {
        if (tick >= RAMP_TICKS) return MIN_INTERVAL_MS
        val span = START_INTERVAL_MS - MIN_INTERVAL_MS
        return START_INTERVAL_MS - span * tick / RAMP_TICKS
    }
}
