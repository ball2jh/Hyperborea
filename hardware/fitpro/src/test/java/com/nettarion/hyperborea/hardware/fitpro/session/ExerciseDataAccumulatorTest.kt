package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class ExerciseDataAccumulatorTest {

    private var fakeTime = 1000L
    private lateinit var accumulator: ExerciseDataAccumulator

    @Before
    fun setUp() {
        fakeTime = 1000L
        accumulator = ExerciseDataAccumulator(clock = { fakeTime })
    }

    @Test
    fun `snapshot returns null fields initially`() {
        accumulator.start()
        val snapshot = accumulator.snapshot()
        assertThat(snapshot.power).isNull()
        assertThat(snapshot.cadence).isNull()
        assertThat(snapshot.speed).isNull()
        assertThat(snapshot.resistance).isNull()
        assertThat(snapshot.incline).isNull()
        assertThat(snapshot.heartRate).isNull()
        assertThat(snapshot.distance).isNull()
        assertThat(snapshot.calories).isNull()
    }

    @Test
    fun `updatePower is reflected in snapshot`() {
        accumulator.start()
        accumulator.updatePower(200)
        assertThat(accumulator.snapshot().power).isEqualTo(200)
    }

    @Test
    fun `updateCadence is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateCadence(85)
        assertThat(accumulator.snapshot().cadence).isEqualTo(85)
    }

    @Test
    fun `updateSpeed is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateSpeed(25.5f)
        assertThat(accumulator.snapshot().speed).isEqualTo(25.5f)
    }

    @Test
    fun `updateResistance is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateResistance(15)
        assertThat(accumulator.snapshot().resistance).isEqualTo(15)
    }

    @Test
    fun `updateIncline is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateIncline(-3.5f)
        assertThat(accumulator.snapshot().incline).isEqualTo(-3.5f)
    }

    @Test
    fun `updateHeartRate is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateHeartRate(145)
        assertThat(accumulator.snapshot().heartRate).isEqualTo(145)
    }

    @Test
    fun `updateDistance is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateDistance(12.3f)
        assertThat(accumulator.snapshot().distance).isEqualTo(12.3f)
    }

    @Test
    fun `updateCalories is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateCalories(350)
        assertThat(accumulator.snapshot().calories).isEqualTo(350)
    }

    @Test
    fun `elapsedTime is zero before first cadence`() {
        accumulator.start()
        fakeTime = 11000L
        assertThat(accumulator.snapshot().elapsedTime).isEqualTo(0)
    }

    @Test
    fun `elapsedTime starts from first non-zero cadence`() {
        accumulator.start()
        accumulator.updateCadence(60) // starts clock at fakeTime=1000
        fakeTime = 11000L              // 10 seconds later
        assertThat(accumulator.snapshot().elapsedTime).isEqualTo(10)
    }

    @Test
    fun `pause freezes elapsed time`() {
        accumulator.start()
        accumulator.updateCadence(60) // starts clock at 1000
        fakeTime = 11000L              // 10 seconds
        accumulator.pause()
        fakeTime = 21000L              // 10 more seconds while paused
        assertThat(accumulator.snapshot().elapsedTime).isEqualTo(10)
    }

    @Test
    fun `resume continues elapsed time after pause`() {
        accumulator.start()
        accumulator.updateCadence(60) // starts clock at 1000
        fakeTime = 11000L              // 10 seconds
        accumulator.pause()
        fakeTime = 21000L              // paused for 10 seconds
        accumulator.resume()           // resumes at 21000
        fakeTime = 26000L              // 5 more seconds
        assertThat(accumulator.snapshot().elapsedTime).isEqualTo(15)
    }

    @Test
    fun `multiple updates accumulate correctly`() {
        accumulator.start()
        accumulator.updatePower(100)
        accumulator.updateCadence(80)
        accumulator.updateSpeed(20.0f)

        val snapshot = accumulator.snapshot()
        assertThat(snapshot.power).isEqualTo(100)
        assertThat(snapshot.cadence).isEqualTo(80)
        assertThat(snapshot.speed).isEqualTo(20.0f)
    }

    @Test
    fun `update overwrites previous value`() {
        accumulator.start()
        accumulator.updatePower(100)
        accumulator.updatePower(200)
        assertThat(accumulator.snapshot().power).isEqualTo(200)
    }

    @Test
    fun `reset clears all fields`() {
        accumulator.start()
        accumulator.updatePower(200)
        accumulator.updateCadence(85)
        accumulator.updateSpeed(25.5f)
        accumulator.updateResistance(15)

        accumulator.reset()
        val snapshot = accumulator.snapshot()
        assertThat(snapshot.power).isNull()
        assertThat(snapshot.cadence).isNull()
        assertThat(snapshot.speed).isNull()
        assertThat(snapshot.resistance).isNull()
        assertThat(snapshot.elapsedTime).isEqualTo(0)
    }

    @Test
    fun `updateTargetPower is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateTargetPower(200)
        assertThat(accumulator.snapshot().targetPower).isEqualTo(200)
    }

    @Test
    fun `updateTargetResistance is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateTargetResistance(15)
        assertThat(accumulator.snapshot().targetResistance).isEqualTo(15)
    }

    @Test
    fun `updateWorkoutMode is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateWorkoutMode(1)
        assertThat(accumulator.snapshot().workoutMode).isEqualTo(1)
    }

    @Test
    fun `updateLifetimeRunningTime is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateLifetimeRunningTime(86400)
        assertThat(accumulator.snapshot().lifetimeRunningTime).isEqualTo(86400)
    }

    @Test
    fun `reset clears target and lifetime fields`() {
        accumulator.start()
        accumulator.updateTargetPower(200)
        accumulator.updateTargetResistance(15)
        accumulator.updateTargetSpeed(25.5f)
        accumulator.updateTargetIncline(8.0f)
        accumulator.updateWorkoutMode(1)
        accumulator.updateLifetimeRunningTime(86400)
        accumulator.updateLifetimeDistance(1234.5f)
        accumulator.updateLifetimeCalories(56789)

        accumulator.reset()
        val snapshot = accumulator.snapshot()
        assertThat(snapshot.targetPower).isNull()
        assertThat(snapshot.targetResistance).isNull()
        assertThat(snapshot.targetSpeed).isNull()
        assertThat(snapshot.targetIncline).isNull()
        assertThat(snapshot.workoutMode).isNull()
        assertThat(snapshot.lifetimeRunningTime).isNull()
        assertThat(snapshot.lifetimeDistance).isNull()
        assertThat(snapshot.lifetimeCalories).isNull()
    }

    @Test
    fun `pause during snapshot does not produce invalid elapsed time`() {
        accumulator.start()
        accumulator.updateCadence(60) // starts clock at 1000
        fakeTime = 6000L              // 5 seconds
        val snapshot = accumulator.snapshot()
        assertThat(snapshot.elapsedTime).isEqualTo(5)

        // Pause after snapshot — next snapshot should freeze time
        accumulator.pause()
        fakeTime = 16000L
        assertThat(accumulator.snapshot().elapsedTime).isEqualTo(5)
    }

    @Test
    fun `multiple pause resume cycles accumulate correctly`() {
        accumulator.start()
        accumulator.updateCadence(60) // starts clock at 1000
        fakeTime = 6000L              // 5 seconds running
        accumulator.pause()
        fakeTime = 11000L             // 5 seconds paused
        accumulator.resume()          // resumes at 11000
        fakeTime = 14000L             // 3 more seconds running
        accumulator.pause()
        fakeTime = 20000L             // 6 seconds paused
        accumulator.resume()          // resumes at 20000
        fakeTime = 22000L             // 2 more seconds running
        assertThat(accumulator.snapshot().elapsedTime).isEqualTo(10) // 5 + 3 + 2
    }

    @Test
    fun `targetSpeed is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateTargetSpeed(25.5f)
        assertThat(accumulator.snapshot().targetSpeed).isEqualTo(25.5f)
    }

    @Test
    fun `targetIncline is reflected in snapshot`() {
        accumulator.start()
        accumulator.updateTargetIncline(8.0f)
        assertThat(accumulator.snapshot().targetIncline).isEqualTo(8.0f)
    }

    @Test
    fun `startTimer starts clock without cadence`() {
        accumulator.start()
        accumulator.startTimer()
        fakeTime = 6000L  // 5 seconds
        assertThat(accumulator.snapshot().elapsedTime).isEqualTo(5)
    }

    @Test
    fun `startTimer is no-op when already running`() {
        accumulator.start()
        accumulator.updateCadence(60) // starts at 1000
        fakeTime = 6000L              // 5 seconds
        accumulator.startTimer()      // should be no-op since runningStartTime > 0
        fakeTime = 11000L             // 10 seconds total
        assertThat(accumulator.snapshot().elapsedTime).isEqualTo(10)
    }

    @Test
    fun `startTimer is no-op when paused`() {
        accumulator.start()
        accumulator.updateCadence(60) // starts at 1000
        fakeTime = 6000L              // 5 seconds
        accumulator.pause()
        accumulator.startTimer()      // should be no-op since paused=true
        fakeTime = 16000L
        assertThat(accumulator.snapshot().elapsedTime).isEqualTo(5)
    }

    @Test
    fun `initialElapsedSeconds carries over time`() {
        val acc = ExerciseDataAccumulator(clock = { fakeTime }, initialElapsedSeconds = 120L)
        acc.start()
        acc.startTimer()              // starts at fakeTime=1000
        fakeTime = 11000L             // 10 seconds later
        assertThat(acc.snapshot().elapsedTime).isEqualTo(130) // 120 + 10
    }

    @Test
    fun `initialElapsedSeconds with no timer running`() {
        val acc = ExerciseDataAccumulator(clock = { fakeTime }, initialElapsedSeconds = 60L)
        assertThat(acc.snapshot().elapsedTime).isEqualTo(60)
    }
}
