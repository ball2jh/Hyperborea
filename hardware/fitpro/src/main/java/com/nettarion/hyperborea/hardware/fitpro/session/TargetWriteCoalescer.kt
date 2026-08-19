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
 * The incline deck settles at every target it receives — there is no protocol ramp-rate field —
 * so a stream of stepped targets (a held +/− button repeating every ~350 ms, or Zwift SIM's
 * gradient stream) makes the motor visibly stop at each intermediate value. The stock app avoids
 * this by keeping one *moving* target ahead of the deck. This class reproduces that: the first
 * value in a burst is written immediately (single taps stay instant); values arriving inside the
 * cooldown window replace each other (latest wins) and the survivor is written when the window
 * expires — which opens the next window, so a sustained stream yields exactly one write per
 * interval, always carrying the newest target, and the final value is guaranteed to land.
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

    suspend fun submit(target: Float) {
        // The write happens while holding the mutex: [close] then can't return while a write is
        // in flight, so teardown writes are strictly ordered after any coalesced write.
        mutex.withLock {
            if (closed) return
            if (cooldownJob?.isActive == true) {
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
        }
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
