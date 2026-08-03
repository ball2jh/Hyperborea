package com.nettarion.hyperborea.demo

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.Metric
import com.nettarion.hyperborea.core.orchestration.Prerequisite
import com.nettarion.hyperborea.core.system.SystemSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * Emulator-only stand-in for the FitPro USB hardware: a simulated treadmill that produces
 * believable exercise data so the whole pipeline (dashboard, overlay, broadcasts) can be
 * exercised without equipment.
 *
 * Mirrors the two hardware behaviours the orchestrator depends on:
 *  - `identify()` publishes [deviceInfo] (the idle probe path).
 *  - After [connect] it reports `workoutMode = IDLE` (armed treadmills park in
 *    AwaitingConsoleStart), then after [CONSOLE_START_DELAY_MS] flips to `RUNNING` — simulating
 *    the user pressing the physical Start key. It never reports IDLE again afterwards, since
 *    the interpreter reads that as the physical Stop key.
 */
class DemoHardwareAdapter(
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) : HardwareAdapter {

    override val prerequisites: List<Prerequisite> = emptyList()
    override fun canOperate(snapshot: SystemSnapshot): Boolean = true

    private val _state = MutableStateFlow<AdapterState>(AdapterState.Inactive)
    override val state: StateFlow<AdapterState> = _state

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    override val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo

    private val _exerciseData = MutableStateFlow<ExerciseData?>(null)
    override val exerciseData: StateFlow<ExerciseData?> = _exerciseData

    private val _deviceIdentity = MutableStateFlow<DeviceIdentity?>(null)
    override val deviceIdentity: StateFlow<DeviceIdentity?> = _deviceIdentity

    override val degradedReason: StateFlow<String?> = MutableStateFlow(null)

    private var tickerJob: Job? = null

    // Simulation state — touched only from the ticker coroutine and sendCommand.
    private var speedKph = 0f
    private var targetSpeedKph = 0f
    private var incline = 0f
    private var targetIncline = 0f
    private var distanceKm = 0f
    private var calories = 0f
    private var elapsedSeconds = 0L
    private var heartRate = 72f
    private var workoutMode = WORKOUT_MODE_IDLE
    private var paused = false

    override suspend fun connect() {
        if (_state.value is AdapterState.Active || _state.value is AdapterState.Activating) return
        _state.value = AdapterState.Activating
        logger.i(TAG, "Demo treadmill connecting")
        _deviceIdentity.value = DEMO_IDENTITY
        _deviceInfo.value = DEMO_INFO
        resetSimulation()
        _state.value = AdapterState.Active

        tickerJob = scope.launch {
            // Armed but parked: the orchestrator sits in AwaitingConsoleStart until the console
            // leaves idle. Simulate the physical Start key after a short pause.
            val startAt = System.currentTimeMillis() + CONSOLE_START_DELAY_MS
            while (true) {
                if (workoutMode == WORKOUT_MODE_IDLE && System.currentTimeMillis() >= startAt) {
                    workoutMode = WORKOUT_MODE_RUNNING
                    targetSpeedKph = INITIAL_TARGET_KPH
                    logger.i(TAG, "Demo console START pressed — belt starting")
                }
                tick()
                _exerciseData.value = buildExerciseData()
                delay(TICK_MS)
            }
        }
    }

    override suspend fun disconnect() {
        if (_state.value is AdapterState.Inactive) return
        logger.i(TAG, "Demo treadmill disconnecting")
        tickerJob?.cancel()
        tickerJob = null
        _exerciseData.value = null
        _state.value = AdapterState.Inactive
    }

    override suspend fun identify(): DeviceInfo? {
        _deviceIdentity.value = DEMO_IDENTITY
        _deviceInfo.value = DEMO_INFO
        logger.i(TAG, "Demo treadmill identified")
        return DEMO_INFO
    }

    override suspend fun sendCommand(command: DeviceCommand) {
        logger.d(TAG, "Demo command: $command")
        when (command) {
            is DeviceCommand.SetIncline -> targetIncline = command.percent.clampIncline()
            is DeviceCommand.SetTargetSpeed -> targetSpeedKph = command.kph.clampSpeed()
            is DeviceCommand.AdjustIncline ->
                targetIncline = (targetIncline + if (command.increase) DEMO_INFO.inclineStep else -DEMO_INFO.inclineStep).clampIncline()
            is DeviceCommand.AdjustSpeed ->
                targetSpeedKph = (targetSpeedKph + if (command.increase) DEMO_INFO.speedStep else -DEMO_INFO.speedStep).clampSpeed()
            is DeviceCommand.PauseWorkout -> paused = true
            is DeviceCommand.ResumeWorkout -> paused = false
            else -> Unit // Fan/volume/etc. are irrelevant to the simulation.
        }
    }

    override fun setInitialElapsedTime(seconds: Long) {
        elapsedSeconds = seconds
    }

    override suspend fun refreshDeviceInfo() {
        _deviceInfo.value = DEMO_INFO
    }

    private fun resetSimulation() {
        speedKph = 0f; targetSpeedKph = 0f
        incline = 0f; targetIncline = 0f
        distanceKm = 0f; calories = 0f
        elapsedSeconds = 0L; heartRate = 72f
        workoutMode = WORKOUT_MODE_IDLE
        paused = false
    }

    /** One second of simulated belt physics. */
    private fun tick() {
        if (workoutMode != WORKOUT_MODE_RUNNING) return

        val effectiveTarget = if (paused) 0f else targetSpeedKph
        speedKph = approach(speedKph, effectiveTarget, SPEED_RAMP_KPH_PER_TICK)
        incline = approach(incline, targetIncline, INCLINE_RAMP_PER_TICK)

        if (!paused) {
            elapsedSeconds += 1
            distanceKm += speedKph / 3600f
            calories += speedKph * 0.02f
        }

        // Heart rate drifts toward an effort-derived level with a little noise.
        val hrTarget = 70f + speedKph * 7f + incline * 3f
        heartRate = approach(heartRate, hrTarget, 1.5f) + Random.nextFloat() - 0.5f
    }

    private fun buildExerciseData(): ExerciseData {
        val running = workoutMode == WORKOUT_MODE_RUNNING
        // Rough treadmill running power: flat component + grade component for a ~75 kg runner.
        val speedMs = speedKph / 3.6f
        val power = (75f * speedMs * (1.0f + incline / 10f) * 1.04f).toInt()
        return ExerciseData(
            power = if (running) power else 0,
            cadence = null,
            speed = speedKph,
            resistance = null,
            incline = incline,
            heartRate = if (running) heartRate.toInt() else null,
            distance = if (running) distanceKm else null,
            calories = if (running) calories.toInt() else null,
            elapsedTime = elapsedSeconds,
            targetSpeed = if (running && abs(targetSpeedKph - speedKph) > 0.05f) targetSpeedKph else null,
            targetIncline = if (running && abs(targetIncline - incline) > 0.05f) targetIncline else null,
            workoutMode = workoutMode,
        )
    }

    private fun approach(current: Float, target: Float, step: Float): Float = when {
        current < target -> minOf(current + step, target)
        current > target -> maxOf(current - step, target)
        else -> current
    }

    private fun Float.clampIncline(): Float = coerceIn(DEMO_INFO.minIncline, DEMO_INFO.maxIncline)
    private fun Float.clampSpeed(): Float = coerceIn(0f, DEMO_INFO.maxSpeed)

    companion object {
        private const val TAG = "DemoHardware"

        // Mirrors WorkoutModeInterpreter's wire constants (internal to :core).
        private const val WORKOUT_MODE_IDLE = 1
        private const val WORKOUT_MODE_RUNNING = 2

        private const val TICK_MS = 1000L
        private const val CONSOLE_START_DELAY_MS = 5000L
        private const val INITIAL_TARGET_KPH = 8f
        private const val SPEED_RAMP_KPH_PER_TICK = 0.3f
        private const val INCLINE_RAMP_PER_TICK = 0.5f

        private val DEMO_INFO = DeviceInfo(
            name = "Demo Treadmill",
            type = DeviceType.TREADMILL,
            supportedMetrics = setOf(
                Metric.POWER, Metric.SPEED, Metric.INCLINE,
                Metric.HEART_RATE, Metric.DISTANCE, Metric.CALORIES,
            ),
            maxResistance = 0, minResistance = 0,
            minIncline = -3f, maxIncline = 15f,
            maxPower = 2000, minPower = 0, powerStep = 1,
            resistanceStep = 1f, inclineStep = 0.5f,
            speedStep = 0.5f, maxSpeed = 20f,
            configKey = -999_999,
        )

        private val DEMO_IDENTITY = DeviceIdentity(
            serialNumber = "DEMO-0001",
            firmwareVersion = "demo",
            model = null,
            partNumber = null,
        )
    }
}
