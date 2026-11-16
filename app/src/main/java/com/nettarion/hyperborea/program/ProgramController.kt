package com.nettarion.hyperborea.program

import android.os.SystemClock
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.orchestration.Orchestrator
import com.nettarion.hyperborea.core.orchestration.OrchestratorState
import com.nettarion.hyperborea.core.program.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgramController @Inject constructor(
    private val orchestrator: Orchestrator,
    private val hardware: HardwareAdapter,
    private val cues: ProgramCues,
    private val scope: CoroutineScope,
    private val logger: AppLogger,
) {
    private val mutex = Mutex()
    private var engine: ProgramEngine? = null
    private val _state = MutableStateFlow<ProgramRunState?>(null)
    val state = _state.asStateFlow()
    private val _canResumeProgram = MutableStateFlow(false)
    val canResumeProgram = _canResumeProgram.asStateFlow()
    private var ticker: Job? = null
    private var telemetry: Job? = null
    @Volatile private var lastTelemetry = 0L
    @Volatile private var launching = false
    private var sentSpeed: Float? = null
    private var sentIncline: Float? = null
    private fun clearSent() { sentSpeed = null; sentIncline = null }

    fun startMonitoring() {
        if (ticker?.isActive == true) return
        hardware.setConsoleControlListener { command ->
            if (command is DeviceCommand.ResumeWorkout) {
                // This callback is a physical Start edge, not a workout-mode/timeout report.
                // Initial starts remain gated by the protocol; only an existing pause resumes here.
                if (orchestrator.state.value is OrchestratorState.Paused) orchestrator.resume()
            } else if (command is DeviceCommand.PauseWorkout) orchestrator.pause()
            else manualControl(command)
        }
        telemetry = scope.launch { hardware.exerciseData.collect { if (it != null) lastTelemetry = SystemClock.elapsedRealtime() } }
        ticker = scope.launch {
            var previous = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(100)
                val now = SystemClock.elapsedRealtime()
                try { tick(now - previous, now) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    logger.e(TAG, "Program failed", e)
                    mutex.withLock { engine?.end(ProgramStatus.ERROR, "Program stopped: ${e.message}"); _state.value = engine?.state; cues.stop() }
                    orchestrator.pause()
                }
                previous = now
            }
        }
    }

    suspend fun start(program: WorkoutProgram) {
        val mode = mutex.withLock {
            val mode = programStartMode(orchestrator.state.value, engine?.state?.active == true)
                ?: error("Stop the current program or resume the manual workout before starting a program.")
            val device = hardware.deviceInfo.value ?: error("Waiting for treadmill identification.")
            program.validationError(device)?.let { error(it) }
            cues.stop()
            engine = ProgramEngine(program); clearSent(); _state.value = engine?.state
            _canResumeProgram.value = false
            launching = mode == ProgramStartMode.NEW_WORKOUT
            mode
        }
        // Attaching to a manual walk leaves the belt and the existing ride recording alone.
        // The next healthy tick begins the same audible count-in as a newly started workout.
        if (mode == ProgramStartMode.NEW_WORKOUT) {
            try { orchestrator.start() } finally { launching = false }
        }
    }

    suspend fun manualControl(command: DeviceCommand) {
        if (command !is DeviceCommand.SetTargetSpeed && command !is DeviceCommand.AdjustSpeed && command !is DeviceCommand.SetIncline && command !is DeviceCommand.AdjustIncline && command !is DeviceCommand.PauseWorkout && command !is DeviceCommand.ResumeWorkout) return
        mutex.withLock {
            val active = engine?.takeIf { it.state.active } ?: return@withLock
            when (command) {
                is DeviceCommand.SetTargetSpeed, is DeviceCommand.AdjustSpeed -> {
                    active.overrideForBlock(speed = true); sentSpeed = null
                }
                is DeviceCommand.SetIncline, is DeviceCommand.AdjustIncline -> {
                    active.overrideForBlock(incline = true); sentIncline = null
                }
                else -> { active.hold(true, "Manual control · resume program when ready"); clearSent(); cues.stop() }
            }
            _state.value = active.state
        }
    }

    private fun equipmentReady(now: Long): Boolean =
        (orchestrator.state.value as? OrchestratorState.Running)?.let { it.degraded == null } == true &&
            hardware.deviceInfo.value?.type == com.nettarion.hyperborea.core.model.DeviceType.TREADMILL &&
            hardware.state.value is AdapterState.Active && now - lastTelemetry <= 5000

    suspend fun resumeProgram() = mutex.withLock {
        if (equipmentReady(SystemClock.elapsedRealtime()) && engine?.state?.status == ProgramStatus.MANUAL) {
            clearSent(); applyEvents(engine!!.begin()); _state.value = engine?.state
        }
    }

    suspend fun clearFinished() = mutex.withLock {
        _canResumeProgram.value = false
        if (engine?.state?.active != true) { engine = null; _state.value = null; clearSent(); cues.stop() }
    }

    suspend fun cancel() = mutex.withLock {
        _canResumeProgram.value = false
        engine?.takeIf { it.state.active }?.end(ProgramStatus.CANCELLED, "Program ended")
        _state.value = engine?.state; cues.stop()
    }
    suspend fun stopMonitoring() { hardware.setConsoleControlListener {}; ticker?.cancelAndJoin(); telemetry?.cancelAndJoin(); cancel(); cues.close(); ticker = null; telemetry = null }

    private suspend fun tick(delta: Long, now: Long) {
        var completed = false
        mutex.withLock {
            val e = engine ?: return@withLock
            _canResumeProgram.value = e.state.status == ProgramStatus.MANUAL && equipmentReady(now)
            if (!e.state.active) return@withLock
            when (val mode = orchestrator.state.value) {
                is OrchestratorState.Running -> {
                    if (!equipmentReady(now) || delta > ProgramEngine.MAX_TICK_MILLIS) {
                        e.hold(true, "Connection or timing interrupted · resume program when ready"); cues.stop()
                    } else if (e.state.status != ProgramStatus.MANUAL) {
                        if (e.state.status == ProgramStatus.READY || e.state.status == ProgramStatus.PAUSED) { clearSent(); applyEvents(e.begin()) }
                        else completed = applyEvents(e.advance(delta.coerceAtLeast(0)))
                    }
                }
                is OrchestratorState.Paused -> { if (e.state.status != ProgramStatus.MANUAL) e.hold(); cues.stop() }
                is OrchestratorState.AwaitingConsoleStart, is OrchestratorState.Preparing -> Unit
                else -> if (!launching) { e.end(if (mode is OrchestratorState.Error) ProgramStatus.ERROR else ProgramStatus.CANCELLED, if (mode is OrchestratorState.Error) mode.message else "Program ended"); cues.stop() }
            }
            _state.value = e.state
            _canResumeProgram.value = e.state.status == ProgramStatus.MANUAL && equipmentReady(now)
        }
        if (completed) { logger.i(TAG, "Program complete — stopping and saving workout"); orchestrator.stop(saveRide = true) }
    }

    private suspend fun applyEvents(events: List<ProgramEvent>): Boolean {
        for (event in events) when (event) {
            is ProgramEvent.Cue -> if (!cues.play(event.count)) {
                if (event.count == 0 && engine?.state?.status == ProgramStatus.COMPLETE) {
                    // All three stop warnings have already played. A missing acknowledgement
                    // must never prevent the scheduled stop or leave a completed session running.
                    logger.w(TAG, "End tone unavailable — continuing the scheduled stop")
                } else {
                    engine?.hold(true, "Countdown sound unavailable · check media volume, then resume program")
                    cues.stop(); return false
                }
            }
            is ProgramEvent.Target -> {
                val info = hardware.deviceInfo.value ?: return false
                fun quantize(value: Float, step: Float): Float = if (step > 0f) (value / step).roundToInt() * step else value
                val ramping = engine?.state?.let { it.block.ramp && it.blockIndex > 0 } == true
                val target = ProgramTarget(
                    event.target.speedKph.coerceIn(.5f, info.maxSpeed),
                    quantize(event.target.inclinePercent, if (ramping) .01f else info.inclineStep).coerceIn(info.minIncline, info.maxIncline),
                )
                if (event.controlSpeed && target.speedKph != sentSpeed) {
                    if (!orchestrator.sendProgramCommand(DeviceCommand.SetTargetSpeed(target.speedKph))) return false
                    sentSpeed = target.speedKph
                    logger.i(TAG, "Program speed: $sentSpeed")
                }
                if (event.controlIncline && target.inclinePercent != sentIncline) {
                    if (!orchestrator.sendProgramCommand(DeviceCommand.SetIncline(target.inclinePercent, snapToStep = !ramping))) return false
                    sentIncline = target.inclinePercent
                    logger.i(TAG, "Program incline: $sentIncline")
                }
            }
            ProgramEvent.Finished -> return true // Let the final acknowledgement finish naturally.
        }
        return false
    }
    private companion object { const val TAG = "ProgramRunner" }
}
