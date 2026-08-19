package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TargetWriteCoalescerTest {

    private val written = mutableListOf<Float>()

    @Test
    fun `first submit writes immediately`() = runTest {
        val coalescer = TargetWriteCoalescer(backgroundScope, INTERVAL) { written.add(it) }

        coalescer.submit(1.0f)

        assertThat(written).containsExactly(1.0f)
    }

    @Test
    fun `burst inside the interval writes only first and latest`() = runTest {
        val coalescer = TargetWriteCoalescer(backgroundScope, INTERVAL) { written.add(it) }

        coalescer.submit(1.0f)
        advanceTimeBy(100); coalescer.submit(1.5f)
        advanceTimeBy(100); coalescer.submit(2.0f)
        advanceTimeBy(100); coalescer.submit(2.5f)
        advanceTimeBy(INTERVAL * 2) // run the background cooldown flush

        assertThat(written).containsExactly(1.0f, 2.5f).inOrder()
    }

    @Test
    fun `sustained stream writes once per interval carrying the latest value and always lands the final one`() = runTest {
        val coalescer = TargetWriteCoalescer(backgroundScope, INTERVAL) { written.add(it) }

        // 30 submits, 100 ms apart (~3 s), values 0.5, 1.0, ... 15.0 — like a held + button.
        var value = 0f
        repeat(30) {
            value += 0.5f
            coalescer.submit(value)
            advanceTimeBy(100)
        }
        advanceTimeBy(INTERVAL * 2) // run the final background cooldown flush

        // One leading write + one per elapsed interval; far fewer than 30.
        assertThat(written.size).isAtMost(1 + (30 * 100 / INTERVAL).toInt() + 1)
        assertThat(written.size).isAtLeast(3)
        // Monotonically increasing (always the newest value at flush time) and the final target landed.
        assertThat(written).isInOrder()
        assertThat(written.last()).isEqualTo(15.0f)
    }

    @Test
    fun `after a quiet gap the next submit is immediate again`() = runTest {
        val coalescer = TargetWriteCoalescer(backgroundScope, INTERVAL) { written.add(it) }

        coalescer.submit(1.0f)
        advanceTimeBy(INTERVAL + 100)
        val countBefore = written.size
        coalescer.submit(2.0f)
        runCurrent()

        assertThat(written.size).isEqualTo(countBefore + 1)
        assertThat(written.last()).isEqualTo(2.0f)
    }

    @Test
    fun `a quiet-time resend of the same value is written again`() = runTest {
        // Re-assert semantics: the console may have self-stepped away from the last written
        // target (physical incline keys), so an idle-time duplicate must reach the wire — as it
        // always did before the coalescer existed.
        val coalescer = TargetWriteCoalescer(backgroundScope, INTERVAL) { written.add(it) }

        coalescer.submit(5.0f)
        advanceTimeBy(INTERVAL * 2)
        coalescer.submit(5.0f)
        advanceTimeBy(INTERVAL * 2)

        assertThat(written).containsExactly(5.0f, 5.0f).inOrder()
    }

    @Test
    fun `pending value equal to lastWritten at expiry is dropped`() = runTest {
        val coalescer = TargetWriteCoalescer(backgroundScope, INTERVAL) { written.add(it) }

        coalescer.submit(1.0f)
        advanceTimeBy(100); coalescer.submit(2.0f)
        advanceTimeBy(100); coalescer.submit(1.0f) // back to the already-written value
        advanceTimeBy(INTERVAL * 2)

        assertThat(written).containsExactly(1.0f)
    }

    @Test
    fun `close drops the pending flush and blocks later submits`() = runTest {
        val coalescer = TargetWriteCoalescer(backgroundScope, INTERVAL) { written.add(it) }

        coalescer.submit(1.0f)
        advanceTimeBy(100); coalescer.submit(3.0f) // pending
        coalescer.close()
        advanceTimeBy(INTERVAL * 2)
        coalescer.submit(9.0f)
        advanceTimeBy(INTERVAL * 2)

        assertThat(written).containsExactly(1.0f)
    }

    private companion object {
        const val INTERVAL = BaseFitProSession.GRADE_WRITE_INTERVAL_MS
    }
}
