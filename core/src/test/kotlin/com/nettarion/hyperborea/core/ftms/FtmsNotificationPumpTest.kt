package com.nettarion.hyperborea.core.ftms

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.ftms.FtmsNotificationPump.Characteristic
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.test.buildExerciseData
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FtmsNotificationPumpTest {

    private class RecordingSink(
        var subscribed: Set<Characteristic> = Characteristic.entries.toSet(),
    ) : FtmsNotificationPump.Sink {
        val sent = mutableListOf<Pair<Characteristic, ByteArray>>()
        override fun isSubscribed(characteristic: Characteristic) = characteristic in subscribed
        override suspend fun send(characteristic: Characteristic, payload: ByteArray) {
            sent.add(characteristic to payload)
        }
    }

    private fun pumpWith(sink: RecordingSink) =
        FtmsNotificationPump(DeviceType.BIKE, sink, clock = { 1_000L })

    @Test
    fun `emits data, training status, CPS and RSC to subscribed characteristics in order`() = runTest {
        val sink = RecordingSink()
        val pump = pumpWith(sink)

        pump.emit(ExerciseData.ZERO)

        assertThat(sink.sent.map { it.first }).containsExactly(
            Characteristic.DATA,
            Characteristic.TRAINING_STATUS,
            Characteristic.CPS_MEASUREMENT,
            Characteristic.RSC_MEASUREMENT,
        ).inOrder()
    }

    @Test
    fun `skips unsubscribed characteristics`() = runTest {
        val sink = RecordingSink(subscribed = setOf(Characteristic.DATA))
        val pump = pumpWith(sink)

        pump.emit(ExerciseData.ZERO)

        assertThat(sink.sent.map { it.first }).containsExactly(Characteristic.DATA)
    }

    @Test
    fun `training status is sent on change only, and again after a new subscription`() = runTest {
        val sink = RecordingSink(subscribed = setOf(Characteristic.TRAINING_STATUS))
        val pump = pumpWith(sink)

        pump.emit(buildExerciseData(power = 100))       // first emit — sends
        pump.emit(buildExerciseData(power = 120))       // same workoutMode — deduped
        assertThat(sink.sent).hasSize(1)

        pump.emit(buildExerciseData(power = 120).copy(workoutMode = 2)) // mode change — sends
        assertThat(sink.sent).hasSize(2)

        // A client (re)subscribes: the current status must be resent even though unchanged.
        pump.onSubscriptionChanged()
        pump.emit(buildExerciseData(power = 120).copy(workoutMode = 2))
        assertThat(sink.sent).hasSize(3)
    }

    @Test
    fun `resetCounters restarts the CPS revolution baseline`() = runTest {
        val sink = RecordingSink(subscribed = setOf(Characteristic.CPS_MEASUREMENT))
        var now = 0L
        val pump = FtmsNotificationPump(DeviceType.BIKE, sink, clock = { now })

        val riding = buildExerciseData(power = 150, cadence = 90, speed = 30f)
        repeat(10) { now += 1_000; pump.emit(riding) }
        val beforeReset = sink.sent.last().second.copyOf()

        pump.resetCounters()
        sink.sent.clear()
        now += 1_000
        pump.emit(riding)

        // Cumulative crank/wheel revolutions restarted — the frame differs from the pre-reset one.
        assertThat(sink.sent.single().second).isNotEqualTo(beforeReset)
    }
}
