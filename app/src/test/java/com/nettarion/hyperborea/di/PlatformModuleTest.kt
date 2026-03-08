package com.nettarion.hyperborea.di

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.LogStore
import com.nettarion.hyperborea.core.system.SystemLogCapture
import com.nettarion.hyperborea.core.system.SystemLogStore
import org.junit.Test

class PlatformModuleTest {

    @Test
    fun `provideAppLogger and provideLogStore return same instance`() {
        val store = LoggingModule.provideRingBufferLogStore()
        val logger: AppLogger = LoggingModule.provideAppLogger(store)
        val logStore: LogStore = LoggingModule.provideLogStore(store)
        assertThat(logger).isSameInstanceAs(logStore)
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
