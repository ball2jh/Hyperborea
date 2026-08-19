package com.nettarion.hyperborea.hardware.fitpro.session

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.DeviceCommand
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
    initialDeviceInfo: DeviceInfo,
    protected val accumulator: ExerciseDataAccumulator,
    private val tag: String,
) : FitProSession {

    /**
     * Live device parameters (step sizes, incline/speed bounds, max resistance). Starts as the
     * catalog/product-id guess the adapter constructed the session with; the adapter re-points it
     * via [updateDeviceInfo] once the handshake identity resolves (user's saved config wins, else
     * MCU-reported limits overlay) — without this the user's configured incline step and the real
     * machine bounds would never reach [nextAdjustedGrade]/[roundToStep]. Volatile reference swap
     * of an immutable value: safe against the poll-loop/command-path readers.
     */
    @Volatile
    protected var deviceInfo: DeviceInfo = initialDeviceInfo.sanitizedForControl()
        private set

    final override fun updateDeviceInfo(info: DeviceInfo) {
        val old = deviceInfo
        val sanitized = info.sanitizedForControl()
        // Identity updates re-resolve per lifetime-stat event (odometer ticks) — skip the swap,
        // the subclass hook, and the log line when nothing actually changed.
        if (sanitized == old) return
        deviceInfo = sanitized
        onDeviceInfoUpdated(old, sanitized)
        logger.i(
            tag,
            "Device info updated: inclineStep=${sanitized.inclineStep} " +
                "incline=${sanitized.minIncline}..${sanitized.maxIncline} " +
                "speedStep=${sanitized.speedStep} maxSpeed=${sanitized.maxSpeed} " +
                "maxResistance=${sanitized.maxResistance}",
        )
    }

    /** Hook for protocol-specific state derived from [deviceInfo] (e.g. V1's resistance converter). */
    protected open fun onDeviceInfoUpdated(old: DeviceInfo, new: DeviceInfo) {}

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
        lastSentGrade = clampedInclineTarget(
            lastSentGrade + if (increase) deviceInfo.inclineStep else -deviceInfo.inclineStep,
        )
        return lastSentGrade
    }

    /** Steps the accumulated speed target by one [DeviceInfo.speedStep], clamped to 0..maxSpeed. */
    protected fun nextAdjustedSpeed(increase: Boolean): Float {
        lastSentSpeed = clampedSpeedTarget(
            lastSentSpeed + if (increase) deviceInfo.speedStep else -deviceInfo.speedStep,
        )
        return lastSentSpeed
    }

    /**
     * Absolute speed targets can come from FTMS clients with no knowledge of the machine's range;
     * clamp before writing. (No lower floor beyond 0: teardown paths legitimately write 0, and
     * consoles reject sub-minimum values themselves.)
     */
    protected fun clampedSpeedTarget(kph: Float): Float {
        val maxSpeed = deviceInfo.maxSpeed
        // A non-positive max means "unknown / not speed-bounded" (rower type-defaults report 0)
        // — clamping to it would pin every target to zero; prod passed these through.
        return if (maxSpeed > 0f) kph.coerceIn(0f, maxSpeed) else kph.coerceAtLeast(0f)
    }

    /**
     * The incline counterpart of [clampedSpeedTarget], shared by the absolute and relative incline
     * paths so they can't disagree. An empty range means "unknown" (bike/rower type-defaults are
     * 0..0) — clamping to it would pin every incline target to zero.
     */
    protected fun clampedInclineTarget(percent: Float): Float {
        val info = deviceInfo
        return if (info.maxIncline > info.minIncline) {
            percent.coerceIn(info.minIncline, info.maxIncline)
        } else {
            percent
        }
    }

    /** Protocol-specific grade-target write. Must not throw — wrap transport errors and log. */
    protected abstract suspend fun writeGradeTarget(target: Float)

    /**
     * Incline target writes go through a throttle-with-trailing-flush so a burst of stepped
     * targets (held +/− button, Zwift SIM gradient stream) reaches the deck as one moving target
     * per interval instead of a step-settle-step staircase — see [TargetWriteCoalescer].
     */
    protected val gradeCoalescer by lazy {
        TargetWriteCoalescer(sessionScope, GRADE_WRITE_INTERVAL_MS) { writeGradeTarget(it) }
    }

    /**
     * Shared handling for the two incline commands; returns true when consumed. Accumulation is
     * deliberately synchronous and coalescing-independent: each Adjust press advances
     * [lastSentGrade] by one step immediately, whether or not that particular value ever reaches
     * the wire, so held buttons keep their one-press-one-step semantics.
     */
    protected suspend fun handleInclineCommand(command: DeviceCommand): Boolean = when (command) {
        is DeviceCommand.SetIncline -> {
            lastSentGrade = clampedInclineTarget(roundToStep(command.percent, deviceInfo.inclineStep))
            publishCommandedInclineTarget(lastSentGrade)
            gradeCoalescer.submit(lastSentGrade)
            true
        }
        is DeviceCommand.AdjustIncline -> {
            val target = nextAdjustedGrade(command.increase)
            publishCommandedInclineTarget(target)
            gradeCoalescer.submit(target)
            true
        }
        else -> false
    }

    /**
     * Surfaces the commanded incline target on [exerciseData] immediately: the dashboard's blue
     * goal appears (and counts along during a held button) without waiting for a console echo —
     * some consoles never send one — and even while the coalescer is suppressing intermediate
     * wire writes. A later console-reported target event simply overwrites this.
     */
    private fun publishCommandedInclineTarget(target: Float) {
        accumulator.updateTargetIncline(target)
        _exerciseData.value = accumulator.snapshot()
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

        /**
         * Minimum interval between incline target writes. Tunable 500–1000 ms: below ~500 ms the
         * deck motor can catch (and settle at) each intermediate target on slow actuators; above
         * ~1000 ms single Zwift gradient corrections start to feel laggy. Not yet measured against
         * a specific deck — adjust from field testing.
         */
        internal const val GRADE_WRITE_INTERVAL_MS = 750L
    }
}

/**
 * Normalises a [DeviceInfo] into values the control paths can safely divide and clamp by.
 *
 * The device-config screen (Settings → Device) is free-text with no validation, so a saved config
 * can carry a zero step or a reversed incline range — and since the session now takes its step
 * sizes and bounds from that resolved config live, those values reach [BaseFitProSession.roundToStep]
 * and the clamps directly. A zero step silently kills incline/speed control (a divide by zero
 * rounds every target to 0, and the Adjust± accumulators stop moving — including the *physical*
 * console keys on host-routed V2 treadmills); a reversed range makes `coerceIn` throw on every
 * press. Normalise once, on the way in, so no caller has to defend itself.
 */
private fun DeviceInfo.sanitizedForControl(): DeviceInfo {
    val incline = if (inclineStep > 0f) inclineStep else DEFAULT_INCLINE_STEP
    val speed = if (speedStep > 0f) speedStep else DEFAULT_SPEED_STEP
    // Bounds typed in the wrong order describe the range the user meant; order them rather than
    // discarding both. An equal pair stays equal — that's the "unknown range" the clamps skip.
    val lowIncline = minOf(minIncline, maxIncline)
    val highIncline = maxOf(minIncline, maxIncline)
    if (incline == inclineStep && speed == speedStep &&
        lowIncline == minIncline && highIncline == maxIncline
    ) {
        return this
    }
    return copy(
        inclineStep = incline,
        speedStep = speed,
        minIncline = lowIncline,
        maxIncline = highIncline,
    )
}

private const val DEFAULT_INCLINE_STEP = 0.5f
private const val DEFAULT_SPEED_STEP = 0.5f
