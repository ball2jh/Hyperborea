package com.nettarion.hyperborea.hardware.fitpro.session

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The protocol-neutral scaffold shared by the V1 and V2 sessions: the published state flows, the
 * per-session coroutine scope, console-keypad edge detection, the target-accumulation state for
 * relative adjust commands, grip-HR filtering, and the throttled telemetry log. Subclasses supply
 * the protocol work — handshake, encode/decode, polling vs event-driven receive, and teardown.
 *
 * Sessions are **single-use**: the adapter builds a fresh instance per connect/identify/calibrate,
 * and [stop] cancels [sessionScope] for good.
 */
internal abstract class BaseFitProSession(
    protected val transport: HidTransport,
    protected val logger: AppLogger,
    parentScope: CoroutineScope,
    protected val deviceInfo: DeviceInfo,
    protected val accumulator: ExerciseDataAccumulator,
    private val tag: String,
) : FitProSession {

    protected val _exerciseData = MutableStateFlow<ExerciseData?>(null)
    final override val exerciseData: StateFlow<ExerciseData?> = _exerciseData.asStateFlow()

    protected val _deviceIdentity = MutableStateFlow<DeviceIdentity?>(null)
    final override val deviceIdentity: StateFlow<DeviceIdentity?> = _deviceIdentity.asStateFlow()

    protected val _sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected)
    final override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    protected val _degradedReason = MutableStateFlow<String?>(null)
    final override val degradedReason: StateFlow<String?> = _degradedReason.asStateFlow()

    /**
     * Every coroutine a session launches (poll/receive loops, start-request drives, host-routed
     * key writes) lives here — a child of the adapter's scope, so app-wide cancellation still
     * reaches it, but cancellable wholesale in [stop] so no stray write can race a closing
     * transport. Supervisor: a crashed child must not take the adapter's shared scope down.
     */
    protected val sessionScope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /**
     * Equipment type of the connected machine, resolved during [start] from the protocol's own
     * signal (V1: the MCU's equipment id in the handshake; V2: the console's device-type report or
     * the supported-features heuristic). The constructor's [deviceInfo] only knows the USB product
     * id and defaults to BIKE, so it cannot be trusted for type-dependent bring-up decisions.
     * The adapter reads this back after [start] to refine the public DeviceInfo.
     */
    final override var detectedDeviceType: DeviceType = DeviceType.BIKE
        protected set

    // Accumulated absolute targets behind the relative Adjust± commands: one press = one step from
    // the last value WE sent (not the console's read-back, which may self-step on some units).
    protected var lastSentGrade = 0f
    protected var lastSentSpeed = 0f

    /** Grip HR is a noisy analog contact reading — both protocols gate + smooth it with this. */
    protected val gripHeartRate = GripHeartRateFilter()

    private var lastKeyCode = 0
    private var lastLogTimeMs = 0L

    /**
     * Console-keypad edge detection, shared verbatim by both protocols (same keypad firmware,
     * different wire fields): reports repeat the *currently-pressed* code (0 = no key), so act
     * only when the code changes to a new non-zero value, mapping it via [FitProKeypad] and
     * handing fresh presses to [onConsoleKeyPressed].
     */
    protected fun onKeypadCode(code: Int, heldMs: Int? = null) {
        if (code == lastKeyCode) return
        lastKeyCode = code
        if (code == 0) return
        val key = FitProKeypad.consoleKeyFromCode(code)
        logger.d(tag, "Console keypad: code=$code${heldMs?.let { " held=${it}ms" } ?: ""}${key?.let { " ($it)" } ?: ""}")
        if (key != null) onConsoleKeyPressed(key)
    }

    /** What a fresh press does is protocol-specific; the default is observe-only (V1's MCU self-acts). */
    protected open fun onConsoleKeyPressed(key: ConsoleKey) {}

    /** Logs the current telemetry snapshot at most once per second — both receive paths call this per sample. */
    protected fun logTelemetryThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastLogTimeMs < TELEMETRY_LOG_INTERVAL_MS) return
        lastLogTimeMs = now
        val snap = _exerciseData.value ?: return
        logger.d(tag, "power=${snap.power}W cadence=${snap.cadence}rpm speed=${snap.speed}kph resistance=${snap.resistance} incline=${snap.incline}%")
    }

    protected fun roundToStep(value: Float, step: Float): Float =
        (value / step).roundToInt() * step

    /** Steps the accumulated incline target by one [DeviceInfo.inclineStep], clamped to the device's range. */
    protected fun nextAdjustedGrade(increase: Boolean): Float {
        lastSentGrade += if (increase) deviceInfo.inclineStep else -deviceInfo.inclineStep
        lastSentGrade = lastSentGrade.coerceIn(deviceInfo.minIncline, deviceInfo.maxIncline)
        return lastSentGrade
    }

    /** Steps the accumulated speed target by one [DeviceInfo.speedStep], clamped to 0..maxSpeed. */
    protected fun nextAdjustedSpeed(increase: Boolean): Float {
        lastSentSpeed += if (increase) deviceInfo.speedStep else -deviceInfo.speedStep
        lastSentSpeed = lastSentSpeed.coerceIn(0f, deviceInfo.maxSpeed)
        return lastSentSpeed
    }

    /** Call as the last step of [stop]: no session coroutine survives past teardown. */
    protected fun cancelSessionScope() {
        sessionScope.cancel()
    }

    companion object {
        /** Degraded reason shared by both protocols' bring-up when the console never confirms RUNNING. */
        const val WORKOUT_NOT_CONFIRMED_REASON =
            "The console didn't confirm the workout started — resistance/speed may not respond"
        private const val TELEMETRY_LOG_INTERVAL_MS = 1000L
    }
}
