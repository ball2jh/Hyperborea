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
    fun `runner wraps forward at every boundary through ten laps`() {
        // Reproduce the reported long-run scenario, including either side of each finish line.
        for (lap in 1..10) {
            val boundaryKm = lap * 0.4f
            val before = boundaryKm - 0.001f
            val after = boundaryKm + 0.001f

            assertThat(TrackMath.completedLaps(before)).isEqualTo(lap - 1)
            assertThat(TrackMath.lapProgress(before)).isWithin(1e-4f).of(0.9975f)
            assertThat(TrackMath.completedLaps(boundaryKm)).isEqualTo(lap)
            assertThat(TrackMath.lapProgress(boundaryKm)).isWithin(1e-4f).of(0f)
            assertThat(TrackMath.completedLaps(after)).isEqualTo(lap)
            assertThat(TrackMath.lapProgress(after)).isWithin(1e-4f).of(0.0025f)

            // The animation target must keep increasing while the drawn fraction wraps.
            assertThat(TrackMath.continuousLaps(before)).isLessThan(lap.toFloat())
            assertThat(TrackMath.continuousLaps(boundaryKm)).isWithin(1e-4f).of(lap.toFloat())
            assertThat(TrackMath.continuousLaps(after)).isGreaterThan(lap.toFloat())
        }
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
        // Bounding box: (84.39 + 2×36.80) wide by (2×36.80) tall.
        assertThat(TrackMath.TRACK_ASPECT_RATIO).isWithin(1e-3f).of(157.99f / 73.60f)
    }

    @Test
    fun `straights and measuring-line curves total 400 meters`() {
        val trackMeters = 2f * TrackMath.STRAIGHT_METERS +
            2f * Math.PI.toFloat() * TrackMath.CURVE_RADIUS_METERS

        assertThat(trackMeters).isWithin(0.01f).of(TrackMath.LAP_METERS)
    }

    @Test
    fun `continuousLaps is laps plus fraction`() {
        assertThat(TrackMath.continuousLaps(0.5f)).isWithin(1e-4f).of(1.25f)
        assertThat(TrackMath.continuousLaps(1.0f)).isWithin(1e-4f).of(2.5f)
    }
}
