package com.nettarion.hyperborea.platform

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

class BoundedTeardownTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() { scope.cancel() }

    @Test
    fun `returns true once every block has finished`() {
        val a = AtomicBoolean(false)
        val b = AtomicBoolean(false)

        val finished = runTeardownWithin(scope, 5_000, { a.set(true) }, { delay(20); b.set(true) })

        assertThat(finished).isTrue()
        assertThat(a.get()).isTrue()
        assertThat(b.get()).isTrue()
    }

    @Test
    fun `an uncancellable teardown cannot hold the caller past the budget, and still completes`() {
        val done = AtomicBoolean(false)
        // Models Orchestrator.stop(): NonCancellable, and far slower than the budget (a V1 belt
        // halt against an MCU that has stopped answering takes tens of seconds).
        val slowTeardown: suspend () -> Unit = {
            withContext(NonCancellable) { delay(1_500); done.set(true) }
        }

        val startedAt = System.nanoTime()
        val finished = runTeardownWithin(scope, 100, slowTeardown)
        val blockedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertThat(finished).isFalse()
        // The caller (the main thread, in onDestroy) got its thread back at the budget — well
        // before the teardown's own 1.5 s — rather than joining the uncancellable work.
        assertThat(blockedMs).isLessThan(1_000)
        assertThat(done.get()).isFalse()

        // …and the teardown was not cancelled: it runs to completion on the scope.
        runBlocking { withTimeout(10_000) { scope.coroutineContext.job.children.forEach { it.join() } } }
        assertThat(done.get()).isTrue()
    }
}
