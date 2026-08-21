package com.nettarion.hyperborea.hardware.fitpro.session

/**
 * A physical-console keypad press, abstracted away from the equipment-specific key codes (see
 * [FitProKeypad]). How a press takes effect is protocol-specific and handled inside the session
 * layer:
 *
 * - **FitPro V1**: the MCU acts directly on most keys. Treadmill Start is app-mediated when
 *   `REQUIRE_START_REQUESTED` is enabled: the controller reports the explicit press through
 *   `START_REQUESTED` (or, on some firmware, only through `KEY_OBJECT`) and the session answers by
 *   writing `WORKOUT_MODE=RUNNING`. This preserves the physical safety gate without writing a
 *   workout mode during connection.
 * - **FitPro V2**: the MCU is a forwarder — pressing [START] makes it report
 *   `WORKOUT_STATE=READY_TO_START` (and emit the key event), then it waits for the HOST to
 *   drive the workout state machine, mirroring the stock console-service's start-request
 *   handling. `V2Session.requestWorkoutStart` answers those triggers by writing the workout to
 *   RUNNING, and `V2Session.routeTreadmillKey` answers speed/incline/Stop on firmware that
 *   forwards keys without acting on them.
 *
 * Either way the orchestrator parks treadmills in `AwaitingConsoleStart` and promotes to Running
 * once telemetry reports the workout RUNNING.
 */
internal enum class ConsoleKey {
    START,
    STOP,
    RESISTANCE_UP,
    RESISTANCE_DOWN,
    INCLINE_UP,
    INCLINE_DOWN,
    SPEED_UP,
    SPEED_DOWN,
}
