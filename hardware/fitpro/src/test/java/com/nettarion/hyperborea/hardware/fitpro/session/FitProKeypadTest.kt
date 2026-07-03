package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FitProKeypadTest {

    @Test
    fun `maps keypad codes to console keys`() {
        assertThat(FitProKeypad.consoleKeyFromCode(1)).isEqualTo(ConsoleKey.STOP)
        assertThat(FitProKeypad.consoleKeyFromCode(2)).isEqualTo(ConsoleKey.START)
        assertThat(FitProKeypad.consoleKeyFromCode(3)).isEqualTo(ConsoleKey.SPEED_UP)
        assertThat(FitProKeypad.consoleKeyFromCode(4)).isEqualTo(ConsoleKey.SPEED_DOWN)
        assertThat(FitProKeypad.consoleKeyFromCode(5)).isEqualTo(ConsoleKey.INCLINE_UP)
        assertThat(FitProKeypad.consoleKeyFromCode(6)).isEqualTo(ConsoleKey.INCLINE_DOWN)
        assertThat(FitProKeypad.consoleKeyFromCode(7)).isEqualTo(ConsoleKey.RESISTANCE_UP)
        assertThat(FitProKeypad.consoleKeyFromCode(8)).isEqualTo(ConsoleKey.RESISTANCE_DOWN)
    }

    @Test
    fun `gear codes map to resistance — bike consoles' plus-minus buttons are the gear selector`() {
        assertThat(FitProKeypad.consoleKeyFromCode(9)).isEqualTo(ConsoleKey.RESISTANCE_UP)
        assertThat(FitProKeypad.consoleKeyFromCode(10)).isEqualTo(ConsoleKey.RESISTANCE_DOWN)
    }

    @Test
    fun `unmapped codes return null`() {
        assertThat(FitProKeypad.consoleKeyFromCode(0)).isNull()
        assertThat(FitProKeypad.consoleKeyFromCode(200)).isNull() // volume
        assertThat(FitProKeypad.consoleKeyFromCode(1300)).isNull() // direct resistance-level keys
    }
}
