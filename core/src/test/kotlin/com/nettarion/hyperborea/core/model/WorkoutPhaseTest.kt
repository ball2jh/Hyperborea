package com.nettarion.hyperborea.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkoutPhaseTest {

    @Test
    fun `codes are the FitPro V1 WORKOUT_MODE values`() {
        // These numbers are on the wire (V1) and persisted in ExerciseData.workoutMode; pin them.
        assertThat(WorkoutPhase.entries.associate { it to it.code }).containsExactly(
            WorkoutPhase.UNKNOWN, 0,
            WorkoutPhase.IDLE, 1,
            WorkoutPhase.RUNNING, 2,
            WorkoutPhase.PAUSE, 3,
            WorkoutPhase.RESULTS, 4,
            WorkoutPhase.DMK, 8,
            WorkoutPhase.WARM_UP, 10,
            WorkoutPhase.COOL_DOWN, 11,
        )
    }

    @Test
    fun `fromCode round-trips every phase`() {
        for (phase in WorkoutPhase.entries) {
            assertThat(WorkoutPhase.fromCode(phase.code)).isEqualTo(phase)
        }
    }

    @Test
    fun `fromCode keeps a missing or unlisted code distinct from UNKNOWN`() {
        assertThat(WorkoutPhase.fromCode(null)).isNull()
        assertThat(WorkoutPhase.fromCode(5)).isNull()
        assertThat(WorkoutPhase.fromCode(-1)).isNull()
    }
}
