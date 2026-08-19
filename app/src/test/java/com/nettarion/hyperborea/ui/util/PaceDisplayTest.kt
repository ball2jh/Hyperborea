package com.nettarion.hyperborea.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaceDisplayTest {

    @Test
    fun `metric pace from kph`() {
        // The reference case: ~9.2 km/h is a 6:31 min/km pace (60 / 9.2 = 6.5217 min).
        assertThat(UnitFormatter.paceDisplay(9.2f, imperial = false)).isEqualTo("6:31 min/km")
        assertThat(UnitFormatter.paceDisplay(12f, imperial = false)).isEqualTo("5:00 min/km")
        assertThat(UnitFormatter.paceDisplay(10f, imperial = false)).isEqualTo("6:00 min/km")
    }

    @Test
    fun `imperial pace converts to min per mile`() {
        // 9.66 km/h = 6.0 mph = 10:00 min/mi.
        assertThat(UnitFormatter.paceDisplay(9.656f, imperial = true)).isEqualTo("10:00 min/mi")
    }

    @Test
    fun `stopped or crawling belt shows no pace`() {
        assertThat(UnitFormatter.paceDisplay(0f, imperial = false)).isNull()
        assertThat(UnitFormatter.paceDisplay(0.05f, imperial = false)).isNull()
        // 1.9 km/h → 31:35 min/km — slower than any pace worth reading.
        assertThat(UnitFormatter.paceDisplay(1.9f, imperial = false)).isNull()
    }

    @Test
    fun `pace seconds are zero-padded`() {
        // 11.5 km/h → 5.217 min → 5:13.
        assertThat(UnitFormatter.paceDisplay(11.5f, imperial = false)).isEqualTo("5:13 min/km")
    }
}
