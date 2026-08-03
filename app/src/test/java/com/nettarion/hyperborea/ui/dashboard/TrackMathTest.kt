package com.nettarion.hyperborea.ui.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackMathTest {

    @Test
    fun `null distance yields zero laps and zero progress`() {
        assertThat(TrackMath.completedLaps(null)).isEqualTo(0)
        assertThat(TrackMath.lapProgress(null)).isEqualTo(0f)
        assertThat(TrackMath.continuousLaps(null)).isEqualTo(0f)
    }

    @Test
    fun `negative distance yields zero laps and zero progress`() {
        assertThat(TrackMath.completedLaps(-1f)).isEqualTo(0)
        assertThat(TrackMath.lapProgress(-1f)).isEqualTo(0f)
    }

    @Test
    fun `zero distance yields zero laps and zero progress`() {
        assertThat(TrackMath.completedLaps(0f)).isEqualTo(0)
        assertThat(TrackMath.lapProgress(0f)).isEqualTo(0f)
    }

    @Test
    fun `mid-first-lap distance`() {
        // 100 m = quarter of a 400 m lap
        assertThat(TrackMath.completedLaps(0.1f)).isEqualTo(0)
        assertThat(TrackMath.lapProgress(0.1f)).isWithin(1e-4f).of(0.25f)
    }

    @Test
    fun `just under one lap`() {
        assertThat(TrackMath.completedLaps(0.399f)).isEqualTo(0)
        assertThat(TrackMath.lapProgress(0.399f)).isWithin(1e-3f).of(0.9975f)
    }

    @Test
    fun `exactly one lap wraps to zero progress`() {
        assertThat(TrackMath.completedLaps(0.4f)).isEqualTo(1)
        assertThat(TrackMath.lapProgress(0.4f)).isWithin(1e-4f).of(0f)
    }

    @Test
    fun `many laps`() {
        // 10 km = 25 laps exactly
        assertThat(TrackMath.completedLaps(10f)).isEqualTo(25)
        assertThat(TrackMath.lapProgress(10f)).isWithin(1e-3f).of(0f)
    }

    @Test
    fun `progress stays below one across float-precision edges`() {
        // Sweep values near lap boundaries — float rounding must never produce progress >= 1.
        var km = 0.3999f
        repeat(2000) {
            val progress = TrackMath.lapProgress(km)
            assertThat(progress).isAtLeast(0f)
            assertThat(progress).isLessThan(1f)
            km += 0.0001f
        }
    }

    @Test
    fun `track aspect ratio matches standard 400m geometry`() {
        // Bounding box: (84.39 + 2×36.50) wide by (2×36.50) tall.
        assertThat(TrackMath.TRACK_ASPECT_RATIO).isWithin(1e-3f).of(157.39f / 73.0f)
    }

    @Test
    fun `continuousLaps is laps plus fraction`() {
        assertThat(TrackMath.continuousLaps(0.5f)).isWithin(1e-4f).of(1.25f)
        assertThat(TrackMath.continuousLaps(1.0f)).isWithin(1e-4f).of(2.5f)
    }
}
