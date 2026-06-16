package com.nettarion.hyperborea.hardware.fitpro.protocol

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.hardware.fitpro.v2.V2FeatureId
import org.junit.Test

class V2FeatureIdTest {

    @Test
    fun `fromCode round-trip for all features`() {
        for (feature in V2FeatureId.entries) {
            assertThat(V2FeatureId.fromCode(feature.code)).isEqualTo(feature)
        }
    }

    @Test
    fun `fromWireBytes round-trip for all features`() {
        for (feature in V2FeatureId.entries) {
            assertThat(V2FeatureId.fromWireBytes(feature.wireLo, feature.wireHi)).isEqualTo(feature)
        }
    }

    @Test
    fun `fromCode returns null for unknown code`() {
        assertThat(V2FeatureId.fromCode(9999)).isNull()
    }

    @Test
    fun `fromWireBytes returns null for unknown bytes`() {
        assertThat(V2FeatureId.fromWireBytes(0xFF.toByte(), 0xFF.toByte())).isNull()
    }

    @Test
    fun `RPM wire bytes`() {
        val feature = V2FeatureId.RPM
        assertThat(feature.code).isEqualTo(322)
        assertThat(feature.wireLo).isEqualTo(0x42.toByte())
        assertThat(feature.wireHi).isEqualTo(0x01.toByte())
    }

    @Test
    fun `WATTS wire bytes`() {
        val feature = V2FeatureId.WATTS
        assertThat(feature.code).isEqualTo(522)
        assertThat(feature.wireLo).isEqualTo(0x0A.toByte())
        assertThat(feature.wireHi).isEqualTo(0x02.toByte())
    }

    @Test
    fun `RUNNING_TIME wire bytes`() {
        val feature = V2FeatureId.RUNNING_TIME
        assertThat(feature.code).isEqualTo(604)
        assertThat(feature.wireLo).isEqualTo(0x5C.toByte())
        assertThat(feature.wireHi).isEqualTo(0x02.toByte())
    }

    @Test
    fun `limit feature wire bytes`() {
        assertThat(V2FeatureId.MAX_KPH.code).isEqualTo(304)
        assertThat(V2FeatureId.MAX_GRADE_PERCENT.code).isEqualTo(404)
        assertThat(V2FeatureId.MAX_WATTS.code).isEqualTo(528)
        // Round-trips through the wire-byte encoding like every other feature.
        assertThat(V2FeatureId.fromWireBytes(V2FeatureId.MAX_KPH.wireLo, V2FeatureId.MAX_KPH.wireHi))
            .isEqualTo(V2FeatureId.MAX_KPH)
    }

    @Test
    fun `pre-workout config feature wire codes`() {
        assertThat(V2FeatureId.DISPLAY_UNITS.code).isEqualTo(140)
        assertThat(V2FeatureId.GOAL_TIME.code).isEqualTo(610)
        assertThat(V2FeatureId.WARM_UP_TIMEOUT.code).isEqualTo(615)
        assertThat(V2FeatureId.COOL_DOWN_TIMEOUT.code).isEqualTo(617)
        assertThat(V2FeatureId.PAUSE_TIMEOUT.code).isEqualTo(619)
        // Write-only — never subscribed.
        assertThat(V2FeatureId.subscribable).containsNoneOf(
            V2FeatureId.DISPLAY_UNITS, V2FeatureId.GOAL_TIME, V2FeatureId.WARM_UP_TIMEOUT,
            V2FeatureId.COOL_DOWN_TIMEOUT, V2FeatureId.PAUSE_TIMEOUT,
        )
    }

    @Test
    fun `per-workout caps are not modelled`() {
        // WORKOUT_MAX_KPH(308) / WORKOUT_MAX_GRADE_PERCENT(408) are per-workout caps, not equipment
        // limits — deliberately excluded so they're never mistaken for the device's physical bounds.
        assertThat(V2FeatureId.fromCode(308)).isNull()
        assertThat(V2FeatureId.fromCode(408)).isNull()
    }

    @Test
    fun `subscribable includes TARGET_KPH and the reported limits but not target grade`() {
        // Belt machines report actual belt speed in the writable TARGET_KPH field (CURRENT_KPH is
        // never sent), so we subscribe to it. The device also reports its own physical limits.
        assertThat(V2FeatureId.subscribable).contains(V2FeatureId.TARGET_KPH)
        assertThat(V2FeatureId.subscribable).containsAtLeast(
            V2FeatureId.MAX_KPH, V2FeatureId.MIN_KPH,
            V2FeatureId.MAX_GRADE_PERCENT, V2FeatureId.MIN_GRADE_PERCENT,
            V2FeatureId.MAX_RESISTANCE, V2FeatureId.MAX_WATTS,
        )
        assertThat(V2FeatureId.subscribable).doesNotContain(V2FeatureId.TARGET_GRADE)
        assertThat(V2FeatureId.subscribable).doesNotContain(V2FeatureId.START_REQUESTED)
    }

    @Test
    fun `subscribable list includes sensor features`() {
        assertThat(V2FeatureId.subscribable).containsAtLeast(
            V2FeatureId.RPM, V2FeatureId.WATTS, V2FeatureId.CURRENT_KPH,
            V2FeatureId.CURRENT_GRADE, V2FeatureId.DISTANCE,
        )
    }
}
