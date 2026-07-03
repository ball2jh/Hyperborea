package com.nettarion.hyperborea.core.orchestration

import com.nettarion.hyperborea.core.AppLogger

/**
 * Translates the console's polled WORKOUT_MODE into orchestrator actions:
 *
 *  - RUNNING while armed ([OrchestratorState.AwaitingConsoleStart]) → promote to Running (the
 *    user pressed the physical Start key; the MCU completed the WARM_UP → RUNNING transition).
 *  - DMK while Running → pause (safety key removed). From AwaitingConsoleStart it's ignored —
 *    the console's own "INSERT SAFETY KEY" hardware indicator covers that case.
 *  - IDLE from Paused → resume (safety key re-inserted); from Running/armed → stop (the user
 *    pressed the physical Stop key; symmetrical with app-side Stop).
 *
 * Stateful in one way: some belt machines refuse the app's WARM_UP write outright and keep
 * reporting idle until the physical Start key is pressed — for those, an idle report while armed
 * is the normal parked state, not the user pressing Stop, so stopping is gated on the console
 * having confirmed leaving idle at least once since [reset].
 */
internal class WorkoutModeInterpreter(private val logger: AppLogger) {

    sealed interface Action {
        data object PromoteToRunning : Action
        data object PauseForSafetyKey : Action
        data object ResumeFromSafetyKey : Action
        data class StopWorkout(val wasRunning: Boolean) : Action
        data object None : Action
    }

    private var consoleLeftIdle = false

    /** Forgets the console-left-idle confirmation. Call each time a session arms. */
    fun reset() {
        consoleLeftIdle = false
    }

    fun interpret(workoutMode: Int?, state: OrchestratorState): Action {
        if (workoutMode != null &&
            workoutMode != WORKOUT_MODE_IDLE && workoutMode != WORKOUT_MODE_UNKNOWN) {
            consoleLeftIdle = true
        }
        return when (workoutMode) {
            WORKOUT_MODE_RUNNING ->
                if (state is OrchestratorState.AwaitingConsoleStart) Action.PromoteToRunning
                else Action.None
            WORKOUT_MODE_DMK ->
                if (state is OrchestratorState.Running) Action.PauseForSafetyKey
                else Action.None
            WORKOUT_MODE_IDLE -> when (state) {
                is OrchestratorState.Paused -> Action.ResumeFromSafetyKey
                is OrchestratorState.Running -> Action.StopWorkout(wasRunning = true)
                is OrchestratorState.AwaitingConsoleStart ->
                    if (consoleLeftIdle) {
                        Action.StopWorkout(wasRunning = false)
                    } else {
                        logger.d(TAG, "Idle report while armed (console never left idle) — staying armed")
                        Action.None
                    }
                else -> Action.None
            }
            else -> Action.None
        }
    }

    companion object {
        private const val TAG = "WorkoutModeInterpreter"
        const val WORKOUT_MODE_UNKNOWN = 0
        const val WORKOUT_MODE_IDLE = 1
        const val WORKOUT_MODE_RUNNING = 2
        const val WORKOUT_MODE_DMK = 8
    }
}
