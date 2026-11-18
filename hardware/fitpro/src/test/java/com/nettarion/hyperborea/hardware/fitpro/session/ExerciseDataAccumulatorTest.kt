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

    @Test fun `paused belt counters survive repeated controller resets without losing the workout`() {
        accumulator.updateDistance(2f); accumulator.updateCalories(100); accumulator.updateVerticalGain(20f)
        accumulator.preserveCountersAcrossReset()
        accumulator.updateDistance(0f); accumulator.updateCalories(0); accumulator.updateVerticalGain(0f)
        accumulator.updateDistance(.5f); accumulator.updateCalories(25); accumulator.updateVerticalGain(5f)
        assertThat(accumulator.snapshot().distance).isEqualTo(2.5f)
        assertThat(accumulator.snapshot().calories).isEqualTo(125)
        assertThat(accumulator.snapshot().verticalGain).isEqualTo(25f)
        accumulator.updateDistance(0f); accumulator.updateCalories(0); accumulator.updateVerticalGain(0f)
        assertThat(accumulator.snapshot().distance).isEqualTo(2.5f)
        assertThat(accumulator.snapshot().calories).isEqualTo(125)
        assertThat(accumulator.snapshot().verticalGain).isEqualTo(25f)
        accumulator.reset(); accumulator.updateDistance(1f); accumulator.updateCalories(10)
        assertThat(accumulator.snapshot().distance).isEqualTo(1f)
        assertThat(accumulator.snapshot().calories).isEqualTo(10)
    }

    @Test fun `counters hold while paused even though the console keeps counting`() {
        accumulator.holdCountersWhilePaused = true
        // Field report: a paused treadmill console keeps integrating its commanded speed.
        accumulator.updateDistance(1f); accumulator.updateCalories(80); accumulator.updateVerticalGain(10f)
        accumulator.pause()
        accumulator.updateDistance(1.4f); accumulator.updateCalories(110); accumulator.updateVerticalGain(14f)
        accumulator.updateDistance(1.9f); accumulator.updateCalories(150); accumulator.updateVerticalGain(19f)
        assertThat(accumulator.snapshot().distance).isEqualTo(1f)
        assertThat(accumulator.snapshot().calories).isEqualTo(80)
        assertThat(accumulator.snapshot().verticalGain).isEqualTo(10f)

        // Resuming carries on from the held value, not from the console's inflated one.
        accumulator.resume()
        accumulator.updateDistance(2.0f); accumulator.updateCalories(158); accumulator.updateVerticalGain(20f)
        assertThat(accumulator.snapshot().distance).isWithin(1e-4f).of(1.1f)
        assertThat(accumulator.snapshot().calories).isEqualTo(88)
        assertThat(accumulator.snapshot().verticalGain).isWithin(1e-4f).of(11f)
    }

    @Test fun `a second pause holds again and the paused drift never accumulates`() {
        accumulator.holdCountersWhilePaused = true
        accumulator.updateDistance(1f)
        accumulator.pause(); accumulator.updateDistance(1.5f); accumulator.resume()
        accumulator.updateDistance(1.7f)
        accumulator.pause(); accumulator.updateDistance(2.7f); accumulator.resume()
        accumulator.updateDistance(2.8f)
        assertThat(accumulator.snapshot().distance).isWithin(1e-4f).of(1.3f)
    }

    @Test fun `paused counters survive the controller zeroing them and resume from the held value`() {
        accumulator.holdCountersWhilePaused = true
        accumulator.updateDistance(2f); accumulator.updateCalories(100)
        accumulator.preserveCountersAcrossReset(); accumulator.pause()
        accumulator.updateDistance(2.6f); accumulator.updateCalories(140)
        // The controller's own pause timer expires: counters restart from zero and keep climbing.
        accumulator.updateDistance(0f); accumulator.updateCalories(0)
        accumulator.updateDistance(.3f); accumulator.updateCalories(20)
        assertThat(accumulator.snapshot().distance).isEqualTo(2f)
        assertThat(accumulator.snapshot().calories).isEqualTo(100)
        accumulator.resume()
        accumulator.updateDistance(.5f); accumulator.updateCalories(35)
        assertThat(accumulator.snapshot().distance).isWithin(1e-4f).of(2.2f)
        assertThat(accumulator.snapshot().calories).isEqualTo(115)
    }

    @Test fun `a parked console's stale counters are still dropped when a new workout starts`() {
        accumulator.holdCountersWhilePaused = true
        // DMK while merely parked pauses the clock without the continuity policy: the previous
        // console workout's totals must not be frozen into the new one.
        accumulator.pause()
        accumulator.updateDistance(3f); accumulator.updateCalories(250)
        assertThat(accumulator.snapshot().distance).isEqualTo(3f)
        accumulator.updateDistance(0f); accumulator.updateCalories(0)
        accumulator.resume()
        accumulator.updateDistance(.2f); accumulator.updateCalories(12)
        assertThat(accumulator.snapshot().distance).isEqualTo(.2f)
        assertThat(accumulator.snapshot().calories).isEqualTo(12)
    }

    @Test fun `other equipment keeps following the console while paused`() {
        // A paused bike's flywheel can still be turning: its distance is real, not phantom.
        accumulator.updateDistance(1f); accumulator.updateCalories(80)
        accumulator.pause()
        accumulator.updateDistance(1.4f); accumulator.updateCalories(110)
        assertThat(accumulator.snapshot().distance).isEqualTo(1.4f)
        assertThat(accumulator.snapshot().calories).isEqualTo(110)
    }

    @Test fun `other equipment counters retain raw reset behaviour without belt pause policy`() {
        accumulator.updateDistance(2f); accumulator.updateCalories(100); accumulator.updateVerticalGain(20f)
        accumulator.updateDistance(0f); accumulator.updateCalories(0); accumulator.updateVerticalGain(0f)
        assertThat(accumulator.snapshot().distance).isEqualTo(0f)
        assertThat(accumulator.snapshot().calories).isEqualTo(0)
        assertThat(accumulator.snapshot().verticalGain).isEqualTo(0f)
    }

    @Test fun `pause before first movement cannot start elapsed clock from later cadence`() {
        accumulator.pause(); fakeTime += 3_600_000; accumulator.updateCadence(60)
        assertThat(accumulator.snapshot().elapsedTime).isEqualTo(0)
        accumulator.resume(); fakeTime += 2000
        assertThat(accumulator.snapshot().elapsedTime).isEqualTo(2)
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
    fun `reset clears target fields`() {
        accumulator.start()
        accumulator.updateTargetPower(200)
        accumulator.updateTargetResistance(15)
        accumulator.updateTargetSpeed(25.5f)
        accumulator.updateTargetIncline(8.0f)
        accumulator.updateWorkoutMode(1)

        accumulator.reset()
        val snapshot = accumulator.snapshot()
        assertThat(snapshot.targetPower).isNull()
        assertThat(snapshot.targetResistance).isNull()
        assertThat(snapshot.targetSpeed).isNull()
        assertThat(snapshot.targetIncline).isNull()
        assertThat(snapshot.workoutMode).isNull()
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
