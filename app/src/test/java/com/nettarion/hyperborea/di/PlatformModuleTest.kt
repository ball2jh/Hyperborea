package com.nettarion.hyperborea.di

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.LogStore
import com.nettarion.hyperborea.core.system.SystemLogCapture
import com.nettarion.hyperborea.core.system.SystemLogStore
import com.nettarion.hyperborea.platform.RingBufferLogStore
import com.nettarion.hyperborea.platform.systemlog.RingBufferSystemLogStore
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlatformModuleTest {

    @Test
    fun `provideApplicationScope uses SupervisorJob`() {
        val scope = PlatformModule.provideApplicationScope()
        val job = scope.coroutineContext[kotlinx.coroutines.Job]
        assertThat(job).isNotNull()
        assertThat(job.toString()).contains("SupervisorJob")
    }

    @Test
    fun `RingBufferLogStore implements AppLogger`() {
        val store = RingBufferLogStore()
        assertThat(store).isInstanceOf(AppLogger::class.java)
    }

    @Test
    fun `RingBufferLogStore implements LogStore`() {
        val store = RingBufferLogStore()
        assertThat(store).isInstanceOf(LogStore::class.java)
    }

    @Test
    fun `provideAppLogger and provideLogStore return same instance`() {
        val store = PlatformModule.provideRingBufferLogStore()
        val logger: AppLogger = PlatformModule.provideAppLogger(store)
        val logStore: LogStore = PlatformModule.provideLogStore(store)
        assertThat(logger).isSameInstanceAs(logStore)
    }

    // --- System log dual-binding ---

    @Test
    fun `RingBufferSystemLogStore implements SystemLogCapture`() {
        val scope = PlatformModule.provideApplicationScope()
        val logStore = PlatformModule.provideRingBufferLogStore()
        val logger = PlatformModule.provideAppLogger(logStore)
        val store = PlatformModule.provideRingBufferSystemLogStore(logger, scope)
        assertThat(store).isInstanceOf(SystemLogCapture::class.java)
    }

    @Test
    fun `RingBufferSystemLogStore implements SystemLogStore`() {
        val scope = PlatformModule.provideApplicationScope()
        val logStore = PlatformModule.provideRingBufferLogStore()
        val logger = PlatformModule.provideAppLogger(logStore)
        val store = PlatformModule.provideRingBufferSystemLogStore(logger, scope)
        assertThat(store).isInstanceOf(SystemLogStore::class.java)
    }

    @Test
    fun `provideSystemLogCapture and provideSystemLogStore return same instance`() {
        val scope = PlatformModule.provideApplicationScope()
        val logStore = PlatformModule.provideRingBufferLogStore()
        val logger = PlatformModule.provideAppLogger(logStore)
        val store = PlatformModule.provideRingBufferSystemLogStore(logger, scope)
        val capture: SystemLogCapture = PlatformModule.provideSystemLogCapture(store)
        val sysLogStore: SystemLogStore = PlatformModule.provideSystemLogStore(store)
        assertThat(capture).isSameInstanceAs(sysLogStore)
    }
}
