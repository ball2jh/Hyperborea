package com.nettarion.hyperborea.ui.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MetricDisplayTest {

    @Test
    fun `no goal without a target`() {
        assertThat(goalVisible(target = null, actual = 5f, tolerance = 0.25f)).isFalse()
    }

    @Test
    fun `goal shows when there is a target but no actual reading yet`() {
        assertThat(goalVisible(target = 5f, actual = null, tolerance = 0.25f)).isTrue()
    }

    @Test
    fun `goal shows while en route and hides on arrival`() {
        assertThat(goalVisible(target = 5f, actual = 2f, tolerance = 0.25f)).isTrue()
        assertThat(goalVisible(target = 5f, actual = 5f, tolerance = 0.25f)).isFalse()
    }

    @Test
    fun `actual within tolerance counts as reached`() {
        // Machines settle slightly off the commanded value (their own quantization grid); the
        // tolerance floor keeps the goal from lingering forever in that case.
        assertThat(goalVisible(target = 1.0f, actual = 1.1f, tolerance = 0.25f)).isFalse()
        assertThat(goalVisible(target = 2.0f, actual = 2.1f, tolerance = 0.25f)).isFalse()
    }

    @Test
    fun `difference just past tolerance still shows the goal`() {
        assertThat(goalVisible(target = 5f, actual = 4.7f, tolerance = 0.25f)).isTrue()
    }
}
