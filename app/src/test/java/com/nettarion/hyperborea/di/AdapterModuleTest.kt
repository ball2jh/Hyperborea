package com.nettarion.hyperborea.di

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.broadcast.wftnp.WftnpAdapter
import com.nettarion.hyperborea.broadcast.ftms.FtmsAdapter
import com.nettarion.hyperborea.core.adapter.BroadcastAdapter
import com.nettarion.hyperborea.core.adapter.HardwareAdapter
import com.nettarion.hyperborea.hardware.fitpro.FitProAdapter
import org.junit.Test

class AdapterModuleTest {

    @Test
    fun `FitProAdapter implements HardwareAdapter`() {
        assertThat(HardwareAdapter::class.java.isAssignableFrom(FitProAdapter::class.java)).isTrue()
    }

    @Test
    fun `FtmsAdapter implements BroadcastAdapter`() {
        assertThat(BroadcastAdapter::class.java.isAssignableFrom(FtmsAdapter::class.java)).isTrue()
    }

    @Test
    fun `WftnpAdapter implements BroadcastAdapter`() {
        assertThat(BroadcastAdapter::class.java.isAssignableFrom(WftnpAdapter::class.java)).isTrue()
    }
}
