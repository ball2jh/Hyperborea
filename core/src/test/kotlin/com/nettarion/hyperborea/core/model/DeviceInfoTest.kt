package com.nettarion.hyperborea.core.model

import com.google.common.truth.Truth.assertThat
import com.nettarion.hyperborea.core.test.buildDeviceInfo
import org.junit.Test

class DeviceInfoTest {

    @Test
    fun `supportedMetrics is a Set — no duplicates`() {
        val info = buildDeviceInfo(
            name = "Bike",
            supportedMetrics = setOf(Metric.POWER, Metric.POWER, Metric.CADENCE),
        )
        assertThat(info.supportedMetrics).hasSize(2)
    }
}
