package com.nettarion.hyperborea.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HoldRepeatTest {

    @Test
    fun `the first repeat keeps the old flat interval`() {
        // A short hold must still land an exact value — the ramp only bites once it's clear the
        // user is sweeping, not trimming.
        assertThat(HoldRepeat.intervalMs(0)).isEqualTo(HoldRepeat.START_INTERVAL_MS)
    }

    @Test
    fun `intervals shorten monotonically across the ramp`() {
        val intervals = (0..HoldRepeat.RAMP_TICKS).map { HoldRepeat.intervalMs(it) }
        assertThat(intervals).isInStrictOrder(Comparator<Long> { a, b -> b.compareTo(a) })
    }

    @Test
    fun `the ramp bottoms out at the floor and stays there`() {
        assertThat(HoldRepeat.intervalMs(HoldRepeat.RAMP_TICKS)).isEqualTo(HoldRepeat.MIN_INTERVAL_MS)
        assertThat(HoldRepeat.intervalMs(HoldRepeat.RAMP_TICKS + 1)).isEqualTo(HoldRepeat.MIN_INTERVAL_MS)
        assertThat(HoldRepeat.intervalMs(10_000)).isEqualTo(HoldRepeat.MIN_INTERVAL_MS)
    }

    @Test
    fun `never returns a non-positive delay`() {
        // A zero or negative delay would turn the repeater into a busy loop.
        (0..100).forEach { assertThat(HoldRepeat.intervalMs(it)).isGreaterThan(0L) }
    }

    @Test
    fun `a full-range treadmill sweep stays around ten seconds`() {
        // The design target: 0 to 20 km/h in 0.1 steps is 200 repeats. That has to feel like the
        // 0.5-step incline cluster's full-range hold did, not like a minute of waiting.
        val heldMs = HoldRepeat.INITIAL_MS + (0 until 200).sumOf { HoldRepeat.intervalMs(it) }
        assertThat(heldMs).isIn(com.google.common.collect.Range.closed(8_000L, 16_000L))
    }
}
