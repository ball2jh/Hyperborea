package com.nettarion.hyperborea.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sin

class RideRecorder(
    private val profileRepository: ProfileRepository,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
) {
    private var collectJob: Job? = null
    private var state = AccumulationState()

    fun start(dataSource: Flow<ExerciseData>) {
        if (collectJob != null) return
        state = AccumulationState()
        state.startedAtMs = System.currentTimeMillis()
        logger.i(TAG, "Recording started")

        collectJob = scope.launch {
            dataSource.collect { data ->
                accumulate(data)
            }
        }
    }

    suspend fun stop(save: Boolean = true) {
        collectJob?.cancel()
        collectJob = null

        if (!save) {
            logger.i(TAG, "Recording discarded by user")
            state = AccumulationState()
            return
        }

        // Explicit stop: flush any pending idle buffer regardless of duration
        if (state.pendingSamples.isNotEmpty()) {
            flushPendingBuffer()
            state.consecutiveIdleSeconds = 0
        }

        saveAndReset()
    }

    private fun accumulate(data: ExerciseData) {
        // Always update cumulative counters (bike totals)
        state.sampleCount++
        state.lastElapsedTime = data.elapsedTime
        data.distance?.let { state.lastDistance = it }
        data.calories?.let { state.lastCalories = it }

        // Per-second gate
        val currentSecond = data.elapsedTime
        if (currentSecond <= state.lastSampleSecond) return
        state.lastSampleSecond = currentSecond

        if (isIdle(data)) {
            state.consecutiveIdleSeconds++

            if (state.consecutiveIdleSeconds >= AUTO_STOP_SECONDS) {
                triggerAutoStop()
                return
            }

            // Buffer the sample — don't accumulate into main state yet
            state.pendingSamples.add(
                WorkoutSample(
                    timestampSeconds = currentSecond,
                    power = data.power,
                    cadence = data.cadence,
                    speedKph = data.speed,
                    heartRate = data.heartRate,
                    resistance = data.resistance,
                    incline = data.incline,
                    calories = data.calories,
                    distanceKm = data.distance,
                ),
            )
        } else {
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

        val sample = WorkoutSample(
            timestampSeconds = data.elapsedTime,
            power = data.power,
            cadence = data.cadence,
            speedKph = data.speed,
            heartRate = data.heartRate,
            resistance = data.resistance,
            incline = data.incline,
            calories = data.calories,
            distanceKm = data.distance,
        )
        accumulateSecondFromSample(sample)
    }

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

    private fun triggerAutoStop() {
        collectJob?.cancel()
        collectJob = null
        state.totalTrimmedSeconds += state.consecutiveIdleSeconds
        state.pendingSamples.clear()
        logger.i(TAG, "Auto-stop: ${AUTO_STOP_SECONDS}s idle")
        scope.launch { saveAndReset() }
    }

    private suspend fun saveAndReset() {
        val durationSeconds = state.lastElapsedTime - state.totalTrimmedSeconds
        if (durationSeconds < MIN_DURATION_SECONDS) {
            logger.i(TAG, "Recording discarded (${durationSeconds}s < ${MIN_DURATION_SECONDS}s)")
            state = AccumulationState()
            return
        }

        val profile = profileRepository.activeProfile.value
        if (profile == null) {
            logger.w(TAG, "No active profile, discarding ride")
            state = AccumulationState()
            return
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
            distanceKm = state.lastDistance,
            calories = state.lastCalories,
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
        profileRepository.saveRideSummary(summary, savedSamples)
        logger.i(TAG, "Recording saved: ${durationSeconds}s, ${state.lastDistance}km, ${state.lastCalories}cal, ${savedSamples.size} samples")
        state = AccumulationState()
    }

    private class AccumulationState {
        var startedAtMs = 0L
        var sampleCount = 0L

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
