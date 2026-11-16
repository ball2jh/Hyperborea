package com.nettarion.hyperborea.core.orchestration

import com.nettarion.hyperborea.core.AppLogger

/**
 * Translates the console's polled WORKOUT_MODE into orchestrator actions:
 *
 *  - RUNNING while armed ([OrchestratorState.AwaitingConsoleStart]) → promote to Running (the
 *    user pressed the physical Start key and the protocol session/MCU completed the transition).
 *  - DMK while Running → pause (safety key removed). From AwaitingConsoleStart it's ignored —
 *    the console's own "INSERT SAFETY KEY" hardware indicator covers that case.
 *  - IDLE from Paused → resume (safety key re-inserted); from Running/armed → stop (the user
 *    pressed the physical Stop key; symmetrical with app-side Stop).
 *
 * Stateful in one way: belt machines can remain idle until the physical Start key is pressed. An
 * idle report while armed is therefore the normal parked state, not the user pressing Stop, so
 * stopping is gated on the console having confirmed leaving idle at least once since [reset].
 */
internal class WorkoutModeInterpreter(private val logger: AppLogger) {

    sealed interface Action {
        data object PromoteToRunning : Action
        data object PauseFromConsole : Action
        data object ResumeFromConsole : Action
        data object PauseForSafetyKey : Action
        data object ResumeFromSafetyKey : Action
        data object PauseTimedOut : Action
        data class StopWorkout(val wasRunning: Boolean) : Action
        data object None : Action
    }

    private var consoleLeftIdle = false
    private var pausedBySafetyKey = false
    private var pauseTimedOut = false

    /** Forgets state flags. Call each time a session arms. */
    fun reset() {
        consoleLeftIdle = false
        pausedBySafetyKey = false
        pauseTimedOut = false
    }

    fun onUserPause() {
        pausedBySafetyKey = false
        pauseTimedOut = false
    }

    fun interpret(workoutMode: Int?, state: OrchestratorState): Action {
        if (workoutMode != null &&
            workoutMode != WORKOUT_MODE_IDLE && workoutMode != WORKOUT_MODE_UNKNOWN) {
            consoleLeftIdle = true
        }
        return when (workoutMode) {
            WORKOUT_MODE_RUNNING -> {
                pauseTimedOut = false
                when (state) {
                    is OrchestratorState.AwaitingConsoleStart -> Action.PromoteToRunning
                    is OrchestratorState.Paused -> {
                        pausedBySafetyKey = false
                        Action.ResumeFromConsole
                    }
                    else -> Action.None
                }
            }
            WORKOUT_MODE_PAUSE -> {
                pauseTimedOut = false
                if (state is OrchestratorState.Running) {
                    pausedBySafetyKey = false
                    Action.PauseFromConsole
                } else {
                    Action.None
                }
            }
            WORKOUT_MODE_DMK ->
                if (state is OrchestratorState.Running) {
                    pausedBySafetyKey = true
                    Action.PauseForSafetyKey
                } else if (state is OrchestratorState.Paused) {
                    pausedBySafetyKey = true
                    Action.None
                } else {
                    Action.None
                }
            WORKOUT_MODE_RESULTS, WORKOUT_MODE_IDLE -> when (state) {
                is OrchestratorState.Paused ->
                    if (workoutMode == WORKOUT_MODE_IDLE && pausedBySafetyKey) {
                        pausedBySafetyKey = false
                        Action.ResumeFromSafetyKey
                    } else if (!pauseTimedOut) {
                        pauseTimedOut = true
                        Action.PauseTimedOut
                    } else {
                        Action.None
                    }
                is OrchestratorState.Running ->
                    if (pauseTimedOut) {
                        // A resume was just requested after a pause timeout; ignore trailing
                        // RESULTS/IDLE polls while the console transitions back to RUNNING.
                        Action.None
                    } else {
                        Action.StopWorkout(wasRunning = true)
                    }
                is OrchestratorState.AwaitingConsoleStart ->
                    if (workoutMode == WORKOUT_MODE_IDLE && consoleLeftIdle) {
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
        const val WORKOUT_MODE_PAUSE = 3
        const val WORKOUT_MODE_RESULTS = 4
        const val WORKOUT_MODE_DMK = 8
    }
}
