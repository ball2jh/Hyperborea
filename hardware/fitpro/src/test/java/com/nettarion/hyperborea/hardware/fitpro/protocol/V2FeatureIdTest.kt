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
    fun `HEART_BEAT_INTERVAL wire bytes`() {
        val feature = V2FeatureId.HEART_BEAT_INTERVAL
        assertThat(feature.wireLo).isEqualTo(0xA1.toByte())
        assertThat(feature.wireHi).isEqualTo(0x00.toByte())
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
    fun `subscribable list does not include HEART_BEAT_INTERVAL`() {
        assertThat(V2FeatureId.subscribable).doesNotContain(V2FeatureId.HEART_BEAT_INTERVAL)
    }

    @Test
    fun `subscribable list does not include target features`() {
        assertThat(V2FeatureId.subscribable).doesNotContain(V2FeatureId.TARGET_KPH)
        assertThat(V2FeatureId.subscribable).doesNotContain(V2FeatureId.TARGET_GRADE)
    }

    @Test
    fun `subscribable list includes sensor features`() {
        assertThat(V2FeatureId.subscribable).containsAtLeast(
            V2FeatureId.RPM, V2FeatureId.WATTS, V2FeatureId.CURRENT_KPH,
            V2FeatureId.CURRENT_GRADE, V2FeatureId.DISTANCE,
        )
    }
}
