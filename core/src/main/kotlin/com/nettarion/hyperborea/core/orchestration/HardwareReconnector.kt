package com.nettarion.hyperborea.core.orchestration

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.RetryPolicy
import com.nettarion.hyperborea.core.adapter.AdapterState
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Reconnect-with-backoff engine for transient hardware dropouts (the bike power-cycles its USB
 * port roughly every 20 s when no session holds it). Owns the elapsed-time preservation across
 * the dropout; the [Orchestrator] owns the surrounding policy — when a dropout warrants a
 * reconnect and what the orchestrator state becomes afterwards.
 */
internal class HardwareReconnector(
    private val hardwareAdapter: HardwareAdapter,
    private val retryPolicy: RetryPolicy,
    private val logger: AppLogger,
) {
    val maxAttempts: Int get() = retryPolicy.maxAttempts

    private var preservedElapsedSeconds = 0L

    /** Clears the preserved elapsed time. Call when a workout ends. */
    fun reset() {
        preservedElapsedSeconds = 0L
    }

    /**
     * Runs one reconnect episode: captures the workout clock, then retries
     * disconnect → seed elapsed → connect with backoff until the adapter reports Active or
     * [retryPolicy] is exhausted. Returns true when reconnected. [shouldContinue] is polled
     * between attempts so the caller can abandon the episode (e.g. the user stopped the workout
     * mid-dropout); returning false from it aborts with a `false` result.
     */
    suspend fun reconnect(shouldContinue: () -> Boolean): Boolean {
        // elapsedTime is cumulative for the whole workout: a reconnected session is seeded with
        // the preserved value and reports it back *included* in its own elapsed. So ASSIGN the
        // last-seen value — adding would double-count the preserved portion on every dropout
        // after the first. Captured once per episode, before the retry loop: disconnect() clears
        // exerciseData, so later attempts would read null. A null/zero read keeps the previous
        // preserved value (the adapter may already have cleared its data by the time we run).
        val lastSeen = hardwareAdapter.exerciseData.value
        if (lastSeen != null && lastSeen.elapsedTime > 0) {
            preservedElapsedSeconds = lastSeen.elapsedTime
        }

        for (attempt in 1..retryPolicy.maxAttempts) {
            delay(retryPolicy.delayForAttempt(attempt))
            hardwareAdapter.disconnect()
            hardwareAdapter.setInitialElapsedTime(preservedElapsedSeconds)
            hardwareAdapter.connect()
            coroutineContext.ensureActive()
            if (!shouldContinue()) return false
            if (hardwareAdapter.state.value is AdapterState.Active) {
                logger.i(TAG, "Hardware reconnected on attempt $attempt")
                return true
            }
        }
        return false
    }

    private companion object {
        const val TAG = "HardwareReconnector"
    }
}
