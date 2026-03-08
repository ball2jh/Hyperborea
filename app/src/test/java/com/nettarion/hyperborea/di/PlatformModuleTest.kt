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
        val store = LoggingModule.provideRingBufferLogStore()
        val logger: AppLogger = LoggingModule.provideAppLogger(store)
        val logStore: LogStore = LoggingModule.provideLogStore(store)
        assertThat(logger).isSameInstanceAs(logStore)
    }

    // --- System log dual-binding ---

    @Test
    fun `RingBufferSystemLogStore implements SystemLogCapture`() {
        val scope = PlatformModule.provideApplicationScope()
        val logStore = LoggingModule.provideRingBufferLogStore()
        val logger = LoggingModule.provideAppLogger(logStore)
        val store = SystemModule.provideRingBufferSystemLogStore(logger, scope)
        assertThat(store).isInstanceOf(SystemLogCapture::class.java)
    }

    @Test
    fun `RingBufferSystemLogStore implements SystemLogStore`() {
        val scope = PlatformModule.provideApplicationScope()
        val logStore = LoggingModule.provideRingBufferLogStore()
        val logger = LoggingModule.provideAppLogger(logStore)
        val store = SystemModule.provideRingBufferSystemLogStore(logger, scope)
        assertThat(store).isInstanceOf(SystemLogStore::class.java)
    }

    @Test
    fun `provideSystemLogCapture and provideSystemLogStore return same instance`() {
        val scope = PlatformModule.provideApplicationScope()
        val logStore = LoggingModule.provideRingBufferLogStore()
        val logger = LoggingModule.provideAppLogger(logStore)
        val store = SystemModule.provideRingBufferSystemLogStore(logger, scope)
        val capture: SystemLogCapture = SystemModule.provideSystemLogCapture(store)
        val sysLogStore: SystemLogStore = SystemModule.provideSystemLogStore(store)
        assertThat(capture).isSameInstanceAs(sysLogStore)
    }
}
