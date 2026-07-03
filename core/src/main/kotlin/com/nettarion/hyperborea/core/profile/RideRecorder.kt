package com.nettarion.hyperborea.core.profile

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.core.model.WorkoutSample

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sin

class RideRecorder(
    private val profileRepository: ProfileRepository,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) {
    private val saveMutex = Mutex()
    private var collectJob: Job? = null
    private var state = AccumulationState()

    private val _lastSavedRideId = MutableStateFlow<Long?>(null)
    val lastSavedRideId: StateFlow<Long?> = _lastSavedRideId.asStateFlow()

    fun start(dataSource: Flow<ExerciseData>) {
        if (collectJob != null) return
        state = AccumulationState()
        _lastSavedRideId.value = null
        logger.i(TAG, "Recorder armed — ride opens on first activity")

        collectJob = scope.launch {
            dataSource.collect { data ->
                accumulate(data)
            }
        }
    }

    suspend fun stop(save: Boolean = true) {
        // Join, don't just cancel: accumulate() runs on the collector coroutine and shares [state]
        // with the save below — after the join nothing else mutates it.
        collectJob?.cancelAndJoin()
        collectJob = null

        if (!save) {
            saveMutex.withLock {
                logger.i(TAG, "Recording discarded by user")
                state = AccumulationState()
            }
            return
        }

        saveAndReset(flushPending = true)
    }

    private suspend fun accumulate(data: ExerciseData) {
        // Always update cumulative counters (bike totals)
        state.lastElapsedTime = data.elapsedTime
        data.distance?.let { state.lastDistance = it }
        data.calories?.let { state.lastCalories = it }

        // Per-second gate
        val currentSecond = data.elapsedTime
        if (currentSecond <= state.lastSampleSecond) return
        state.lastSampleSecond = currentSecond

        if (isIdle(data)) {
            // No ride in progress (fresh start, or parked after an auto-stop): stay dormant until
            // the rider produces output — the next active second opens a new ride.
            if (state.startedAtMs == 0L) return

            state.consecutiveIdleSeconds++

            if (state.consecutiveIdleSeconds >= AUTO_STOP_SECONDS) {
                autoStop()
                return
            }

            // Buffer the sample — don't accumulate into main state yet
            state.pendingSamples.add(buildSample(data))
        } else {
            if (state.startedAtMs == 0L) openRide(data)

            // Active second — resolve any pending idle buffer
            if (state.consecutiveIdleSeconds > 0) {
                if (state.consecutiveIdleSeconds < IDLE_TRIM_THRESHOLD_SECONDS) {
                    flushPendingBuffer()
                    logger.d(TAG, "Kept short idle: ${state.consecutiveIdleSeconds}s")
                } else {
                    logger.d(TAG, "Trimmed idle: ${state.consecutiveIdleSeconds}s")
                    state.totalTrimmedSeconds += state.consecutiveIdleSeconds
                    state.pendingSamples.clear()
                }
                state.consecutiveIdleSeconds = 0
            }

            accumulateSecond(data)
        }
    }

    private fun isIdle(data: ExerciseData): Boolean {
        val power = data.power ?: 0
        val cadence = data.cadence ?: 0
        val speed = data.speed ?: 0f
        return power == 0 && cadence == 0 && speed == 0f
    }

    private fun accumulateSecond(data: ExerciseData) {
        // Elevation gain (needs distance delta from ExerciseData)
        val currentDistance = data.distance
        val currentIncline = data.incline
        if (currentDistance != null) {
            if (!state.hasFirstDistance) {
                state.hasFirstDistance = true
            } else if (currentIncline != null && currentIncline > 0f) {
                val deltaDistanceKm = currentDistance - state.prevDistance
                if (deltaDistanceKm > 0f) {
                    val grade = currentIncline / 100f
                    val elevMeters = deltaDistanceKm * 1000f * sin(atan(grade.toDouble()))
                    state.totalElevationGain += elevMeters
                }
            }
            state.prevDistance = currentDistance
        }

        accumulateSecondFromSample(buildSample(data))
    }

    /**
     * Opens a new ride on the first active (non-idle) second. The first ride of a session counts
     * from the hardware's own zero; a ride opened after an auto-stop rebases instead — the
     * hardware's elapsed/distance/calorie counters are cumulative for the whole console session,
     * so without new baselines a "second wind" ride would inherit the earlier ride's totals.
     */
    private fun openRide(data: ExerciseData) {
        state.startedAtMs = System.currentTimeMillis()
        if (state.rebaseOnOpen) {
            state.baseElapsedSeconds = (data.elapsedTime - 1).coerceAtLeast(0)
            state.baseDistanceKm = data.distance ?: 0f
            state.baseCalories = data.calories ?: 0
        }
        logger.i(TAG, "Recording started (elapsed baseline ${state.baseElapsedSeconds}s)")
    }

    /** Builds a sample with the session-cumulative counters rebased to this ride's baselines. */
    private fun buildSample(data: ExerciseData) = WorkoutSample(
        timestampSeconds = data.elapsedTime - state.baseElapsedSeconds,
        power = data.power,
        cadence = data.cadence,
        speedKph = data.speed,
        heartRate = data.heartRate,
        resistance = data.resistance,
        incline = data.incline,
        calories = data.calories?.let { (it - state.baseCalories).coerceAtLeast(0) },
        distanceKm = data.distance?.let { (it - state.baseDistanceKm).coerceAtLeast(0f) },
    )

    private fun accumulateSecondFromSample(sample: WorkoutSample) {
        sample.power?.let { p ->
            state.powerSum += p
            state.powerSamples++
            if (p > state.maxPower) state.maxPower = p
        }
        sample.cadence?.let { c ->
            state.cadenceSum += c
            state.cadenceSamples++
            if (c > state.maxCadence) state.maxCadence = c
        }
        sample.speedKph?.let { s ->
            state.speedSum += s
            state.speedSamples++
            if (s > state.maxSpeed) state.maxSpeed = s
        }
        sample.heartRate?.let { hr ->
            state.heartRateSum += hr
            state.heartRateSamples++
            if (hr > state.maxHeartRate) state.maxHeartRate = hr
        }
        sample.resistance?.let { r ->
            state.resistanceSum += r
            state.resistanceSamples++
            if (r > state.maxResistance) state.maxResistance = r
        }
        sample.incline?.let { i ->
            state.inclineSum += i
            state.inclineSamples++
            if (i > state.maxIncline) state.maxIncline = i
        }

        // NP buffer
        val power = sample.power ?: 0
        state.npBuffer[state.npBufferIndex] = power
        state.npBufferIndex = (state.npBufferIndex + 1) % NP_WINDOW_SIZE
        if (state.npBufferFilled < NP_WINDOW_SIZE) state.npBufferFilled++

        if (state.npBufferFilled >= NP_WINDOW_SIZE) {
            var sum = 0L
            for (v in state.npBuffer) sum += v
            val avg30 = sum.toDouble() / NP_WINDOW_SIZE
            state.np4Sum += avg30.pow(4.0)
            state.np4Count++
        }

        state.samples.add(sample)
    }

    private fun flushPendingBuffer() {
        for (sample in state.pendingSamples) {
            accumulateSecondFromSample(sample)
        }
        state.pendingSamples.clear()
    }

    /**
     * Saves and closes the current ride after [AUTO_STOP_SECONDS] of continuous idle — but keeps
     * collecting: the reset state parks with no ride open, and the next active second opens a fresh
     * ride (a rider's "second wind" gets its own recording instead of being lost). Runs inline on
     * the collector coroutine, so it can't race later samples.
     */
    private suspend fun autoStop() {
        state.totalTrimmedSeconds += state.consecutiveIdleSeconds
        state.pendingSamples.clear()
        logger.i(TAG, "Auto-stop: ${AUTO_STOP_SECONDS}s idle — saving; a new ride opens on next activity")
        saveAndReset()
        // The fresh state opens mid-session: rebase the cumulative hardware counters at ride open.
        state.rebaseOnOpen = true
    }

    private suspend fun saveAndReset(flushPending: Boolean = false) = saveMutex.withLock {
        if (state.startedAtMs == 0L) return@withLock // Already saved/reset

        if (flushPending && state.pendingSamples.isNotEmpty()) {
            flushPendingBuffer()
            state.consecutiveIdleSeconds = 0
        }

        val durationSeconds = state.lastElapsedTime - state.baseElapsedSeconds - state.totalTrimmedSeconds
        if (durationSeconds < MIN_DURATION_SECONDS) {
            logger.i(TAG, "Recording discarded (${durationSeconds}s < ${MIN_DURATION_SECONDS}s)")
            state = AccumulationState()
            return@withLock
        }

        val profile = profileRepository.activeProfile.value
        if (profile == null) {
            logger.w(TAG, "No active profile, discarding ride")
            state = AccumulationState()
            return@withLock
        }
        val profileId = profile.id

        // Compute NP
        val normalizedPower = if (state.np4Count > 0) {
            (state.np4Sum / state.np4Count).pow(0.25).toInt()
        } else {
            null
        }

        // Compute IF and TSS from FTP
        val ftp = profile.ftpWatts
        val intensityFactor: Float?
        val trainingStressScore: Float?
        if (normalizedPower != null && ftp != null && ftp > 0) {
            intensityFactor = normalizedPower.toFloat() / ftp
            trainingStressScore = (durationSeconds * intensityFactor.pow(2) * 100f) / 3600f
        } else {
            intensityFactor = null
            trainingStressScore = null
        }

        val elevationGain = if (state.totalElevationGain > 0) state.totalElevationGain.toFloat() else null

        val summary = RideSummary(
            profileId = profileId,
            startedAt = state.startedAtMs,
            durationSeconds = durationSeconds,
            distanceKm = (state.lastDistance - state.baseDistanceKm).coerceAtLeast(0f),
            calories = (state.lastCalories - state.baseCalories).coerceAtLeast(0),
            avgPower = if (state.powerSamples > 0) (state.powerSum / state.powerSamples).toInt() else null,
            maxPower = if (state.powerSamples > 0) state.maxPower else null,
            avgCadence = if (state.cadenceSamples > 0) (state.cadenceSum / state.cadenceSamples).toInt() else null,
            maxCadence = if (state.cadenceSamples > 0) state.maxCadence else null,
            avgSpeedKph = if (state.speedSamples > 0) (state.speedSum / state.speedSamples).toFloat() else null,
            maxSpeedKph = if (state.speedSamples > 0) state.maxSpeed else null,
            avgHeartRate = if (state.heartRateSamples > 0) (state.heartRateSum / state.heartRateSamples).toInt() else null,
            maxHeartRate = if (state.heartRateSamples > 0) state.maxHeartRate else null,
            avgResistance = if (state.resistanceSamples > 0) (state.resistanceSum / state.resistanceSamples).toInt() else null,
            maxResistance = if (state.resistanceSamples > 0) state.maxResistance else null,
            avgIncline = if (state.inclineSamples > 0) (state.inclineSum / state.inclineSamples).toFloat() else null,
            maxIncline = if (state.inclineSamples > 0) state.maxIncline else null,
            totalElevationGainMeters = elevationGain,
            normalizedPower = normalizedPower,
            intensityFactor = intensityFactor,
            trainingStressScore = trainingStressScore,
        )

        val savedSamples = state.samples.toList()
        val savedId = profileRepository.saveRideSummary(summary, savedSamples)
        logger.i(TAG, "Recording saved: id=$savedId, ${durationSeconds}s, ${summary.distanceKm}km, ${summary.calories}cal, ${savedSamples.size} samples")
        state = AccumulationState()
        _lastSavedRideId.value = savedId
    }

    private class AccumulationState {
        /** 0 = no ride open; stamped by [openRide] on the first active second. */
        var startedAtMs = 0L

        // Ride-start baselines for the hardware's session-cumulative counters (see openRide)
        var rebaseOnOpen = false
        var baseElapsedSeconds = 0L
        var baseDistanceKm = 0f
        var baseCalories = 0

        var powerSum = 0L
        var maxPower = 0
        var powerSamples = 0L

        var cadenceSum = 0L
        var maxCadence = 0
        var cadenceSamples = 0L

        var speedSum = 0.0
        var maxSpeed = 0f
        var speedSamples = 0L

        var heartRateSum = 0L
        var maxHeartRate = 0
        var heartRateSamples = 0L

        var resistanceSum = 0L
        var maxResistance = 0
        var resistanceSamples = 0L

        var inclineSum = 0.0
        var maxIncline = 0f
        var inclineSamples = 0L

        var lastDistance = 0f
        var lastCalories = 0
        var lastElapsedTime = 0L

        var hasFirstDistance = false
        var prevDistance = 0f
        var totalElevationGain = 0.0

        val npBuffer = IntArray(NP_WINDOW_SIZE)
        var npBufferIndex = 0
        var npBufferFilled = 0
        var np4Sum = 0.0
        var np4Count = 0L

        var lastSampleSecond = -1L
        val samples = mutableListOf<WorkoutSample>()

        // Idle tracking
        var totalTrimmedSeconds = 0L
        var consecutiveIdleSeconds = 0
        val pendingSamples = mutableListOf<WorkoutSample>()
    }

    private companion object {
        const val TAG = "RideRecorder"
        const val MIN_DURATION_SECONDS = 60L
        const val NP_WINDOW_SIZE = 30
        const val IDLE_TRIM_THRESHOLD_SECONDS = 60
        const val AUTO_STOP_SECONDS = 300
    }
}
