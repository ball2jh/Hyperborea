package com.nettarion.hyperborea.di

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.broadcast.dircon.DirconAdapter
import com.nettarion.hyperborea.broadcast.ftms.FtmsAdapter
import com.nettarion.hyperborea.core.BroadcastAdapter
import com.nettarion.hyperborea.core.HardwareAdapter
import com.nettarion.hyperborea.hardware.fitpro.FitProAdapter
import org.junit.Test

class AdapterModuleTest {

    @Test
    fun `FitProAdapter implements HardwareAdapter`() {
        assertThat(FitProAdapter()).isInstanceOf(HardwareAdapter::class.java)
    }

    @Test
    fun `FtmsAdapter implements BroadcastAdapter`() {
        assertThat(FtmsAdapter()).isInstanceOf(BroadcastAdapter::class.java)
    }

    @Test
    fun `DirconAdapter implements BroadcastAdapter`() {
        assertThat(DirconAdapter()).isInstanceOf(BroadcastAdapter::class.java)
    }
}
