package com.nettarion.hyperborea.core.ftms

import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Transport-agnostic FTMS notification pump: given one exercise-data sample, encodes and sends
 * every subscribed measurement characteristic in a fixed order — device-type data frame,
 * Training Status (on change only), CPS measurement, RSC measurement. Both broadcast transports
 * (BLE GATT and the WiFi TCP protocol) drive one of these instead of hand-rolling the same loop.
 *
 * Owns the state the loop needs: the [RevolutionCounter] deriving CPS wheel/crank revolutions
 * and the Training-Status change dedup. All entry points serialize on one mutex, so counters
 * can't tear when a tick, a data push, and a subscription event race (the counter itself is
 * single-threaded by contract).
 *
 * One pump per server/client instance and per client lifetime on shared-transport servers:
 * CPS revolutions are cumulative per subscriber session, so reset via [resetCounters] when the
 * subscriber population drops to zero.
 */
class FtmsNotificationPump(
    private val deviceType: DeviceType,
    private val sink: Sink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Characteristic { DATA, TRAINING_STATUS, CPS_MEASUREMENT, RSC_MEASUREMENT }

    /** The transport half: subscription lookup + delivery of one encoded payload. */
    interface Sink {
        fun isSubscribed(characteristic: Characteristic): Boolean
        suspend fun send(characteristic: Characteristic, payload: ByteArray)
    }

    private val mutex = Mutex()
    private val revCounter = RevolutionCounter()
    private var lastTrainingStatus: Byte? = null

    /** Encodes and sends [data] to every currently-subscribed characteristic. */
    suspend fun emit(data: ExerciseData) = mutex.withLock {
        if (sink.isSubscribed(Characteristic.DATA)) {
            sink.send(Characteristic.DATA, FtmsDataEncoder.encodeData(deviceType, data))
        }

        // Training Status is notified on change (per FTMS semantics), not every tick. The dedup
        // starts/resets to null so the first emit after a (re)subscription always sends one.
        if (sink.isSubscribed(Characteristic.TRAINING_STATUS)) {
            val trainingStatus = FtmsDataEncoder.encodeTrainingStatus(data.workoutMode)
            if (trainingStatus[1] != lastTrainingStatus) {
                lastTrainingStatus = trainingStatus[1]
                sink.send(Characteristic.TRAINING_STATUS, trainingStatus)
            }
        }

        if (sink.isSubscribed(Characteristic.CPS_MEASUREMENT)) {
            revCounter.update(data, clock())
            sink.send(
                Characteristic.CPS_MEASUREMENT,
                FtmsDataEncoder.encodeCpsMeasurement(
                    data,
                    revCounter.cumulativeWheelRevs,
                    revCounter.lastWheelEventTime,
                    revCounter.cumulativeCrankRevs,
                    revCounter.lastCrankEventTime,
                ),
            )
        }

        if (sink.isSubscribed(Characteristic.RSC_MEASUREMENT)) {
            sink.send(Characteristic.RSC_MEASUREMENT, FtmsDataEncoder.encodeRscMeasurement(data))
        }
    }

    /**
     * Call when a client (re)enables notifications: forgets the Training-Status dedup so the
     * newcomer receives the current status on the next [emit] instead of waiting for a change.
     */
    suspend fun onSubscriptionChanged() = mutex.withLock {
        lastTrainingStatus = null
    }

    /** Call when the last subscriber disconnects: cumulative CPS counters restart from zero. */
    suspend fun resetCounters() = mutex.withLock {
        revCounter.reset()
        lastTrainingStatus = null
    }
}
