package com.nettarion.hyperborea.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class DeviceCommandTest {

    @Test
    fun `SetResistance is a DeviceCommand`() {
        val cmd: DeviceCommand = DeviceCommand.SetResistance(level = 10)
        assertIs<DeviceCommand.SetResistance>(cmd)
    }

    @Test
    fun `SetIncline is a DeviceCommand`() {
        val cmd: DeviceCommand = DeviceCommand.SetIncline(percent = 5.0f)
        assertIs<DeviceCommand.SetIncline>(cmd)
    }

    @Test
    fun `SetResistance data class equality`() {
        val a = DeviceCommand.SetResistance(level = 10)
        val b = DeviceCommand.SetResistance(level = 10)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `SetResistance inequality on different level`() {
        val a = DeviceCommand.SetResistance(level = 10)
        val b = DeviceCommand.SetResistance(level = 20)
        assertNotEquals(a, b)
    }

    @Test
    fun `SetIncline data class equality`() {
        val a = DeviceCommand.SetIncline(percent = 3.5f)
        val b = DeviceCommand.SetIncline(percent = 3.5f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `SetIncline inequality on different percent`() {
        val a = DeviceCommand.SetIncline(percent = 3.5f)
        val b = DeviceCommand.SetIncline(percent = 7.0f)
        assertNotEquals(a, b)
    }

    @Test
    fun `when expression is exhaustive over sealed variants`() {
        val commands: List<DeviceCommand> = listOf(
            DeviceCommand.SetResistance(level = 5),
            DeviceCommand.SetIncline(percent = 2.0f),
        )
        val results = commands.map { cmd ->
            when (cmd) {
                is DeviceCommand.SetResistance -> "resistance:${cmd.level}"
                is DeviceCommand.SetIncline -> "incline:${cmd.percent}"
            }
        }
        assertThat(results).containsExactly("resistance:5", "incline:2.0").inOrder()
    }
}
