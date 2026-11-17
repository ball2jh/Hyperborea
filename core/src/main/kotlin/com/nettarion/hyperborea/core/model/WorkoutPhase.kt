package com.nettarion.hyperborea.core.model

/**
 * The console's workout state, as carried in [ExerciseData.workoutMode].
 *
 * The [code]s are the FitPro V1 `WORKOUT_MODE` values — V1 is the older protocol, so its numbering
 * became the lingua franca: V2 sessions translate their own state ordinals into these before
 * publishing, and everything downstream (the orchestrator's workout-mode monitor, FTMS Training
 * Status, the belt-pause latch) reads them through this one type instead of re-declaring the
 * numbers.
 */
enum class WorkoutPhase(val code: Int) {
    UNKNOWN(0),
    IDLE(1),
    RUNNING(2),
    PAUSE(3),
    RESULTS(4),

    /** Safety key removed ("dead man's key"). V2's `OFF_MACHINE` maps here too. */
    DMK(8),
    WARM_UP(10),
    COOL_DOWN(11),
    ;

    companion object {
        /**
         * The phase for a raw [ExerciseData.workoutMode], or null for a missing value or a code
         * outside the known set — callers decide what an unrecognised console state means, so it
         * is deliberately not folded into [UNKNOWN].
         */
        fun fromCode(code: Int?): WorkoutPhase? = entries.firstOrNull { it.code == code }
    }
}
