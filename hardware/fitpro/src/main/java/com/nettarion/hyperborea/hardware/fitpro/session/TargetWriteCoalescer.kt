package com.nettarion.hyperborea.hardware.fitpro.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Leading-edge throttle with a latest-value trailing flush for absolute target writes.
 *
 * Absolute targets are throttled with a leading write and latest-value trailing flush, keeping
 * traffic bounded while allowing fine program-ramp targets. Relative button selections can defer
 * until quiet: a held/repeated selection then sends only its final destination instead of asking
 * the deck to settle at every intermediate half-percent. The selected UI target still publishes
 * immediately; motor motion starts after the quiet interval. A new absolute target supersedes a
 * pending relative selection.
 *
 * Dedupe applies only *within* a burst: a pending value equal to the one written when the burst
 * opened is dropped at flush (it was on the wire under a cooldown ago). A quiet-time re-send of
 * the same value IS written again — the console may have self-stepped away from the last written
 * target (physical incline keys), and prod behavior was to re-assert every incoming target.
 *
 * All timing is `delay()`-driven (virtual-time testable). The [write] lambda must not throw —
 * callers wrap transport errors — or the cooldown job dies and the state machine wedges.
 */
internal class TargetWriteCoalescer(
    private val scope: CoroutineScope,
    private val intervalMs: Long,
    private val write: suspend (Float) -> Unit,
) {

    private val mutex = Mutex()
    private var closed = false
    private var lastWritten: Float? = null
    private var pending: Float? = null
    private var cooldownJob: Job? = null
    private var deferred = false

    suspend fun submit(target: Float, deferUntilQuiet: Boolean = false) {
        // The write happens while holding the mutex: [close] then can't return while a write is
        // in flight, so teardown writes are strictly ordered after any coalesced write.
        mutex.withLock {
            if (closed) return
            if (deferUntilQuiet) {
                // Relative buttons select a final destination. Wait for a quiet interval so a
                // held button doesn't repeatedly start and settle the deck at intermediate steps.
                cooldownJob?.cancel()
                deferred = true
                pending = target
                startCooldownLocked()
            } else if (deferred) {
                // A preset, program or external absolute target supersedes a pending selection.
                cooldownJob?.cancel()
                deferred = false
                pending = null
                lastWritten = target
                startCooldownLocked()
                write(target)
            } else if (cooldownJob?.isActive == true) {
                // Mid-burst: remember only the newest value; the cooldown expiry flushes it.
                pending = target
            } else {
                lastWritten = target
                startCooldownLocked()
                write(target)
            }
        }
    }

    /**
     * Permanently shuts the coalescer down, dropping any pending flush. Call at the start of
     * session teardown, *before* any explicit teardown writes (e.g. grade → 0), so a trailing
     * flush can never land after them. Sessions are single-use, so there is no reopen.
     */
    suspend fun close() {
        mutex.withLock {
            closed = true
            cooldownJob?.cancel()
            cooldownJob = null
            pending = null
            deferred = false
        }
    }

    /** Pause drops queued motion while keeping the session usable for a later explicit target. */
    suspend fun cancelPending() = mutex.withLock {
        cooldownJob?.cancel()
        cooldownJob = null
        pending = null
        deferred = false
    }

    private fun startCooldownLocked() {
        cooldownJob = scope.launch {
            delay(intervalMs)
            onCooldownExpired()
        }
    }

    private suspend fun onCooldownExpired() {
        mutex.withLock {
            if (closed) return
            deferred = false
            val value = pending
            pending = null
            if (value != null && value != lastWritten) {
                lastWritten = value
                startCooldownLocked() // sustains one-write-per-interval during a stream
                write(value)
            } else {
                cooldownJob = null
            }
        }
    }
}
