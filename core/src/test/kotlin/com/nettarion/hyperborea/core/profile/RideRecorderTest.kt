package com.nettarion.hyperborea.core.profile

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.Profile
import com.nettarion.hyperborea.core.model.RideSummary
import com.nettarion.hyperborea.core.model.WorkoutSample

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.test.buildExerciseData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class RideRecorderTest {

    @Test
    fun `saves summary when session exceeds minimum duration`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // Emit samples over 120s
        dataFlow.emit(buildExerciseData(power = 100, cadence = 80, speed = 25f, heartRate = 140, distance = 1.0f, calories = 50, elapsedTime = 60))
        dataFlow.emit(buildExerciseData(power = 200, cadence = 90, speed = 30f, heartRate = 160, distance = 2.0f, calories = 100, elapsedTime = 120))

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        val summary = repo.savedSummaries[0]
        assertThat(summary.profileId).isEqualTo(1L)
        assertThat(summary.durationSeconds).isEqualTo(120)
        assertThat(summary.distanceKm).isEqualTo(2.0f)
        assertThat(summary.calories).isEqualTo(100)
        assertThat(summary.avgPower).isEqualTo(150)
        assertThat(summary.maxPower).isEqualTo(200)
        assertThat(summary.avgCadence).isEqualTo(85)
        assertThat(summary.maxCadence).isEqualTo(90)
        assertThat(summary.maxSpeedKph).isEqualTo(30f)
        assertThat(summary.avgHeartRate).isEqualTo(150)
        assertThat(summary.maxHeartRate).isEqualTo(160)
    }

    @Test
    fun `discards session shorter than minimum duration`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        dataFlow.emit(buildExerciseData(power = 100, elapsedTime = 30))

        recorder.stop()

        assertThat(repo.savedSummaries).isEmpty()
    }

    @Test
    fun `discards session when no active profile`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = null)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        dataFlow.emit(buildExerciseData(power = 100, elapsedTime = 120))

        recorder.stop()

        assertThat(repo.savedSummaries).isEmpty()
    }

    @Test
    fun `handles null metric fields gracefully`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // Active data (power/cadence/speed non-zero) but no HR/resistance/incline
        dataFlow.emit(buildExerciseData(power = 100, cadence = 80, speed = 25f, distance = 5.0f, calories = 200, elapsedTime = 120))

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        val summary = repo.savedSummaries[0]
        assertThat(summary.avgPower).isEqualTo(100)
        assertThat(summary.avgHeartRate).isNull()
        assertThat(summary.avgResistance).isNull()
        assertThat(summary.avgIncline).isNull()
    }

    @Test
    fun `double start is no-op`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow1 = MutableSharedFlow<ExerciseData>()
        val dataFlow2 = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow1)
        recorder.start(dataFlow2)

        dataFlow1.emit(buildExerciseData(power = 100, elapsedTime = 120))

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
    }

    @Test
    fun `resistance and incline avg and max computed correctly`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        dataFlow.emit(buildExerciseData(power = 100, resistance = 5, incline = 2.0f, elapsedTime = 60))
        dataFlow.emit(buildExerciseData(power = 100, resistance = 10, incline = 6.0f, elapsedTime = 120))
        dataFlow.emit(buildExerciseData(power = 100, resistance = 15, incline = 4.0f, elapsedTime = 180))

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        val summary = repo.savedSummaries[0]
        assertThat(summary.avgResistance).isEqualTo(10) // (5+10+15)/3
        assertThat(summary.maxResistance).isEqualTo(15)
        assertThat(summary.avgIncline).isEqualTo(4.0f) // (2+6+4)/3
        assertThat(summary.maxIncline).isEqualTo(6.0f)
    }

    @Test
    fun `normalized power calculated correctly with 30s window`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // Emit 60 seconds of constant 200W — NP should equal 200W for constant power
        for (sec in 1L..60L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        val summary = repo.savedSummaries[0]
        assertThat(summary.normalizedPower).isEqualTo(200)
    }

    @Test
    fun `NP is null when fewer than 30 power samples`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // Only 6 unique seconds — well under the 30s NP window
        for (sec in listOf(1L, 2L, 3L, 4L, 5L, 6L)) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }
        // Jump to meet minimum duration
        dataFlow.emit(buildExerciseData(power = 200, elapsedTime = 61))

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        assertThat(repo.savedSummaries[0].normalizedPower).isNull()
    }

    @Test
    fun `NP computed at exactly 30s boundary`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // Emit seconds 1-30 at constant 200W — buffer fills at sample 30, one np4 contribution
        for (sec in 1L..30L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }
        // Jump to 61 to meet minimum duration — second np4 contribution
        dataFlow.emit(buildExerciseData(power = 200, elapsedTime = 61))

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        assertThat(repo.savedSummaries[0].normalizedPower).isEqualTo(200)
    }

    @Test
    fun `elevation gain accumulated from positive incline and distance deltas`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // Flat: 0% incline, 0→1km
        dataFlow.emit(buildExerciseData(speed = 10f, incline = 0.0f, distance = 0.0f, elapsedTime = 1))
        dataFlow.emit(buildExerciseData(speed = 10f, incline = 0.0f, distance = 1.0f, elapsedTime = 30))

        // Uphill: 10% incline, 1→2km — should gain elevation
        dataFlow.emit(buildExerciseData(speed = 10f, incline = 10.0f, distance = 2.0f, elapsedTime = 60))

        // Downhill: -5% incline, 2→3km — negative incline, no gain
        dataFlow.emit(buildExerciseData(speed = 10f, incline = -5.0f, distance = 3.0f, elapsedTime = 90))

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        val summary = repo.savedSummaries[0]

        // Expected: 1km at 10% incline → sin(atan(0.10)) * 1000m ≈ 99.5m
        val expectedGain = (sin(atan(0.10)) * 1000.0).toFloat()
        assertThat(summary.totalElevationGainMeters).isNotNull()
        assertThat(summary.totalElevationGainMeters!!.toDouble()).isWithin(0.5).of(expectedGain.toDouble())
    }

    @Test
    fun `time-series samples recorded once per elapsed second`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // Simulate 100ms poll: multiple emissions at the same elapsed second
        dataFlow.emit(buildExerciseData(power = 100, elapsedTime = 1))
        dataFlow.emit(buildExerciseData(power = 110, elapsedTime = 1)) // same second, ignored
        dataFlow.emit(buildExerciseData(power = 120, elapsedTime = 1)) // same second, ignored
        dataFlow.emit(buildExerciseData(power = 200, elapsedTime = 2))
        dataFlow.emit(buildExerciseData(power = 300, elapsedTime = 61)) // skip ahead

        recorder.stop()

        assertThat(repo.lastSavedSamples).hasSize(3) // seconds 1, 2, 61
        assertThat(repo.lastSavedSamples!![0].timestampSeconds).isEqualTo(1)
        assertThat(repo.lastSavedSamples!![0].power).isEqualTo(100) // first emission at second 1
        assertThat(repo.lastSavedSamples!![1].timestampSeconds).isEqualTo(2)
        assertThat(repo.lastSavedSamples!![2].timestampSeconds).isEqualTo(61)
    }

    @Test
    fun `IF and TSS computed when FTP is set`() = runTest {
        val ftp = 200
        val repo = FakeProfileRepository(activeProfileId = 1L, ftpWatts = ftp)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // 60 seconds of constant 200W — NP = 200, IF = 200/200 = 1.0
        for (sec in 1L..60L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        val summary = repo.savedSummaries[0]
        assertThat(summary.normalizedPower).isEqualTo(200)
        assertThat(summary.intensityFactor).isWithin(0.01f).of(1.0f)
        // TSS = (60 * 1.0^2 * 100) / 3600 ≈ 1.67
        assertThat(summary.trainingStressScore).isWithin(0.1f).of(1.67f)
    }

    @Test
    fun `IF and TSS null when FTP is not set`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L, ftpWatts = null)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        for (sec in 1L..60L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        val summary = repo.savedSummaries[0]
        assertThat(summary.normalizedPower).isEqualTo(200)
        assertThat(summary.intensityFactor).isNull()
        assertThat(summary.trainingStressScore).isNull()
    }

    // --- Idle detection and auto-stop ---

    @Test
    fun `auto-stops after 300 consecutive idle seconds`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // 60s active
        for (sec in 1L..60L) {
            dataFlow.emit(buildExerciseData(power = 150, elapsedTime = sec))
        }
        // 300s idle — triggers auto-stop
        for (sec in 61L..360L) {
            dataFlow.emit(buildExerciseData(elapsedTime = sec))
        }

        // Auto-stop should have fired — don't call stop()
        assertThat(repo.savedSummaries).hasSize(1)
        val summary = repo.savedSummaries[0]
        assertThat(summary.durationSeconds).isEqualTo(60) // 360 - 300 trimmed
        assertThat(repo.lastSavedSamples).hasSize(60)
    }

    @Test
    fun `auto-stop discards when active duration below minimum`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // 30s active — below minimum
        for (sec in 1L..30L) {
            dataFlow.emit(buildExerciseData(power = 150, elapsedTime = sec))
        }
        // 300s idle — triggers auto-stop
        for (sec in 31L..330L) {
            dataFlow.emit(buildExerciseData(elapsedTime = sec))
        }

        assertThat(repo.savedSummaries).isEmpty()
    }

    @Test
    fun `short idle under 60s is kept`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // 60s active
        for (sec in 1L..60L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }
        // 30s idle — short pause, should be kept
        for (sec in 61L..90L) {
            dataFlow.emit(buildExerciseData(elapsedTime = sec))
        }
        // 60s active
        for (sec in 91L..150L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        assertThat(repo.savedSummaries[0].durationSeconds).isEqualTo(150)
        assertThat(repo.lastSavedSamples).hasSize(150) // 60 active + 30 idle + 60 active
    }

    @Test
    fun `long idle 60s or more is trimmed`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // 60s active
        for (sec in 1L..60L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }
        // 90s idle — long pause, should be trimmed
        for (sec in 61L..150L) {
            dataFlow.emit(buildExerciseData(elapsedTime = sec))
        }
        // 60s active
        for (sec in 151L..210L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        assertThat(repo.savedSummaries[0].durationSeconds).isEqualTo(120) // 210 - 90 trimmed
        assertThat(repo.lastSavedSamples).hasSize(120) // 60 + 60, idle excluded
    }

    @Test
    fun `idle boundary at exactly 59s is kept`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // 60s active
        for (sec in 1L..60L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }
        // 59s idle — just under threshold
        for (sec in 61L..119L) {
            dataFlow.emit(buildExerciseData(elapsedTime = sec))
        }
        // 2s active
        for (sec in 120L..121L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        assertThat(repo.savedSummaries[0].durationSeconds).isEqualTo(121) // all kept
    }

    @Test
    fun `idle boundary at exactly 60s is trimmed`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // 60s active
        for (sec in 1L..60L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }
        // 60s idle — exactly at threshold, should be trimmed
        for (sec in 61L..120L) {
            dataFlow.emit(buildExerciseData(elapsedTime = sec))
        }
        // 2s active
        for (sec in 121L..122L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        assertThat(repo.savedSummaries[0].durationSeconds).isEqualTo(62) // 122 - 60 trimmed
    }

    @Test
    fun `averages exclude trimmed idle data`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // 30s at 200W
        for (sec in 1L..30L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }
        // 120s idle — trimmed
        for (sec in 31L..150L) {
            dataFlow.emit(buildExerciseData(elapsedTime = sec))
        }
        // 30s at 100W
        for (sec in 151L..180L) {
            dataFlow.emit(buildExerciseData(power = 100, elapsedTime = sec))
        }

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        val summary = repo.savedSummaries[0]
        assertThat(summary.avgPower).isEqualTo(150) // (200*30 + 100*30) / 60, not diluted by zeros
        assertThat(summary.durationSeconds).isEqualTo(60) // 180 - 120 trimmed
    }

    @Test
    fun `explicit stop during long idle flushes pending`() = runTest {
        val repo = FakeProfileRepository(activeProfileId = 1L)
        val logger = NoOpLogger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val recorder = RideRecorder(repo, logger, scope)

        val dataFlow = MutableSharedFlow<ExerciseData>()
        recorder.start(dataFlow)

        // 60s active
        for (sec in 1L..60L) {
            dataFlow.emit(buildExerciseData(power = 200, elapsedTime = sec))
        }
        // 120s idle — pending, but user stops explicitly
        for (sec in 61L..180L) {
            dataFlow.emit(buildExerciseData(elapsedTime = sec))
        }

        recorder.stop()

        assertThat(repo.savedSummaries).hasSize(1)
        assertThat(repo.savedSummaries[0].durationSeconds).isEqualTo(180) // explicit stop keeps all
        assertThat(repo.lastSavedSamples).hasSize(180)
    }

    // --- Fakes ---

    private class FakeProfileRepository(
        activeProfileId: Long?,
        ftpWatts: Int? = null,
    ) : ProfileRepository {
        override val profiles: Flow<List<Profile>> = MutableStateFlow(emptyList())
        override val activeProfile: StateFlow<Profile?> = MutableStateFlow(
            activeProfileId?.let { Profile(id = it, name = "Test", ftpWatts = ftpWatts) },
        )
        val savedSummaries = mutableListOf<RideSummary>()
        var lastSavedSamples: List<WorkoutSample>? = null

        override suspend fun createProfile(name: String) = Profile(id = 1, name = name)
        override suspend fun updateProfile(profile: Profile) {}
        override suspend fun deleteProfile(id: Long) {}
        override suspend fun setActiveProfile(id: Long) {}
        override fun getRideSummaries(profileId: Long): Flow<List<RideSummary>> = MutableStateFlow(emptyList())
        override suspend fun saveRideSummary(summary: RideSummary, samples: List<WorkoutSample>) {
            savedSummaries.add(summary)
            lastSavedSamples = samples
        }
        override suspend fun deleteRideSummary(id: Long) {}
        override fun getWorkoutSamples(rideId: Long): Flow<List<WorkoutSample>> = MutableStateFlow(emptyList())
    }

    private class NoOpLogger : AppLogger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }
}
