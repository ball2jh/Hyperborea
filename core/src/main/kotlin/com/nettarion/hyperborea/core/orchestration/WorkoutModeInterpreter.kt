package com.nettarion.hyperborea.core.orchestration

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.DeviceType

/**
 * Translates the console's polled WORKOUT_MODE into orchestrator actions:
 *
 *  - RUNNING while armed ([OrchestratorState.AwaitingConsoleStart]) → promote to Running (the
 *    user pressed the physical Start key and the protocol session/MCU completed the transition).
 *  - DMK while Running → pause (safety key removed). From AwaitingConsoleStart it's ignored —
 *    the console's own "INSERT SAFETY KEY" hardware indicator covers that case.
 *  - IDLE/RESULTS from a paused treadmill → stay paused. Pause expiry and safety-key
 *    reinsertion never end the retained workout or authorize restarting a belt. Non-belt equipment retains its existing
 *    safety-key recovery. From Running/armed, IDLE stops the workout.
 *
 * Stateful in one way: belt machines can remain idle until the physical Start key is pressed. An
 * idle report while armed is therefore the normal parked state, not the user pressing Stop, so
 * stopping is gated on the console having confirmed leaving idle at least once since [reset].
 */
internal class WorkoutModeInterpreter(private val logger: AppLogger) {

    sealed interface Action {
        data object PromoteToRunning : Action
        data object PauseFromConsole : Action
        data object ResumeFromSafetyKey : Action
        data class StopWorkout(val wasRunning: Boolean) : Action
        data object None : Action
    }

    private var consoleLeftIdle = false

    /** Forgets the console-left-idle confirmation. Call each time a session arms. */
    fun reset() {
        consoleLeftIdle = false
    }

    fun interpret(workoutMode: Int?, state: OrchestratorState, deviceType: DeviceType?): Action {
        if (workoutMode != null &&
            workoutMode != WORKOUT_MODE_IDLE && workoutMode != WORKOUT_MODE_UNKNOWN && workoutMode != WORKOUT_MODE_RESULTS) {
            consoleLeftIdle = true
        }
        return when (workoutMode) {
            WORKOUT_MODE_RUNNING ->
                if (state is OrchestratorState.AwaitingConsoleStart) Action.PromoteToRunning
                else Action.None
            WORKOUT_MODE_PAUSE ->
                if (state is OrchestratorState.Running && deviceType == DeviceType.TREADMILL) Action.PauseFromConsole
                else Action.None
            WORKOUT_MODE_DMK ->
                if (state is OrchestratorState.Running) Action.PauseFromConsole
                else Action.None
            WORKOUT_MODE_RESULTS -> if (deviceType == DeviceType.TREADMILL && state is OrchestratorState.Running) {
                Action.StopWorkout(wasRunning = true)
            } else Action.None
            WORKOUT_MODE_IDLE -> when (state) {
                is OrchestratorState.Paused -> if (deviceType == null || deviceType == DeviceType.TREADMILL) {
                    Action.None
                } else Action.ResumeFromSafetyKey
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
        const val WORKOUT_MODE_PAUSE = 3
        const val WORKOUT_MODE_RESULTS = 4
        const val WORKOUT_MODE_DMK = 8
    }
}
