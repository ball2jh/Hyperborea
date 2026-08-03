package com.nettarion.hyperborea.ui.profile

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.model.DeviceType
import org.junit.Test

class PickerPromptTest {

    @Test
    fun `prompt matches device type`() {
        assertThat(pickerPrompt(DeviceType.BIKE)).isEqualTo("Who's riding?")
        assertThat(pickerPrompt(DeviceType.TREADMILL)).isEqualTo("Who's running?")
        assertThat(pickerPrompt(DeviceType.ROWER)).isEqualTo("Who's rowing?")
        assertThat(pickerPrompt(DeviceType.ELLIPTICAL)).isEqualTo("Who's training?")
    }

    @Test
    fun `prompt falls back to generic copy when device unknown`() {
        assertThat(pickerPrompt(null)).isEqualTo("Who's working out?")
    }

    @Test
    fun `guest subtitle matches device type`() {
        assertThat(guestSubtitle(DeviceType.BIKE)).isEqualTo("Ride without saving")
        assertThat(guestSubtitle(DeviceType.TREADMILL)).isEqualTo("Run without saving")
        assertThat(guestSubtitle(DeviceType.ROWER)).isEqualTo("Row without saving")
        assertThat(guestSubtitle(DeviceType.ELLIPTICAL)).isEqualTo("Train without saving")
        assertThat(guestSubtitle(null)).isEqualTo("Work out without saving")
    }
}
