package com.nettarion.hyperborea.core.model

/**
 * A physical-console keypad press, abstracted away from the equipment-specific key codes. The
 * hardware adapter exposes a stream of these — one per press — on
 * [com.nettarion.hyperborea.core.adapter.HardwareAdapter.consoleKeyPresses].
 *
 * This stream is observational — nothing in the core pipeline drives the hardware from it; it
 * exists for UI/diagnostics. How a press takes effect is protocol-specific and handled inside
 * the session layer:
 *
 * - **FitPro V1**: the MCU acts on its keys directly (resistance/incline/speed, and the workout
 *   state machine on START/STOP); the new state flows up through normal polling. On a V1
 *   treadmill the MCU itself gates belt motion on the physical [START] key — writing
 *   `WORKOUT_MODE=RUNNING` from the app alone will not move the belt; the MCU needs both the
 *   mode write AND a Start-key press.
 * - **FitPro V2**: the MCU is a forwarder — pressing [START] makes it report
 *   `WORKOUT_STATE=READY_TO_START` (and emit the key event), then it waits for the HOST to
 *   drive the workout state machine, mirroring the stock GlassOS service's `START_REQUESTED`
 *   handling. `V2Session.requestWorkoutStart` answers those triggers by writing the workout to
 *   RUNNING.
 *
 * Either way the orchestrator parks treadmills in
 * [com.nettarion.hyperborea.core.orchestration.OrchestratorState.AwaitingConsoleStart] and
 * promotes to Running once telemetry reports the workout RUNNING.
 */
enum class ConsoleKey {
    START,
    STOP,
    RESISTANCE_UP,
    RESISTANCE_DOWN,
    INCLINE_UP,
    INCLINE_DOWN,
    SPEED_UP,
    SPEED_DOWN,
}
